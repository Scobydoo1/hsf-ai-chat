// Small wrapper around fetch() for the Spring Boot REST API.
class ApiError extends Error {
    constructor(status, message, data = {}) {
        super(message);
        this.status = status;                   // HTTP status; 0 = no answer (offline, or timed out)
        this.code = data.code || null;          // 502 only: RATE_LIMIT | UNAVAILABLE | AUTH | NOT_FOUND | AI_ERROR
        this.provider = data.provider || null;  // 502 only: the platform `code` is about = the one the user chose (tried first)
        this.timeout = !!data.timeout;
    }
}

const Api = (() => {
    const base = window.APP_CONFIG.API_BASE_URL.replace(/\/$/, '');

    function getPassword() {
        try { return localStorage.getItem('appPassword') || ''; } catch { return ''; }
    }

    function setPassword(value) {
        try { localStorage.setItem('appPassword', value); } catch { /* private mode: ask again next time */ }
    }

    /** timeoutMs: give up after this long (0 = wait as long as the browser allows) */
    async function request(method, path, body, timeoutMs = 0) {
        const headers = {};
        const password = getPassword();
        if (password) headers['X-App-Password'] = password;

        let payload;
        if (body instanceof FormData) {
            payload = body; // the browser sets the multipart Content-Type itself
        } else if (body !== undefined) {
            headers['Content-Type'] = 'application/json';
            payload = JSON.stringify(body);
        }

        const controller = new AbortController();
        const timer = timeoutMs ? setTimeout(() => controller.abort(), timeoutMs) : null;
        let res;
        let text;
        try {
            res = await fetch(base + path, { method, headers, body: payload, signal: controller.signal });
            text = await res.text();
        } catch {
            if (controller.signal.aborted) {
                throw new ApiError(0, 'The server did not answer in time.', { timeout: true });
            }
            throw new ApiError(0, `Cannot reach the server at ${base}. Check your connection, then try again.`);
        } finally {
            clearTimeout(timer);
        }

        let data = null;
        try { data = text ? JSON.parse(text) : null; } catch { data = text; }
        if (!res.ok) {
            const info = data && typeof data === 'object' ? data : {};
            throw new ApiError(res.status, info.error || `HTTP ${res.status}`, info);
        }
        return data;
    }

    return {
        base,
        getPassword,
        setPassword,
        status: (timeoutMs) => request('GET', '/api/status', undefined, timeoutMs),

        providers: () => request('GET', '/api/providers'),
        createProvider: (p) => request('POST', '/api/providers', p),
        updateProvider: (id, p) => request('PUT', `/api/providers/${id}`, p),
        deleteProvider: (id) => request('DELETE', `/api/providers/${id}`),
        activateProvider: (id) => request('POST', `/api/providers/${id}/activate`),
        testProvider: (id) => request('POST', `/api/providers/${id}/test`),

        conversations: () => request('GET', '/api/conversations'),
        createConversation: () => request('POST', '/api/conversations', {}),
        deleteConversation: (id) => request('DELETE', `/api/conversations/${id}`),
        messages: (id) => request('GET', `/api/conversations/${id}/messages`),
        /** body: { content, useDocuments, documentIds, providerId, autoFallback } */
        send: (id, body) => request('POST', `/api/conversations/${id}/messages`, body),

        documents: () => request('GET', '/api/documents'),
        uploadDocument: (file) => { const f = new FormData(); f.append('file', file); return request('POST', '/api/documents', f); },
        addTextDocument: (name, content) => request('POST', '/api/documents/text', { name, content }),
        deleteDocument: (id) => request('DELETE', `/api/documents/${id}`),
        reindex: () => request('POST', '/api/documents/reindex'),
    };
})();
