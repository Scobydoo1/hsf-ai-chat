// Message bubbles: the user's questions, the AI's answers (Markdown + highlighter citations + source cards),
// the "thinking" bubble while we wait, and the error bubble with Retry / Ask <platform> buttons.

const thread = () => $('thread');

function scrollToBottom() {
    const box = $('messages');
    box.scrollTop = box.scrollHeight;
}

/** A new answer: scroll down to it, but when it is taller than the screen keep its first line in view */
function revealAnswer(row) {
    const box = $('messages');
    if (row.offsetHeight < box.clientHeight - 32) {
        scrollToBottom();
        return;
    }
    box.scrollTop += row.getBoundingClientRect().top - box.getBoundingClientRect().top - 16;
}

// ---------- user bubble ----------
function appendUser(text) {
    const row = document.createElement('div');
    row.className = 'msg user';
    row.innerHTML = `<div class="bubble">${esc(text)}</div>`;
    thread().append(row);
    scrollToBottom();
    return row;
}

/** The server saved nothing, so the question is marked "Not sent" until a retry works */
function markNotSent(row) {
    if (!row.querySelector('.msg-flag')) row.insertAdjacentHTML('beforeend', '<p class="msg-flag">Not sent</p>');
}

function markSent(row) {
    row.querySelector('.msg-flag')?.remove();
}

// ---------- answers ----------

/**
 * What an answer may NOT contain, on top of DOMPurify's defaults (no scripts, no event handlers):
 * - no <style>, inline styles or class names: an answer could restyle itself into a full-page overlay
 *   (e.g. class="modal-backdrop show") that swallows every click;
 * - no id / name: an element with id="messageInput" would hijack our $('messageInput') look-ups;
 * - no data-* attributes: our click handlers look for them, so a fake one could trigger them;
 * - no form controls, images or SVG: nothing interactive, and no requests to other servers
 *   (an image could track readers, and it costs loading time).
 * Plain Markdown output (headings, lists, tables, code, links) is untouched.
 */
const PURIFY_OPTIONS = {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['style', 'form', 'input', 'button', 'textarea', 'select', 'img'],
    FORBID_ATTR: ['style', 'id', 'name', 'class'],
    ALLOW_DATA_ATTR: false,
};

/** Markdown -> safe DOM. Never trust HTML written by an AI (or by a document it quotes). */
function renderMarkdown(text) {
    const div = document.createElement('div');
    div.className = 'markdown';
    div.innerHTML = DOMPurify.sanitize(marked.parse(text ?? ''), PURIFY_OPTIONS);
    div.querySelectorAll('a[href]').forEach((a) => {
        a.target = '_blank';
        a.rel = 'noopener noreferrer';
    });
    return div;
}

const CITATION = /\[(\d{1,2}(?:\s*,\s*\d{1,2})*)\]/g; // [1] or [1, 2]

/**
 * The signature element: "[1]" and "[1, 2]" become yellow highlighter chips that open source card n.
 * We walk the TEXT NODES of the already-sanitized DOM (never a regex on an HTML string),
 * and skip code blocks and links. Returns how many chips were made.
 */
function addCitationChips(root, sources) {
    if (!sources.length) return 0;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
        acceptNode: (node) => (node.parentElement.closest('code, pre, a') ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT),
    });
    const textNodes = [];
    while (walker.nextNode()) textNodes.push(walker.currentNode); // collect first: we change the DOM below

    let chips = 0;
    for (const node of textNodes) {
        const text = node.nodeValue;
        const parts = document.createDocumentFragment();
        let last = 0;
        for (const match of text.matchAll(CITATION)) {
            const numbers = match[1].split(',').map((n) => Number(n.trim()));
            if (!numbers.every((n) => n >= 1 && n <= sources.length)) continue; // not one of our sources: keep the text
            parts.append(text.slice(last, match.index));
            numbers.forEach((n) => {
                const chip = document.createElement('button');
                chip.type = 'button';
                chip.className = 'cite';
                chip.dataset.cite = n;
                chip.textContent = n;
                chip.title = `${sources[n - 1].documentName}, part ${sources[n - 1].chunkIndex + 1}`;
                chip.setAttribute('aria-label', `Source ${n}: ${sources[n - 1].documentName}`);
                chip.style.setProperty('--i', chips++); // staggers the marker-stroke animation
                parts.append(chip);
            });
            last = match.index + match[0].length;
        }
        if (last === 0) continue; // no citation in this text node
        parts.append(text.slice(last));
        node.replaceWith(parts);
    }
    return chips;
}

/** The sources, shown as "index cards" under the answer */
function sourcesHtml(sources) {
    const cards = sources.map((s, i) => `
        <li class="source-card" data-source="${i + 1}" tabindex="-1">
            <div class="source-head">
                <span class="cite cite-static">${i + 1}</span>
                <span class="source-name">${esc(s.documentName)}</span>
            </div>
            <p class="source-meta">part ${Number(s.chunkIndex) + 1} · ${esc(s.method)} ${Number(s.score).toFixed(3)}</p>
            <p class="snippet"><mark>${esc(s.snippet)}</mark></p>
            ${s.documentId != null
                ? `<button type="button" class="link-btn" data-ask-doc="${Number(s.documentId)}">Ask about this document</button>`
                : ''}
        </li>`).join('');
    return `<details class="sources">
            <summary><i class="bi bi-journal-text" aria-hidden="true"></i> Sources <span class="count">${sources.length}</span>
                <i class="bi bi-chevron-down caret" aria-hidden="true"></i></summary>
            <ol class="source-cards">${cards}</ol>
        </details>`;
}

/** "Groq · qwen/qwen3.8-27b · 2.4 s · searched 2 docs" (on a narrow screen it wraps only at the dots) */
function answerMeta(m) {
    return [
        m.provider,
        m.model,
        m.durationMs != null ? `${(m.durationMs / 1000).toFixed(1)} s` : null,
        m.documentsSearched != null ? `searched ${plural(m.documentsSearched, 'doc')}` : null,
    ].filter(Boolean).map((part) => part.replaceAll(' ', ' ')).join(' · '); //   = a space that never breaks
}

/** Show an assistant message. fresh = it just arrived, so the citation chips get the marker animation. */
function appendAnswer(m, fresh = false) {
    const sources = m.sources || [];
    const row = document.createElement('article');
    row.className = 'msg assistant';
    const bubble = document.createElement('div');
    bubble.className = 'bubble answer';

    const body = renderMarkdown(m.content);
    const chips = addCitationChips(body, sources);
    bubble.append(body);
    if (sources.length) bubble.insertAdjacentHTML('beforeend', sourcesHtml(sources));
    if (m.notice) {
        bubble.insertAdjacentHTML('beforeend', `<p class="notice">
            <i class="bi bi-lightning-charge-fill" aria-hidden="true"></i><span>${esc(m.notice)}</span></p>`);
    }
    bubble.insertAdjacentHTML('beforeend', `<footer class="answer-foot">
            <span class="meta">${esc(answerMeta(m))}</span>
            <button type="button" class="copy-btn"><i class="bi bi-clipboard" aria-hidden="true"></i><span>Copy</span></button>
        </footer>`);
    bubble.querySelector('.copy-btn').addEventListener('click', (e) => copyAnswer(e.currentTarget, m.content));

    if (fresh && chips) row.classList.add('is-new');
    row.append(bubble);
    thread().append(row);
    return row;
}

async function copyAnswer(button, text) {
    try {
        await navigator.clipboard.writeText(text);
    } catch {
        toast('Could not copy. Select the text and press Ctrl+C.', 'warning');
        return;
    }
    button.querySelector('span').textContent = 'Copied';
    button.querySelector('i').className = 'bi bi-check2';
    setTimeout(() => {
        button.querySelector('span').textContent = 'Copy';
        button.querySelector('i').className = 'bi bi-clipboard';
    }, 1600);
}

/** Clicking a citation chip opens the sources and flashes card n */
function openSource(chip) {
    const details = chip.closest('.answer')?.querySelector('.sources');
    const card = details?.querySelector(`[data-source="${Number(chip.dataset.cite)}"]`);
    if (!card) return;
    details.open = true;
    card.scrollIntoView({ block: 'nearest', behavior: prefersReducedMotion() ? 'auto' : 'smooth' });
    details.querySelectorAll('.flash').forEach((c) => c.classList.remove('flash'));
    void card.offsetWidth; // restart the CSS animation
    card.classList.add('flash');
    card.focus({ preventScroll: true });
}

// ---------- waiting for an answer ----------

/** Pending bubble with an elapsed-seconds counter. Returns { stop } */
function showThinking(label) {
    const row = document.createElement('div');
    row.className = 'msg assistant pending';
    row.innerHTML = `<div class="bubble thinking">
            <div class="thinking-line">
                <i class="bi bi-hourglass-split" aria-hidden="true"></i>
                <span>${esc(label)}…</span>
                <span class="elapsed" aria-hidden="true">0 s</span>
            </div>
            <p class="slow-note" hidden></p>
        </div>`;
    thread().append(row);
    scrollToBottom();

    const started = Date.now();
    const elapsed = row.querySelector('.elapsed');
    const note = row.querySelector('.slow-note');
    const timer = setInterval(() => {
        const seconds = Math.floor((Date.now() - started) / 1000);
        elapsed.textContent = `${seconds} s`;
        const text = seconds >= 45 ? 'This is taking longer than usual.'
            : seconds >= 10 ? 'Free servers can be slow — still working.' : '';
        if (text && note.textContent !== text) {
            note.textContent = text;
            note.hidden = false;
        }
    }, 1000);
    return {
        stop() {
            clearInterval(timer);
            row.remove();
        },
    };
}

// ---------- a failed question ----------

/** What went wrong, in plain words. The 502 "code" picks the hint. */
const ERROR_HINTS = {
    RATE_LIMIT: (p) => `${p} hit its free-tier limit.`,
    UNAVAILABLE: (p) => `${p} is not responding.`,
    AUTH: (p) => `${p} rejected the API key – check it in Settings.`,
    NOT_FOUND: (p) => `${p}: model name not found – edit it in Settings.`,
};

function errorTitle(e) {
    if (e.status === 0) return 'Can’t reach the server. Check your connection, then retry.';
    if (e.status === 401) return 'The app password is missing or wrong.';
    if (e.status === 502) {
        const name = e.provider || 'The AI platform';
        return ERROR_HINTS[e.code] ? ERROR_HINTS[e.code](name) : `${name} could not answer.`;
    }
    return 'The server could not answer this question.';
}

/**
 * Error bubble with "Retry" plus "Ask <platform>" for up to 3 other real (non-demo) platforms.
 * onRetry(providerId or null) re-sends the SAME text. Returns the row.
 */
function appendError(e, onRetry) {
    const failed = e.provider || activeProvider()?.name; // the 502 names the platform whose failure "code" describes
    // other platforms that can answer: not the demo, and with a key or a URL (e.g. Ollama), like the server's backups
    const others = e.status === 502
        ? state.providers.filter((p) => p.type !== 'DEMO' && p.name !== failed && (p.hasKey || p.baseUrl)).slice(0, 3)
        : [];
    const title = errorTitle(e);
    const detail = e.status === 0 || e.message === title ? '' : e.message;

    const row = document.createElement('div');
    row.className = 'msg assistant';
    row.innerHTML = `<div class="bubble error-bubble" role="alert">
            <p class="error-title"><i class="bi bi-exclamation-circle" aria-hidden="true"></i><span>${esc(title)}</span></p>
            ${detail ? `<p class="error-detail">${esc(detail)}</p>` : ''}
            <p class="error-help">Your question was not saved. ${others.length ? 'Retry, or ask another platform.' : 'You can retry it.'}</p>
            <div class="error-actions">
                <button type="button" class="btn btn-sm btn-primary" data-retry="">
                    <i class="bi bi-arrow-clockwise" aria-hidden="true"></i> Retry</button>
                ${others.map((p) => `<button type="button" class="btn btn-sm btn-outline-secondary" data-retry="${Number(p.id)}">
                    Ask ${esc(p.name)}</button>`).join('')}
                ${e.code === 'AUTH' || e.code === 'NOT_FOUND'
                    ? '<button type="button" class="btn btn-sm btn-link" data-open-settings>Open Settings</button>' : ''}
            </div>
        </div>`;
    row.querySelectorAll('[data-retry]').forEach((btn) => btn.addEventListener('click', () =>
        onRetry(btn.dataset.retry ? Number(btn.dataset.retry) : null)));
    row.querySelector('[data-open-settings]')?.addEventListener('click', () =>
        bootstrap.Tab.getOrCreateInstance($('settingsTabBtn')).show());
    thread().append(row);
    scrollToBottom();
    return row;
}
