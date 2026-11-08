// Start-up: wake the free server, load everything in parallel, and keep the server awake during a demo.
// (Render's free plan sleeps after 15 minutes without visitors; waking it takes 30-60 seconds.)

const WAKE_BANNER_AFTER_MS = 2500;    // explain the wait when /api/status is slower than this
const WAKE_GIVE_UP_MS = 150000;       // stop retrying after about 2.5 minutes
const STATUS_TIMEOUT_MS = 70000;      // one request may hang while the server starts
const KEEP_AWAKE_MS = 9 * 60 * 1000;  // ping before the 15-minute sleep timer runs out

// ---------- server status: dot in the top bar + banner ----------
const SERVER_LABELS = { connecting: 'Connecting…', waking: 'Waking…', online: 'Online', offline: 'Offline' };

function setServerStatus(mode) {
    $('serverStatus').dataset.state = mode;
    $('serverStatus').title = `Server: ${SERVER_LABELS[mode]}`; // small screens show only the dot
    $('serverStatusLabel').textContent = SERVER_LABELS[mode];
}

/** mode: "waking", "error" or null (hidden) */
function showWakeBanner(mode, message) {
    const banner = $('wakeBanner');
    banner.hidden = !mode;
    banner.classList.toggle('is-error', mode === 'error');
    $('wakeRetry').hidden = mode !== 'error';
    if (mode === 'waking') {
        $('wakeTitle').textContent = 'Waking up the free server…';
        $('wakeBody').textContent = 'The free plan sleeps after 15 minutes without visitors; the first request takes up to a minute.';
    } else if (mode === 'error') {
        $('wakeTitle').textContent = 'Can’t reach the server';
        $('wakeBody').textContent = message
            || 'It did not answer for 2.5 minutes. Check your internet connection, then try again.';
    }
}

/** GET /api/status, retrying with growing pauses while the free server starts (up to ~2.5 minutes) */
async function wakeServer() {
    const started = Date.now();
    let pause = 2000;
    setServerStatus('connecting');
    const slowTimer = setTimeout(() => {
        setServerStatus('waking');
        showWakeBanner('waking');
    }, WAKE_BANNER_AFTER_MS);
    try {
        for (;;) {
            const left = WAKE_GIVE_UP_MS - (Date.now() - started);
            try {
                return await Api.status(Math.max(5000, Math.min(STATUS_TIMEOUT_MS, left)));
            } catch (e) {
                const refused = e.status >= 400 && e.status < 500; // the server is up but said no: retrying will not help
                if (refused || Date.now() - started + pause > WAKE_GIVE_UP_MS) throw e;
                setServerStatus('waking');
                showWakeBanner('waking');
                await sleep(pause);
                pause = Math.min(pause * 2, 15000);
            }
        }
    } finally {
        clearTimeout(slowTimer);
    }
}

async function start() {
    showWakeBanner(null);
    let status;
    try {
        status = await wakeServer();
    } catch (e) {
        setServerStatus('offline');
        showWakeBanner('error', e.status ? e.message : null);
        return;
    }
    showWakeBanner(null);
    setServerStatus('online');
    state.status = status;
    renderDocuments();
    startKeepAwake();
    if (status.passwordRequired && !Api.getPassword()) {
        askPassword(false);
        return;
    }
    await loadAll();
}

/** After the status check: providers, conversations and documents in parallel (one round trip) */
async function loadAll() {
    await Promise.all([loadProviders(), loadConversations(), loadDocuments()]);
    if (state.status?.reindexing) watchReindex();
}

$('wakeRetry').addEventListener('click', start);

// ---------- keep-awake: while the tab is visible, ping /api/status every 9 minutes ----------
let keepAwakeTimer = null;
let lastPing = Date.now();

async function ping() {
    lastPing = Date.now();
    try {
        state.status = await Api.status(STATUS_TIMEOUT_MS);
        setServerStatus('online');
    } catch {
        setServerStatus('offline');
    }
}

function startKeepAwake() {
    if (keepAwakeTimer) return;
    keepAwakeTimer = setInterval(() => {
        if (document.visibilityState === 'visible') ping();
    }, KEEP_AWAKE_MS);
    // back on the tab after a long break: ping at once, so the server is awake before the next question
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible' && Date.now() - lastPing > KEEP_AWAKE_MS) ping();
    });
}

// ---------- first paint (nothing here needs the server) ----------
applyTheme();
state.scope = loadScope();
setAutoFallback(store.get('autoFallback') !== 'false', false); // default ON
fillForm({});
renderScopeList();
renderScope();
renderHeader();
renderEmptyState();
$('conversationList').innerHTML = '<p class="conv-empty">Loading conversations…</p>';
renderDocuments();
updateSendButton();
start();
