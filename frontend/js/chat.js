// Chat tab: the conversation list, the document scope picker, the composer and sending a question.
// There is ONE kind of chat: every question can search documents or not, chosen in the composer.

/** Real questions from docs/TEST-QUESTIONS.md. docs = the answer is in the sample documents. */
const SUGGESTIONS = [
    { q: 'How much is a salted cream coffee, size L, at Green Leaf Café?', from: 'Café handbook', docs: true },
    { q: 'Lệ phí phúc khảo là bao nhiêu?', from: 'Exam rules (Vietnamese)', docs: true },
    { q: 'What do I need to pass the course?', from: 'Course syllabus', docs: true },
    { q: 'Explain @Transactional in two sentences.', from: 'General question', docs: false },
];

// ---------- conversation list (sidebar) ----------
async function loadConversations() {
    try {
        state.conversations = await call(() => Api.conversations());
        renderConversationList();
    } catch { /* call() already showed the error */ }
}

const sameDay = (a, b) => a.toDateString() === b.toDateString();

/** Draw the whole list once (after loading). Later changes patch single items, see below. */
function renderConversationList() {
    const now = new Date();
    const today = state.conversations.filter((c) => sameDay(new Date(c.updatedAt), now));
    const earlier = state.conversations.filter((c) => !today.includes(c));
    const group = (key, label, list) => (list.length
        ? `<h2 class="conv-group">${label}</h2><ul class="conv-list" data-group="${key}">${list.map(conversationItem).join('')}</ul>`
        : '');
    $('conversationList').innerHTML = group('today', 'Today', today) + group('earlier', 'Earlier', earlier)
        || '<p class="conv-empty">No conversations yet. Ask a question to start one.</p>';
}

function conversationItem(c) {
    const open = state.current?.id === c.id;
    return `<li class="conv-item${open ? ' active' : ''}" data-id="${Number(c.id)}">
            <button type="button" class="conv-open" data-open="${Number(c.id)}"${open ? ' aria-current="page"' : ''}>
                <i class="bi bi-chat-left-text" aria-hidden="true"></i><span>${esc(c.title)}</span>
            </button>
            <button type="button" class="conv-delete" data-delete="${Number(c.id)}" title="Delete"
                    aria-label="Delete “${esc(c.title)}”"><i class="bi bi-trash3" aria-hidden="true"></i></button>
        </li>`;
}

const conversationLi = (id) => $('conversationList').querySelector(`.conv-item[data-id="${Number(id)}"]`);

/** Highlight the open conversation (only classes change, nothing is redrawn) */
function markOpenConversation() {
    $('conversationList').querySelectorAll('.conv-item').forEach((li) => {
        const open = Number(li.dataset.id) === state.current?.id;
        const button = li.querySelector('.conv-open');
        li.classList.toggle('active', open);
        if (open) button.setAttribute('aria-current', 'page');
        else button.removeAttribute('aria-current');
    });
}

/** A conversation got a new answer (or was just created): move its item to the top of "Today", with its new title */
function moveConversationToTop(c) {
    const today = $('conversationList').querySelector('[data-group="today"]');
    if (!today) { // no "Today" group yet: draw the list once
        renderConversationList();
        return;
    }
    const li = conversationLi(c.id);
    if (!li) {
        today.insertAdjacentHTML('afterbegin', conversationItem(c));
        return;
    }
    li.querySelector('.conv-open span').textContent = c.title;
    li.querySelector('.conv-delete').setAttribute('aria-label', `Delete “${c.title}”`);
    const from = li.parentElement;
    today.prepend(li);
    removeGroupIfEmpty(from);
}

function removeGroupIfEmpty(list) {
    if (list.children.length) return;
    list.previousElementSibling?.remove(); // the "Today" / "Earlier" heading
    list.remove();
}

$('conversationList').addEventListener('click', async (e) => {
    const del = e.target.closest('[data-delete]');
    if (del) {
        const id = Number(del.dataset.delete);
        if (!confirm('Delete this conversation?')) return;
        try { await call(() => Api.deleteConversation(id), 'Conversation deleted'); } catch { return; }
        state.conversations = state.conversations.filter((c) => c.id !== id); // update locally, no re-fetch
        const li = conversationLi(id);
        if (li) {
            const from = li.parentElement;
            li.remove();
            removeGroupIfEmpty(from);
        }
        if (!state.conversations.length) renderConversationList(); // shows "No conversations yet"
        if (state.current?.id === id) startNewChat();
        return;
    }
    const open = e.target.closest('[data-open]');
    if (open) openConversation(Number(open.dataset.open));
});

$('newChatBtn').addEventListener('click', () => {
    startNewChat();
    closeSidebar();
    focusComposer(true);
});

function closeSidebar() {
    bootstrap.Offcanvas.getInstance($('sidebar'))?.hide();
}

/** Focus the message box. On phones only when asked, so the keyboard does not pop up by itself. */
function focusComposer(force = false) {
    if (force || isDesktop()) $('messageInput').focus();
}

/** "New chat" only clears the screen. The conversation is created on the first question (no empty chats). */
function startNewChat() {
    state.current = null;
    markOpenConversation();
    renderHeader();
    renderEmptyState();
}

async function openConversation(id) {
    state.current = state.conversations.find((c) => c.id === id) || { id, title: 'Conversation' };
    markOpenConversation();
    renderHeader();
    closeSidebar();
    thread().innerHTML = '<p class="thread-note">Loading messages…</p>';
    let messages = null;
    try { messages = await call(() => Api.messages(id)); } catch { /* shown by call() */ }
    if (state.current?.id !== id) return; // another chat was opened meanwhile
    if (!messages) {
        thread().innerHTML = '<p class="thread-note">Could not load this conversation. Open it again to retry.</p>';
        return;
    }
    thread().innerHTML = '';
    if (messages.length === 0) renderEmptyState();
    messages.forEach((m) => (m.role === 'user' ? appendUser(m.content) : appendAnswer(m)));
    scrollToBottom();
    focusComposer();
}

function renderHeader() {
    $('chatTitle').textContent = state.current ? state.current.title : 'New chat';
}

/** After an answer: update the title and order locally instead of re-fetching the list (saves a request) */
function touchConversation(id, text) {
    const c = state.conversations.find((x) => x.id === id);
    if (!c) return;
    if (c.title === 'New chat') c.title = text.length > 60 ? `${text.slice(0, 60)}…` : text; // same rule as the server
    c.updatedAt = new Date().toISOString();
    state.conversations = [c, ...state.conversations.filter((x) => x !== c)];
    moveConversationToTop(c);
    if (state.current?.id === id) renderHeader();
}

// ---------- empty state ----------
function renderEmptyState() {
    const active = activeProvider();
    thread().innerHTML = `<section class="empty" aria-labelledby="emptyTitle">
            <h2 id="emptyTitle" class="empty-title">Ask a question</h2>
            <p class="empty-lead"><b id="emptyProvider">${esc(active ? active.name : 'The active AI')}</b> answers.
                With documents on, it cites the passages it used, like <span class="cite cite-static">1</span>.</p>
            <div class="suggestions">${SUGGESTIONS.map((s, i) => `
                <button type="button" class="suggestion" data-suggestion="${i}">
                    <span class="suggestion-from">${esc(s.from)}</span>
                    <span class="suggestion-q">${esc(s.q)}</span>
                </button>`).join('')}
            </div>
        </section>`;
}

/** Keep the AI name in the empty state up to date when the providers load or change */
function updateEmptyProvider() {
    const el = $('emptyProvider');
    if (el) el.textContent = activeProvider()?.name || 'The active AI';
}

// ---------- document scope (requirement 5: choose which documents to search) ----------
function loadScope() {
    try {
        const saved = JSON.parse(store.get('docScope'));
        if (saved && ['all', 'none', 'choose'].includes(saved.mode)) {
            return { mode: saved.mode, ids: Array.isArray(saved.ids) ? saved.ids.filter(Number.isInteger) : [] };
        }
    } catch { /* broken value: use the default */ }
    return { mode: 'all', ids: [] };
}

function setScope(mode, ids = state.scope.ids) {
    state.scope = { mode, ids };
    store.set('docScope', JSON.stringify(state.scope));
    renderScope();
}

/** What the next question searches. "Choose" with nothing ticked means no documents. */
function effectiveScope() {
    const { mode, ids } = state.scope;
    if (mode === 'all') return { useDocuments: true, documentIds: null, label: 'All documents' };
    if (mode === 'choose' && ids.length) return { useDocuments: true, documentIds: [...ids], label: plural(ids.length, 'document') };
    return { useDocuments: false, documentIds: null, label: 'No documents' };
}

/** Forget documents that no longer exist (called when the document list loads) */
function pruneScope() {
    const existing = new Set(state.documents.map((d) => d.id));
    const ids = state.scope.ids.filter((id) => existing.has(id));
    if (ids.length !== state.scope.ids.length) setScope(state.scope.mode, ids);
}

/** Button labels, marker colour and the radio buttons */
function renderScope() {
    const scope = effectiveScope();
    $('scopeLabel').textContent = scope.label;
    $('scopeSummaryLabel').textContent = scope.label;
    $('scopeBtn').classList.toggle('is-on', scope.useDocuments);
    $('scopeSummary').classList.toggle('is-on', scope.useDocuments);
    const names = (scope.documentIds || []).map((id) => state.documents.find((d) => d.id === id)?.name).filter(Boolean);
    $('scopeBtn').title = names.length ? names.join(', ') : scope.label;
    document.querySelectorAll('input[name="scopeMode"]').forEach((radio) => { radio.checked = radio.value === state.scope.mode; });
    $('scopeAllCount').textContent = state.documentsLoaded ? ` (${state.documents.length})` : '';
    $('scopeDocs').hidden = state.scope.mode !== 'choose';
}

/** The checkbox list under "Choose documents…". Re-drawn only when the documents change, so focus is kept. */
function renderScopeList() {
    $('scopeDocs').innerHTML = state.documents.map((d) => `
        <label class="scope-doc">
            <input class="form-check-input" type="checkbox" value="${Number(d.id)}"${state.scope.ids.includes(d.id) ? ' checked' : ''}>
            <span>${esc(d.name)}</span>
        </label>`).join('') || '<p class="scope-empty">No documents yet. Add some on the Documents tab.</p>';
}

$('scopeMenu').addEventListener('change', (e) => {
    if (e.target.name === 'scopeMode') {
        setScope(e.target.value);
    } else if (e.target.type === 'checkbox') {
        const ids = [...$('scopeDocs').querySelectorAll('input:checked')].map((input) => Number(input.value));
        setScope('choose', ids);
    }
});

// the summary in the chat header opens the same picker
$('scopeSummary').addEventListener('click', (e) => {
    e.stopPropagation(); // otherwise Bootstrap's "click outside" closes the menu at once
    bootstrap.Dropdown.getOrCreateInstance($('scopeBtn')).show();
});

/** "Ask about this" (Documents tab and source cards): search only that document */
function askAboutDocument(id) {
    const doc = state.documents.find((d) => d.id === id);
    if (state.documentsLoaded && !doc) { // e.g. a source card of an old answer
        toast('That document has been deleted. Choose other documents in the chat box.', 'warning');
        return;
    }
    setScope('choose', [id]);
    renderScopeList();
    bootstrap.Tab.getOrCreateInstance($('chatTabBtn')).show();
    focusComposer(true);
    toast(`The chat now searches only “${doc ? doc.name : 'this document'}”.`);
}

// ---------- composer ----------
function autoGrow() {
    const el = $('messageInput');
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 200)}px`;
}

function updateSendButton() {
    $('sendBtn').disabled = state.sending || !$('messageInput').value.trim();
}

$('messageInput').addEventListener('input', () => {
    autoGrow();
    updateSendButton();
});

$('messageInput').addEventListener('keydown', (e) => {
    // Enter sends, Shift+Enter adds a line. Not while typing Vietnamese with an input method (isComposing),
    // and not on touch screens, where Enter adds a line and the Send button sends.
    const touch = window.matchMedia('(pointer: coarse)').matches;
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing && !touch) {
        e.preventDefault();
        submitComposer();
    }
});

$('sendForm').addEventListener('submit', (e) => {
    e.preventDefault();
    submitComposer();
});

async function submitComposer() {
    const box = $('messageInput');
    const text = box.value.trim();
    if (!text || state.sending) return;
    box.value = '';
    autoGrow();
    const started = await sendMessage(text);
    if (!started && !box.value) { // the chat could not even be created: give the text back
        box.value = text;
        autoGrow();
    }
    updateSendButton();
}

/** Suggestion buttons send right away; document questions turn documents on if they were off */
function useSuggestion(s) {
    if (s.docs && !effectiveScope().useDocuments) setScope('all');
    sendMessage(s.q);
}

function thinkingLabel(body) {
    const p = body.providerId ? state.providers.find((x) => x.id === body.providerId) : activeProvider();
    const ai = p ? p.name : 'the AI';
    if (!body.useDocuments) return `Asking ${ai}`;
    return `Searching ${body.documentIds ? plural(body.documentIds.length, 'document') : 'all documents'}, then asking ${ai}`;
}

/**
 * Ask the AI and show the answer.
 * retry = { body, userRow, errorRow } when the user clicks "Retry" or "Ask <platform>" on an error bubble:
 * the server saved nothing for the failed try, so sending the same body again never makes duplicates.
 * Returns false when nothing could be sent (the conversation could not be created).
 */
async function sendMessage(text, retry = null, providerId = null) {
    if (state.sending) {
        toast('Wait for the current answer first.', 'warning');
        return true;
    }
    state.sending = true;
    updateSendButton();

    // 1. The request body: same text and documents on a retry, only the platform may change
    const scope = effectiveScope();
    const body = retry ? { ...retry.body } : { content: text, useDocuments: scope.useDocuments, documentIds: scope.documentIds };
    delete body.providerId;
    if (providerId) body.providerId = providerId;
    body.autoFallback = state.autoFallback;

    // 2. Show the question and the thinking timer at once, before any request:
    //    on a sleepy free server even creating the chat can take a few seconds.
    thread().querySelector('.empty')?.remove();
    let userRow = retry ? retry.userRow : appendUser(text);
    retry?.errorRow.classList.add('is-retrying');
    const thinking = showThinking(thinkingLabel(body));
    try {
        // 3. A new chat is created on its first question (POST {})
        if (!state.current) {
            let created;
            try {
                created = await call(() => Api.createConversation());
            } catch {
                thinking.stop();
                userRow.remove();
                renderEmptyState();
                return false; // submitComposer puts the text back in the box
            }
            // "now" in this browser's time zone, so it lands under "Today"
            state.current = { ...created, updatedAt: new Date().toISOString() };
            state.conversations.unshift(state.current);
            moveConversationToTop(state.current);
            renderHeader();
        }
        const conversationId = state.current.id;

        // 4. Wait for the answer (free AI platforms can take a while)
        let answer;
        try {
            answer = await Api.send(conversationId, body);
        } catch (e) {
            thinking.stop();
            if (e.status === 401) askPassword(true);
            if (e.status === 0) setServerStatus('offline');
            if (state.current?.id !== conversationId) { // the user opened another chat meanwhile
                toast(`Your question was not sent. ${errorTitle(e)}`, 'danger');
                return true;
            }
            retry?.errorRow.remove();
            if (!userRow.isConnected) userRow = appendUser(text); // this chat was re-opened while we waited
            markNotSent(userRow);
            const errorRow = appendError(e, (pid) => sendMessage(text, { body, userRow, errorRow }, pid));
            return true;
        }
        thinking.stop();
        setServerStatus('online');
        touchConversation(conversationId, text);
        if (answer.notice) loadProviders(); // once, to show which platform is resting now
        if (state.current?.id !== conversationId) return true; // the user opened another chat meanwhile

        retry?.errorRow.remove();
        if (userRow.isConnected) markSent(userRow);
        else appendUser(text); // this chat was re-opened while we waited: its list did not have this question yet
        revealAnswer(appendAnswer(answer, true));
        return true;
    } finally {
        state.sending = false;
        updateSendButton();
    }
}

// clicks inside the message list: citation chips, "Ask about this document", suggestions
$('thread').addEventListener('click', (e) => {
    const chip = e.target.closest('button.cite');
    if (chip) {
        openSource(chip);
        return;
    }
    const ask = e.target.closest('[data-ask-doc]');
    if (ask) {
        askAboutDocument(Number(ask.dataset.askDoc));
        return;
    }
    const suggestion = e.target.closest('[data-suggestion]');
    if (suggestion) useSuggestion(SUGGESTIONS[Number(suggestion.dataset.suggestion)]);
});
