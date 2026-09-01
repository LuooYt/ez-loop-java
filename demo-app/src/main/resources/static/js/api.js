/**
 * REST API 请求封装。
 */
const API = {
    BASE: '/api',

    async request(method, path, body) {
        const opts = {
            method,
            headers: { 'Content-Type': 'application/json' },
        };
        if (body) opts.body = JSON.stringify(body);

        const resp = await fetch(this.BASE + path, opts);
        if (!resp.ok) {
            const text = await resp.text();
            throw new Error(`HTTP ${resp.status}: ${text}`);
        }
        return resp.json();
    },

    get(path) { return this.request('GET', path); },
    post(path, body) { return this.request('POST', path, body); },
    put(path, body) { return this.request('PUT', path, body); },
    del(path) { return this.request('DELETE', path); },

    // ===== Sessions =====
    sessions: {
        list()    { return API.get('/sessions'); },
        create(prompt) { return API.post('/sessions', { sessionPrompt: prompt }); },
        destroy(id)    { return API.del(`/sessions/${id}`); },
        cancel(id)     { return API.post(`/sessions/${id}/cancel`); },
        tokens(id)     { return API.get(`/sessions/${id}/tokens`); },
        messages(id)   { return API.get(`/sessions/${id}/messages`); },
    },

    // ===== Chat =====
    chat: {
        /**
         * 返回 SSE EventSource URL。
         */
        streamUrl(sessionId, message) {
            return API.BASE + `/chat/${sessionId}/stream?message=${encodeURIComponent(message)}`;
        },
        permissionResponse(sessionId, response) {
            return API.post(`/chat/${sessionId}/permission-response`, { response });
        },
        askResponse(sessionId, response) {
            return API.post(`/chat/${sessionId}/ask-response`, { response });
        },
    },

    // ===== Tools =====
    tools: {
        global()          { return API.get('/tools'); },
    },

    // ===== Permissions =====
    permissions: {
        state()        { return API.get('/permissions'); },
        setMode(mode)  { return API.put('/permissions/mode', { mode }); },
        addRule(toolName, description, action) {
            return API.post('/permissions/rules', { toolName, description, action });
        },
    },

    // ===== Metrics =====
    metrics: {
        session(sid)     { return API.get(`/metrics/${sid}`); },
    },
};
