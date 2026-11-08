// Documents tab: upload or paste files, see how each one is searched, and follow the background re-index.

const MAX_UPLOAD_BYTES = 10 * 1024 * 1024; // same limit as the server (10 MB)

let documentsLoadedAt = 0;   // when the list was last fetched (see REFRESH_AFTER_MS)
let reindexRequest = false;  // our own "Re-index all" request is running

/** withStatus: also refresh /api/status (embedding platform, re-index running?) in the same round trip */
async function loadDocuments(withStatus = false) {
    try {
        const [docs, status] = await Promise.all([
            call(() => Api.documents()),
            withStatus ? call(() => Api.status()) : Promise.resolve(state.status),
        ]);
        state.documents = docs;
        state.documentsLoaded = true;
        state.status = status;
        documentsLoadedAt = Date.now();
        pruneScope();
        renderScopeList();
        renderScope();
        renderDocuments();
    } catch { /* call() already showed the error */ }
}

function renderDocuments() {
    renderEmbeddingInfo();
    renderReindexButton();
    $('reindexNote').hidden = !state.status?.reindexing;
    $('docCount').textContent = state.documentsLoaded ? `(${state.documents.length})` : '';
    if (!state.documentsLoaded) {
        $('documentGrid').innerHTML = '<p class="empty-note">Loading documents…</p>';
        return;
    }
    $('documentGrid').innerHTML = state.documents.map(documentCard).join('')
        || '<p class="empty-note">No documents yet. Upload a file or paste text above.</p>';
}

function renderEmbeddingInfo() {
    const s = state.status;
    if (!s) {
        $('embeddingInfo').textContent = '';
        return;
    }
    $('embeddingInfo').innerHTML = s.embeddingProvider
        ? `<i class="bi bi-info-circle" aria-hidden="true"></i><span>New documents are embedded with <b>${esc(s.embeddingProvider)}</b>
           for vector search. Documents embedded with another model are re-indexed automatically in the background.</span>`
        : `<i class="bi bi-info-circle" aria-hidden="true"></i><span>No embedding platform is set, so the document search uses keywords.
           In <b>Settings</b>, tick “Use for embeddings” on a platform that has an embedding model.</span>`;
}

/** vector = green marker, outdated = pink, keyword = grey */
function searchModeTag(d) {
    const mode = d.searchMode || (d.embeddingModel ? 'vector' : 'keyword'); // older back-end without searchMode
    const model = d.embeddingModel ? `Embedded with ${d.embeddingModel}` : 'No vectors';
    if (mode === 'vector') return `<span class="tag tag-green" title="${esc(model)}">Vector search</span>`;
    if (mode === 'outdated') {
        return state.status?.reindexing
            ? '<span class="tag tag-pink">Updating…</span>'
            : `<span class="tag tag-pink" title="${esc(model)}, an older model">Outdated – re-index</span>`;
    }
    return `<span class="tag" title="${esc(model)}">Keyword search</span>`;
}

function documentCard(d) {
    return `<article class="doc-card">
            <div class="doc-top">
                <i class="bi bi-file-earmark-text doc-icon" aria-hidden="true"></i>
                ${searchModeTag(d)}
            </div>
            <h3 class="doc-name">${esc(d.name)}</h3>
            <p class="doc-stats">${Number(d.charCount).toLocaleString('en-US')} characters · ${plural(Number(d.chunkCount), 'part')}</p>
            <div class="doc-actions">
                <button type="button" class="btn btn-sm btn-outline-secondary" data-ask-doc="${Number(d.id)}">
                    <i class="bi bi-chat-left-text" aria-hidden="true"></i> Ask about this</button>
                <button type="button" class="icon-btn danger" data-delete-doc="${Number(d.id)}" title="Delete"
                        aria-label="Delete ${esc(d.name)}"><i class="bi bi-trash3" aria-hidden="true"></i></button>
            </div>
        </article>`;
}

$('documentGrid').addEventListener('click', async (e) => {
    const ask = e.target.closest('[data-ask-doc]');
    if (ask) {
        askAboutDocument(Number(ask.dataset.askDoc));
        return;
    }
    const del = e.target.closest('[data-delete-doc]');
    if (!del || !confirm('Delete this document?')) return;
    const id = Number(del.dataset.deleteDoc);
    try { await call(() => Api.deleteDocument(id), 'Document deleted'); } catch { return; }
    state.documents = state.documents.filter((d) => d.id !== id); // update locally, no re-fetch
    pruneScope();
    renderScopeList();
    renderScope();
    renderDocuments();
});

// ---------- add documents: drop zone (drag & drop or click) and pasted text ----------

/** The server answers with the new document, so we add it to the list ourselves (no re-fetch) */
function documentAdded(res) {
    if (res.warning) toast(res.warning, 'warning');
    else toast(`Added “${res.document.name}” (${plural(res.document.chunkCount, 'part')})`);
    if (!state.documentsLoaded) { // the list never loaded: fetch it now
        loadDocuments();
        return;
    }
    state.documents.unshift(res.document); // the server lists the newest first
    renderScopeList();
    renderScope();
    renderDocuments();
}

async function uploadFile(file) {
    if (!file) return;
    if (file.size > MAX_UPLOAD_BYTES) { // check here: no slow upload to a free server just to hear "too large"
        toast(`“${file.name}” is larger than 10 MB. Choose a smaller file.`, 'danger');
        return;
    }
    $('dropZone').classList.add('is-busy');
    $('dropTitle').textContent = `Uploading ${file.name}…`;
    try {
        documentAdded(await call(() => Api.uploadDocument(file)));
    } catch { /* shown by call() */ } finally {
        $('dropZone').classList.remove('is-busy');
        $('dropTitle').textContent = 'Drop a file here, or click to choose';
        $('fileInput').value = '';
    }
}

$('fileInput').addEventListener('change', (e) => uploadFile(e.target.files[0]));

['dragenter', 'dragover'].forEach((type) => $('dropZone').addEventListener(type, (e) => {
    e.preventDefault(); // allows dropping
    $('dropZone').classList.add('is-dragging');
}));
['dragleave', 'drop'].forEach((type) => $('dropZone').addEventListener(type, () => $('dropZone').classList.remove('is-dragging')));
$('dropZone').addEventListener('drop', (e) => {
    e.preventDefault();
    uploadFile(e.dataTransfer.files[0]);
});

// A file dropped anywhere else would make the browser leave the app to open it: block that outside the drop zone
['dragover', 'drop'].forEach((type) => window.addEventListener(type, (e) => {
    if (!e.dataTransfer?.types.includes('Files') || e.target.closest?.('#dropZone')) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = 'none'; // shows the "not allowed" cursor
}));

$('textForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const btn = e.submitter;
    btn.disabled = true;
    try {
        documentAdded(await call(() => Api.addTextDocument($('textName').value, $('textContent').value)));
        $('textForm').reset();
    } catch { /* shown by call() */ } finally {
        btn.disabled = false;
    }
});

// ---------- re-index ----------

/** Disabled while our request runs, and while the server re-indexes in the background (it would say no) */
function renderReindexButton() {
    const btn = $('reindexBtn');
    const background = !!state.status?.reindexing && !reindexRequest;
    btn.disabled = reindexRequest || background;
    btn.title = background ? 'Wait until the background update has finished' : '';
    btn.innerHTML = reindexRequest
        ? '<span class="spinner-border spinner-border-sm" aria-hidden="true"></span> Re-indexing…'
        : '<i class="bi bi-arrow-repeat" aria-hidden="true"></i> Re-index all';
}

$('reindexBtn').addEventListener('click', async () => {
    reindexRequest = true;
    renderReindexButton();
    let r = null;
    try { r = await call(() => Api.reindex()); } catch { /* call() showed why */ }
    reindexRequest = false;
    if (r) {
        toast(`Re-indexed ${plural(r.documents, 'document')} (${plural(r.chunks, 'part')}) with ${r.embeddingModel}`);
        await loadDocuments(true); // the new search modes (this also redraws the button)
    } else {
        await refreshStatus(); // the server says no while its background re-index runs: follow that one instead
    }
});

let reindexWatch = null;

/** The server re-indexes outdated documents in the background: check /api/status every 5 s (max 3 min) */
function watchReindex() {
    if (reindexWatch) return;
    const started = Date.now();
    renderDocuments();
    reindexWatch = setInterval(async () => {
        try { state.status = await Api.status(); } catch { /* try again in 5 s */ }
        const timedOut = Date.now() - started > 3 * 60 * 1000;
        if (!state.status?.reindexing || timedOut) {
            clearInterval(reindexWatch);
            reindexWatch = null;
            loadDocuments();
        }
    }, 5000);
}

/** After saving a platform with "Use for embeddings" (or a refused "Re-index all"): is a background re-index running? */
async function refreshStatus() {
    try { state.status = await Api.status(); } catch { /* keep the last status */ }
    renderDocuments();
    if (state.status?.reindexing) watchReindex();
}

$('docsTabBtn').addEventListener('shown.bs.tab', async () => {
    renderDocuments(); // show what we already have at once
    if (Date.now() - documentsLoadedAt > REFRESH_AFTER_MS) await loadDocuments(true); // then refresh documents + status
    if (state.status?.reindexing) watchReindex();
});
