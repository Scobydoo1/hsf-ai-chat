// Shared state and small helpers used by every other script (plain JavaScript, no build step).
// Scripts load in this order: api.js, ui.js, messages.js, chat.js, documents.js, settings.js, app.js.

/** Everything the page knows, in one place */
const state = {
    status: null,            // last GET /api/status
    providers: [],           // AI platforms (GET /api/providers)
    conversations: [],       // newest first (GET /api/conversations)
    documents: [],           // GET /api/documents
    documentsLoaded: false,
    current: null,           // the open conversation; null = a new chat, created on the first question
    sending: false,          // one question at a time
    scope: { mode: 'all', ids: [] },  // documents to search: "all", "none" or "choose" (ids)
    autoFallback: true,      // switch platform automatically when one hits its limit
};

const $ = (id) => document.getElementById(id);

// ---------- helpers ----------
const ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

/** Escape text before it goes into innerHTML or an attribute. ALL dynamic text goes through this. */
function esc(text) {
    return String(text ?? '').replace(/[&<>"']/g, (ch) => ESCAPES[ch]);
}

/** "1 document", "3 documents" */
function plural(n, word) {
    return `${n} ${word}${n === 1 ? '' : 's'}`;
}

/** localStorage can throw (private mode, blocked storage): never let that break the page */
const store = {
    get(key) { try { return localStorage.getItem(key); } catch { return null; } },
    set(key, value) { try { localStorage.setItem(key, value); } catch { /* not saved: fine */ } },
};

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Opening the Documents or Settings tab re-fetches its list only when it is older than this
 *  (every request wakes the free server and database, so we do not repeat them on each click) */
const REFRESH_AFTER_MS = 60 * 1000;
const prefersReducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches;
const isDesktop = () => window.matchMedia('(min-width: 768px)').matches;

// ---------- toast ----------
const TOAST_ICONS = { success: 'bi-check2-circle', warning: 'bi-exclamation-circle', danger: 'bi-x-circle' };

function toast(message, type = 'success') {
    const el = $('toast');
    el.className = `toast is-${type}`;
    $('toastIcon').className = `bi ${TOAST_ICONS[type] || TOAST_ICONS.success}`;
    $('toastBody').textContent = message;
    bootstrap.Toast.getInstance(el)?.dispose(); // a new instance, so each toast gets its own delay
    new bootstrap.Toast(el, { delay: type === 'danger' ? 8000 : 3500 }).show();
}

/** Run an API call; show errors; ask for the password on 401. Re-throws, so the caller can stop. */
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
        loadAll(); // app.js
    } catch (err) {
        $('passwordError').classList.remove('d-none');
    }
});

// ---------- theme: light / dark / auto (follows the computer), saved in localStorage ----------
const darkQuery = window.matchMedia('(prefers-color-scheme: dark)');
const THEME_ICONS = { light: 'bi-sun', dark: 'bi-moon-stars', auto: 'bi-circle-half' };

function applyTheme() {
    const saved = store.get('theme');
    const choice = THEME_ICONS[saved] ? saved : 'auto';
    const dark = choice === 'dark' || (choice === 'auto' && darkQuery.matches);
    document.documentElement.setAttribute('data-bs-theme', dark ? 'dark' : 'light');
    $('themeIcon').className = `bi ${THEME_ICONS[choice]}`;
    $('themeBtn').setAttribute('aria-label', `Theme: ${choice}`);
    document.querySelectorAll('[data-theme]').forEach((btn) => {
        btn.classList.toggle('is-current', btn.dataset.theme === choice);
        btn.setAttribute('aria-pressed', String(btn.dataset.theme === choice));
    });
}

darkQuery.addEventListener('change', applyTheme);
document.querySelectorAll('[data-theme]').forEach((btn) => btn.addEventListener('click', () => {
    store.set('theme', btn.dataset.theme);
    applyTheme();
}));

// ---------- dropdown menus keep 16 px away from the screen edges (phones) ----------
document.querySelectorAll('[data-bs-toggle="dropdown"]').forEach((toggle) => {
    bootstrap.Dropdown.getOrCreateInstance(toggle, {
        // Popper merges this with Bootstrap's own "preventOverflow" settings
        popperConfig: (config) => ({
            ...config,
            modifiers: [...config.modifiers, { name: 'preventOverflow', options: { padding: 16 } }],
        }),
    });
});
