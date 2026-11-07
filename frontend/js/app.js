// AI Chat front-end: plain JavaScript + Bootstrap 5, no build step.

// ---------- presets for the Settings form (model names change over time: check each platform's docs) ----------
const PRESETS = {
    gemini: { label: 'Google Gemini (free tier)', name: 'Google Gemini', type: 'GEMINI', baseUrl: '', model: 'gemini-3.8-flash',
        embeddingModel: 'gemini-embedding-001', keyUrl: 'https://aistudio.google.com/apikey' },
    openai: { label: 'OpenAI', name: 'OpenAI', type: 'OPENAI_COMPATIBLE', baseUrl: 'https://api.openai.com/v1', model: 'gpt-5-mini',
        embeddingModel: 'text-embedding-3-small', keyUrl: 'https://platform.openai.com/api-keys' },
    anthropic: { label: 'Anthropic Claude', name: 'Anthropic Claude', type: 'ANTHROPIC', baseUrl: '', model: 'claude-opus-5-5',
        embeddingModel: '', keyUrl: 'https://console.anthropic.com/settings/keys',
        note: 'Claude has no embeddings API: keep another platform ticked for embeddings.' },
    groq: { label: 'Groq (free tier)', name: 'Groq', type: 'OPENAI_COMPATIBLE', baseUrl: 'https://api.groq.com/openai/v1',
        model: 'llama-3.3-70b-versatile', embeddingModel: '', keyUrl: 'https://console.groq.com/keys',
        note: 'Groq has no embeddings: keep another platform ticked for embeddings.' },
    ollama: { label: 'Ollama (runs on your PC)', name: 'Ollama (local)', type: 'OPENAI_COMPATIBLE', baseUrl: 'http://localhost:11434/v1',
        model: 'llama3.2', embeddingModel: 'nomic-embed-text', keyUrl: 'https://ollama.com/download',
        note: 'No key needed. Works only while the back-end runs on the same PC.' },
    demo: { label: 'Demo (offline, no key)', name: 'Demo (offline, no key)', type: 'DEMO', baseUrl: '', model: 'demo',
        embeddingModel: 'hashing-512', keyUrl: '' },
};

const state = { conversations: [], current: null, providers: [], sending: false };
const $ = (id) => document.getElementById(id);

// ---------- helpers ----------
function esc(text) {
    const div = document.createElement('div');
    div.textContent = text ?? '';
    return div.innerHTML;
}

function markdown(text) {
    return DOMPurify.sanitize(marked.parse(text ?? '')); // never trust HTML coming from an AI
}

function toast(message, type = 'success') {
    const el = $('toast');
    el.className = `toast align-items-center border-0 text-bg-${type}`;
    $('toastBody').textContent = message;
    bootstrap.Toast.getOrCreateInstance(el, { delay: type === 'danger' ? 8000 : 3000 }).show();
}

/** Run an API call; show errors; ask for the password on 401 */
async function call(fn, okMessage) {
    try {
        const result = await fn();
        if (okMessage) toast(okMessage);
        return result;
    } catch (e) {
        if (e.status === 401) {
            askPassword(true);
        } else {
            toast(e.message, 'danger');
        }
        throw e;
    }
}

// ---------- password ----------
const passwordModal = () => bootstrap.Modal.getOrCreateInstance($('passwordModal'));

function askPassword(wrong) {
    $('passwordError').classList.toggle('d-none', !wrong || !Api.getPassword());
    passwordModal().show();
    setTimeout(() => $('passwordInput').focus(), 300);
}

$('passwordForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    Api.setPassword($('passwordInput').value);
    try {
        await Api.providers();
        passwordModal().hide();
        loadAll();
    } catch (err) {
        $('passwordError').classList.remove('d-none');
    }
});

// ---------- provider switch (navbar) ----------
async function loadProviders() {
    state.providers = await call(() => Api.providers());
    const select = $('providerSwitch');
    select.innerHTML = state.providers.map(p =>
        `<option value="${p.id}" ${p.active ? 'selected' : ''}>${esc(p.name)} · ${esc(p.model)}</option>`).join('')
        || '<option>No provider</option>';
    renderProviderRows();
}

$('providerSwitch').addEventListener('change', async (e) => {
    const p = await call(() => Api.activateProvider(e.target.value));
    toast(`Now chatting with ${p.name} (${p.model})`);
    loadProviders();
});

// ---------- conversations ----------
async function loadConversations() {
    state.conversations = await call(() => Api.conversations());
    renderConversationList();
}

function renderConversationList() {
    $('conversationList').innerHTML = state.conversations.map(c => `
        <div class="list-group-item list-group-item-action conversation-item d-flex align-items-center gap-2 ${state.current?.id === c.id ? 'active' : ''}"
             data-id="${c.id}">
            <i class="bi ${c.mode === 'RAG' ? 'bi-journal-text' : 'bi-chat'}"></i>
            <span class="text-truncate flex-grow-1">${esc(c.title)}</span>
            <button class="btn btn-sm btn-link p-0 delete-btn ${state.current?.id === c.id ? 'text-white' : 'text-danger'}"
                    data-delete="${c.id}" title="Delete" aria-label="Delete conversation"><i class="bi bi-trash"></i></button>
        </div>`).join('') || '<p class="small text-body-secondary px-2">No conversations yet.</p>';
}

$('conversationList').addEventListener('click', async (e) => {
    const del = e.target.closest('[data-delete]');
    if (del) {
        e.stopPropagation();
        if (!confirm('Delete this conversation?')) return;
        const id = Number(del.dataset.delete);
        await call(() => Api.deleteConversation(id), 'Conversation deleted');
        if (state.current?.id === id) resetChat();
        loadConversations();
        return;
    }
    const item = e.target.closest('[data-id]');
    if (item) openConversation(Number(item.dataset.id));
});

document.querySelectorAll('[data-new-chat]').forEach(btn => btn.addEventListener('click', async () => {
    const c = await call(() => Api.createConversation(btn.dataset.newChat));
    await loadConversations();
    openConversation(c.id);
}));

async function openConversation(id) {
    state.current = state.conversations.find(c => c.id === id);
    renderConversationList();
    renderHeader();
    const messages = await call(() => Api.messages(id));
    $('messages').innerHTML = '';
    if (messages.length === 0) {
        $('messages').innerHTML = `<div class="empty-state text-center text-body-secondary">
            <i class="bi ${state.current.mode === 'RAG' ? 'bi-journal-text' : 'bi-chat-dots'} display-5"></i>
            <p class="mt-3">${state.current.mode === 'RAG'
                ? 'Ask a question about your documents. Answers cite their sources like [1].'
                : 'Ask anything.'}</p></div>`;
    }
    messages.forEach(appendMessage);
    $('messageInput').disabled = false;
    $('sendBtn').disabled = false;
    $('messageInput').focus();
    bootstrap.Offcanvas.getInstance($('sidebar'))?.hide();
}

function resetChat() {
    state.current = null;
    $('chatTitle').textContent = 'Start a new chat';
    $('chatMode').className = 'badge d-none';
    $('messages').innerHTML = '';
    $('messageInput').disabled = true;
    $('sendBtn').disabled = true;
}

function renderHeader() {
    $('chatTitle').textContent = state.current.title;
    const badge = $('chatMode');
    badge.className = `badge ${state.current.mode === 'RAG' ? 'text-bg-success' : 'text-bg-secondary'}`;
    badge.textContent = state.current.mode === 'RAG' ? 'RAG · documents' : 'Chat';
}

// ---------- messages ----------
function appendMessage(m) {
    $('messages').querySelector('.empty-state')?.remove();
    const row = document.createElement('div');
    row.className = `msg ${m.role}`;
    if (m.role === 'user') {
        row.innerHTML = `<div class="bubble">${esc(m.content)}</div>`;
    } else {
        const sources = (m.sources || []).length ? `
            <details class="sources mt-2"><summary>Sources (${m.sources.length})</summary><ol class="mt-2 mb-0 ps-3">
            ${m.sources.map(s => `<li><b>${esc(s.documentName)}</b> · part ${s.chunkIndex + 1} · ${esc(s.method)} score ${s.score}
                <div class="text-body-secondary">${esc(s.snippet)}</div></li>`).join('')}
            </ol></details>` : '';
        row.innerHTML = `<div class="bubble">${markdown(m.content)}${sources}
            <div class="meta"><i class="bi bi-cpu"></i> ${esc(m.provider || '')} · ${esc(m.model || '')}</div></div>`;
    }
    $('messages').appendChild(row);
    $('messages').scrollTop = $('messages').scrollHeight;
    return row;
}

function appendBubble(cls, html) {
    const row = document.createElement('div');
    row.className = `msg ${cls}`;
    row.innerHTML = `<div class="bubble">${html}</div>`;
    $('messages').appendChild(row);
    $('messages').scrollTop = $('messages').scrollHeight;
    return row;
}

async function sendMessage() {
    const input = $('messageInput');
    const text = input.value.trim();
    if (!text || !state.current || state.sending) return;
    state.sending = true;
    $('sendBtn').disabled = true;
    input.value = '';
    autoGrow();

    appendMessage({ role: 'user', content: text });
    const typing = appendBubble('assistant', '<span class="typing"><span></span><span></span><span></span></span>');
    try {
        const answer = await Api.send(state.current.id, text);
        typing.remove();
        appendMessage(answer);
        loadConversations().then(() => {
            state.current = state.conversations.find(c => c.id === state.current.id) || state.current;
            renderHeader();
        });
    } catch (e) {
        typing.remove();
        if (e.status === 401) askPassword(true);
        appendBubble('error', `<i class="bi bi-exclamation-triangle"></i> ${esc(e.message)}`);
    } finally {
        state.sending = false;
        $('sendBtn').disabled = false;
        input.focus();
    }
}

$('sendForm').addEventListener('submit', (e) => { e.preventDefault(); sendMessage(); });
$('messageInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); }
});
function autoGrow() {
    const el = $('messageInput');
    el.style.height = 'auto';
    el.style.height = Math.min(el.scrollHeight, 200) + 'px';
}
$('messageInput').addEventListener('input', autoGrow);

// ---------- documents ----------
async function loadDocuments() {
    const [docs, status] = await Promise.all([call(() => Api.documents()), call(() => Api.status())]);
    $('embeddingInfo').innerHTML = status.embeddingProvider
        ? `<i class="bi bi-info-circle"></i> New documents are embedded with <b>${esc(status.embeddingProvider)}</b> for vector search.
           Documents embedded with a different model use keyword search until you click <b>Re-index all</b>.`
        : `<i class="bi bi-info-circle"></i> No embedding platform is set, so RAG uses keyword search.
           In <b>Settings</b>, tick “Use for embeddings” on a platform that has an embedding model.`;
    $('documentRows').innerHTML = docs.map(d => `
        <tr>
            <td><i class="bi bi-file-earmark-text"></i> ${esc(d.name)}</td>
            <td class="text-end">${d.charCount.toLocaleString()}</td>
            <td class="text-end">${d.chunkCount}</td>
            <td>${d.embeddingModel
                ? `<span class="badge text-bg-success">vector</span> <span class="small text-body-secondary">${esc(d.embeddingModel)}</span>`
                : '<span class="badge text-bg-secondary">keyword</span>'}</td>
            <td class="text-end"><button class="btn btn-sm btn-outline-danger" data-delete-doc="${d.id}" title="Delete" aria-label="Delete document">
                <i class="bi bi-trash"></i></button></td>
        </tr>`).join('') || '<tr><td colspan="5" class="text-center text-body-secondary py-4">No documents yet.</td></tr>';
}

function showUploadResult(res) {
    if (res.warning) toast(res.warning, 'warning');
    else toast(`Added “${res.document.name}” (${res.document.chunkCount} chunks)`);
}

$('uploadForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const file = $('fileInput').files[0];
    if (!file) return;
    const btn = e.submitter; btn.disabled = true;
    try {
        showUploadResult(await call(() => Api.uploadDocument(file)));
        $('uploadForm').reset();
        loadDocuments();
    } finally { btn.disabled = false; }
});

$('textForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const btn = e.submitter; btn.disabled = true;
    try {
        showUploadResult(await call(() => Api.addTextDocument($('textName').value, $('textContent').value)));
        $('textForm').reset();
        loadDocuments();
    } finally { btn.disabled = false; }
});

$('documentRows').addEventListener('click', async (e) => {
    const btn = e.target.closest('[data-delete-doc]');
    if (!btn || !confirm('Delete this document?')) return;
    await call(() => Api.deleteDocument(btn.dataset.deleteDoc), 'Document deleted');
    loadDocuments();
});

$('reindexBtn').addEventListener('click', async (e) => {
    e.currentTarget.disabled = true;
    try {
        const r = await call(() => Api.reindex());
        toast(`Re-indexed ${r.documents} documents (${r.chunks} chunks) with ${r.embeddingModel}`);
        loadDocuments();
    } finally { $('reindexBtn').disabled = false; }
});

// ---------- settings ----------
function renderProviderRows() {
    $('providerRows').innerHTML = state.providers.map(p => `
        <tr>
            <td class="fw-semibold">${esc(p.name)}</td>
            <td><span class="small">${esc(p.type)}</span></td>
            <td><span class="small">${esc(p.model)}${p.embeddingModel ? `<br><span class="text-body-secondary">emb: ${esc(p.embeddingModel)}</span>` : ''}</span></td>
            <td><code class="small">${p.hasKey ? esc(p.maskedKey) : '—'}</code></td>
            <td>
                ${p.active ? '<span class="badge text-bg-primary">Chat</span>' : ''}
                ${p.useForEmbeddings ? '<span class="badge text-bg-success">Embeddings</span>' : ''}
            </td>
            <td class="text-end text-nowrap">
                ${p.active ? '' : `<button class="btn btn-sm btn-outline-primary" data-action="activate" data-id="${p.id}">Use for chat</button>`}
                <button class="btn btn-sm btn-outline-secondary" data-action="test" data-id="${p.id}" title="Send a test message">Test</button>
                <button class="btn btn-sm btn-outline-secondary" data-action="edit" data-id="${p.id}" title="Edit" aria-label="Edit"><i class="bi bi-pencil"></i></button>
                <button class="btn btn-sm btn-outline-danger" data-action="delete" data-id="${p.id}" title="Delete" aria-label="Delete"><i class="bi bi-trash"></i></button>
            </td>
        </tr>`).join('');
}

$('providerRows').addEventListener('click', async (e) => {
    const btn = e.target.closest('[data-action]');
    if (!btn) return;
    const id = Number(btn.dataset.id);
    const p = state.providers.find(x => x.id === id);
    switch (btn.dataset.action) {
        case 'activate':
            await call(() => Api.activateProvider(id), `Now chatting with ${p.name}`);
            loadProviders();
            break;
        case 'test': {
            btn.disabled = true;
            btn.innerHTML = '<span class="spinner-border spinner-border-sm"></span>';
            try {
                const r = await call(() => Api.testProvider(id));
                if (r.ok) toast(`${p.name} works: ${r.reply}`);
                else toast(`${p.name} failed: ${r.error}`, 'danger');
            } finally { btn.disabled = false; btn.textContent = 'Test'; }
            break;
        }
        case 'edit':
            fillForm(p);
            $('providerForm').scrollIntoView({ behavior: 'smooth' });
            break;
        case 'delete':
            if (!confirm(`Delete ${p.name}?`)) return;
            await call(() => Api.deleteProvider(id), 'Deleted');
            loadProviders();
            break;
    }
});

function fillForm(p) {
    $('providerFormTitle').textContent = p.id ? `Edit ${p.name}` : 'Add a platform';
    $('pId').value = p.id || '';
    $('pName').value = p.name || '';
    $('pType').value = p.type || 'GEMINI';
    $('pBaseUrl').value = p.baseUrl || '';
    $('pModel').value = p.model || '';
    $('pEmbeddingModel').value = p.embeddingModel || '';
    $('pApiKey').value = '';
    $('pApiKey').placeholder = p.hasKey ? `Saved: ${p.maskedKey} (leave empty to keep)` : 'Paste your API key';
    $('pUseForEmbeddings').checked = !!p.useForEmbeddings;
    const preset = PRESETS[$('pPreset').value];
    $('pKeyHelp').innerHTML = !p.id && preset && preset.keyUrl
        ? `Get a key: <a href="${preset.keyUrl}" target="_blank" rel="noopener">${esc(preset.keyUrl)}</a>${preset.note ? ' · ' + esc(preset.note) : ''}`
        : '';
}

$('pPreset').innerHTML = '<option value="">— choose —</option>' +
    Object.entries(PRESETS).map(([k, v]) => `<option value="${k}">${esc(v.label)}</option>`).join('');
$('pPreset').addEventListener('change', (e) => {
    const p = PRESETS[e.target.value];
    if (p) fillForm({ ...p, useForEmbeddings: !!p.embeddingModel && !state.providers.some(x => x.useForEmbeddings && x.type !== 'DEMO') });
});
$('providerCancel').addEventListener('click', () => { $('providerForm').reset(); fillForm({}); });

$('providerForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const id = $('pId').value;
    const body = {
        name: $('pName').value, type: $('pType').value, baseUrl: $('pBaseUrl').value, model: $('pModel').value,
        embeddingModel: $('pEmbeddingModel').value, apiKey: $('pApiKey').value, useForEmbeddings: $('pUseForEmbeddings').checked,
    };
    await call(() => id ? Api.updateProvider(id, body) : Api.createProvider(body), 'Saved');
    $('providerForm').reset();
    fillForm({});
    loadProviders();
});

// ---------- start ----------
document.getElementById('docs-tab-btn').addEventListener('shown.bs.tab', loadDocuments);
document.getElementById('settings-tab-btn').addEventListener('shown.bs.tab', loadProviders);

async function loadAll() {
    await Promise.all([loadProviders(), loadConversations()]);
}

(async function init() {
    fillForm({});
    try {
        const status = await Api.status();
        if (status.passwordRequired && !Api.getPassword()) {
            askPassword(false);
            return;
        }
        await loadAll();
    } catch (e) {
        if (e.status !== 401) toast(e.message, 'danger');
    }
})();
