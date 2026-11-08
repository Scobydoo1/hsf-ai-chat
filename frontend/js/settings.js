// AI platforms: the "AI pill" in the top bar (quick switch), the Settings tab (cards + form),
// and "switch automatically when a platform hits its limit" (saved in this browser).

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

const TYPE_LABELS = {
    GEMINI: 'Google Gemini API', OPENAI_COMPATIBLE: 'OpenAI-compatible API', ANTHROPIC: 'Anthropic API', DEMO: 'Offline demo',
};

function activeProvider() {
    return state.providers.find((p) => p.active) || null;
}

/** Seconds a platform still rests after a limit. The server sends a snapshot; we count down here (no polling). */
function restSeconds(p) {
    return Math.max(0, Math.ceil((p.restUntil - Date.now()) / 1000));
}

let providersLoadedAt = 0; // when the list was last fetched (see REFRESH_AFTER_MS)

async function loadProviders() {
    try {
        const list = await call(() => Api.providers());
        const now = Date.now();
        state.providers = list.map((p) => ({ ...p, restUntil: now + (p.cooldownSeconds || 0) * 1000 }));
        providersLoadedAt = now;
        renderProviders();
    } catch { /* call() already showed the error */ }
}

function renderProviders() {
    renderAiPill();
    renderProviderCards();
    updateEmptyProvider();
}

// ---------- top bar: AI pill + dropdown ----------
function renderAiPill() {
    const active = activeProvider();
    $('aiPillName').textContent = active ? active.name : 'No AI yet';
    $('providerMenu').innerHTML = state.providers.map((p) => {
        const rest = restSeconds(p);
        return `<button type="button" class="dropdown-item provider-option" data-activate="${Number(p.id)}" aria-pressed="${p.active}">
                <span class="po-text">
                    <span class="po-name">${esc(p.name)}</span>
                    <span class="po-model">${esc(p.model)}</span>
                </span>
                ${p.type === 'DEMO' ? '<span class="tag">offline demo</span>' : ''}
                ${rest ? `<span class="tag tag-pink" title="${esc(p.lastError || 'Resting after a limit')}">resting ${rest} s</span>` : ''}
                <i class="bi bi-check-lg po-check" aria-hidden="true"></i>
            </button>`;
    }).join('') || '<p class="menu-empty">No platforms yet. Add one in Settings.</p>';
}

$('aiPill').parentElement.addEventListener('show.bs.dropdown', renderAiPill); // fresh countdowns each time it opens

$('providerMenu').addEventListener('click', (e) => {
    const btn = e.target.closest('[data-activate]');
    if (!btn) return;
    bootstrap.Dropdown.getOrCreateInstance($('aiPill')).hide();
    const id = Number(btn.dataset.activate);
    if (activeProvider()?.id !== id) activate(id);
});

async function activate(id) {
    try {
        const p = await call(() => Api.activateProvider(id));
        // update locally instead of re-fetching; activating also ends the platform's rest on the server
        state.providers.forEach((x) => {
            x.active = x.id === p.id;
            if (x.id === p.id) x.restUntil = 0;
        });
        renderProviders();
        toast(`${p.name} answers from now on`);
    } catch { /* shown by call() */ }
}

// ---------- automatic switching (navbar switch, composer pill, Settings switch: all the same setting) ----------
function setAutoFallback(on, save = true) {
    state.autoFallback = on;
    if (save) store.set('autoFallback', String(on));
    document.querySelectorAll('[data-auto-switch]').forEach((input) => { input.checked = on; });
    const pill = $('autoSwitchBtn');
    pill.setAttribute('aria-pressed', String(on));
    pill.querySelector('.label').textContent = on ? 'Auto-switch on' : 'Auto-switch off';
    pill.querySelector('i').className = on ? 'bi bi-lightning-charge-fill' : 'bi bi-lightning-charge';
}

document.querySelectorAll('[data-auto-switch]').forEach((input) => input.addEventListener('change', () => setAutoFallback(input.checked)));

$('autoSwitchBtn').addEventListener('click', () => {
    setAutoFallback(!state.autoFallback);
    toast(state.autoFallback
        ? 'Auto-switch on: another platform answers when one hits its limit.'
        : 'Auto-switch off: when a platform hits its limit, you choose what to do.');
});

// ---------- Settings tab: one card per platform ----------
function renderProviderCards() {
    $('providerGrid').innerHTML = state.providers.map((p) => {
        const rest = restSeconds(p);
        return `<article class="provider-card${p.active ? ' is-active' : ''}">
                <header class="pc-head">
                    <h3 class="pc-name">${esc(p.name)}</h3>
                    <span class="pc-type">${esc(TYPE_LABELS[p.type] || p.type)}</span>
                </header>
                <dl class="pc-facts">
                    <div><dt>Chat model</dt><dd>${esc(p.model)}</dd></div>
                    <div><dt>Embedding model</dt><dd>${p.embeddingModel ? esc(p.embeddingModel) : '—'}</dd></div>
                    <div><dt>API key</dt><dd>${p.hasKey ? esc(p.maskedKey) : '—'}</dd></div>
                </dl>
                <div class="tags">
                    ${p.active ? '<span class="tag tag-ink">Chat</span>' : ''}
                    ${p.useForEmbeddings ? '<span class="tag tag-green">Embeddings</span>' : ''}
                    ${rest ? `<span class="tag tag-pink">Resting ${rest} s</span>` : ''}
                </div>
                ${rest && p.lastError ? `<p class="pc-error">${esc(p.lastError)}</p>` : ''}
                <div class="pc-actions">
                    ${p.active ? '' : `<button type="button" class="btn btn-sm btn-primary" data-action="activate" data-id="${Number(p.id)}">Use for chat</button>`}
                    <button type="button" class="btn btn-sm btn-outline-secondary" data-action="test" data-id="${Number(p.id)}"
                            title="Send a test message">Test</button>
                    <span class="pc-spacer"></span>
                    <button type="button" class="icon-btn" data-action="edit" data-id="${Number(p.id)}" title="Edit"
                            aria-label="Edit ${esc(p.name)}"><i class="bi bi-pencil" aria-hidden="true"></i></button>
                    <button type="button" class="icon-btn danger" data-action="delete" data-id="${Number(p.id)}" title="Delete"
                            aria-label="Delete ${esc(p.name)}"><i class="bi bi-trash3" aria-hidden="true"></i></button>
                </div>
            </article>`;
    }).join('') || '<p class="empty-note">No platforms yet. Add one below.</p>';
}

$('providerGrid').addEventListener('click', async (e) => {
    const btn = e.target.closest('[data-action]');
    if (!btn) return;
    const id = Number(btn.dataset.id);
    const p = state.providers.find((x) => x.id === id);
    switch (btn.dataset.action) {
        case 'activate':
            await activate(id);
            break;
        case 'test': {
            btn.disabled = true;
            btn.innerHTML = '<span class="spinner-border spinner-border-sm" aria-hidden="true"></span><span class="visually-hidden">Testing</span>';
            try {
                const r = await call(() => Api.testProvider(id));
                if (r.ok) toast(`${p.name} works: ${r.reply}`);
                else toast(`${p.name} failed: ${r.error}`, 'danger');
                loadProviders(); // a test can start or end a platform's rest
            } catch { /* shown by call() */ } finally {
                btn.disabled = false;
                btn.textContent = 'Test';
            }
            break;
        }
        case 'edit':
            fillForm(p);
            $('providerFormCard').scrollIntoView({ behavior: prefersReducedMotion() ? 'auto' : 'smooth' });
            $('pName').focus({ preventScroll: true });
            break;
        case 'delete':
            if (!confirm(`Delete ${p.name}?`)) return;
            try { await call(() => Api.deleteProvider(id), 'Deleted'); } catch { return; }
            loadProviders();
            break;
    }
});

// ---------- add / edit form ----------
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
        ? `Get a key: <a href="${esc(preset.keyUrl)}" target="_blank" rel="noopener">${esc(preset.keyUrl)}</a>${preset.note ? ` · ${esc(preset.note)}` : ''}`
        : '';
}

$('pPreset').innerHTML = '<option value="">— choose —</option>'
    + Object.entries(PRESETS).map(([k, v]) => `<option value="${k}">${esc(v.label)}</option>`).join('');
$('pPreset').addEventListener('change', (e) => {
    const p = PRESETS[e.target.value];
    if (p) fillForm({ ...p, useForEmbeddings: !!p.embeddingModel && !state.providers.some((x) => x.useForEmbeddings && x.type !== 'DEMO') });
});
$('providerCancel').addEventListener('click', () => {
    $('providerForm').reset();
    fillForm({});
});

$('providerForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const id = $('pId').value;
    const body = {
        name: $('pName').value, type: $('pType').value, baseUrl: $('pBaseUrl').value, model: $('pModel').value,
        embeddingModel: $('pEmbeddingModel').value, apiKey: $('pApiKey').value, useForEmbeddings: $('pUseForEmbeddings').checked,
    };
    try { await call(() => (id ? Api.updateProvider(id, body) : Api.createProvider(body)), 'Saved'); } catch { return; }
    $('providerForm').reset();
    fillForm({});
    await loadProviders();
    if (body.useForEmbeddings) refreshStatus(); // a new embedding platform starts a background re-index
});

$('settingsTabBtn').addEventListener('shown.bs.tab', () => {
    renderProviderCards(); // show what we have at once (fresh countdowns)
    if (Date.now() - providersLoadedAt > REFRESH_AFTER_MS) loadProviders(); // then refresh, if older than a minute
});
