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
        pause(id)      { return API.post(`/sessions/${id}/pause`); },
        resume(id)     { return API.post(`/sessions/${id}/resume`); },
        compact(id)    { return API.post(`/sessions/${id}/compact`); },
        prompt(id)     { return API.get(`/sessions/${id}/prompt`); },
        updatePrompt(id, sessionPrompt) {
            return API.put(`/sessions/${id}/prompt`, { sessionPrompt });
        },
        /** 清理空闲会话 —— 集合级操作，不针对单个会话 */
        cleanup(idleSeconds) {
            return API.post(`/sessions/cleanup?idleSeconds=${idleSeconds}`);
        },
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
        session(sid)      { return API.get(`/tools/${sid}`); },
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
