// Small wrapper around fetch() for the Spring Boot REST API.
class ApiError extends Error {
    constructor(status, message) {
        super(message);
        this.status = status;
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

    async function request(method, path, body) {
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

        let res;
        try {
            res = await fetch(base + path, { method, headers, body: payload });
        } catch {
            throw new ApiError(0, `Cannot reach the back-end at ${base}. Is it running? ` +
                '(On Render’s free plan the first request after a break can take about a minute.)');
        }
        if (res.status === 204) return null;
        const text = await res.text();
        let data = null;
        try { data = text ? JSON.parse(text) : null; } catch { data = text; }
        if (!res.ok) throw new ApiError(res.status, (data && data.error) || `HTTP ${res.status}`);
        return data;
    }

    return {
        base,
        getPassword,
        setPassword,
        status: () => request('GET', '/api/status'),

        providers: () => request('GET', '/api/providers'),
        createProvider: (p) => request('POST', '/api/providers', p),
        updateProvider: (id, p) => request('PUT', `/api/providers/${id}`, p),
        deleteProvider: (id) => request('DELETE', `/api/providers/${id}`),
        activateProvider: (id) => request('POST', `/api/providers/${id}/activate`),
        testProvider: (id) => request('POST', `/api/providers/${id}/test`),

        conversations: () => request('GET', '/api/conversations'),
        createConversation: (mode) => request('POST', '/api/conversations', { mode }),
        deleteConversation: (id) => request('DELETE', `/api/conversations/${id}`),
        messages: (id) => request('GET', `/api/conversations/${id}/messages`),
        send: (id, content) => request('POST', `/api/conversations/${id}/messages`, { content }),

        documents: () => request('GET', '/api/documents'),
        uploadDocument: (file) => { const f = new FormData(); f.append('file', file); return request('POST', '/api/documents', f); },
        addTextDocument: (name, content) => request('POST', '/api/documents/text', { name, content }),
        deleteDocument: (id) => request('DELETE', `/api/documents/${id}`),
        reindex: () => request('POST', '/api/documents/reindex'),
    };
})();
