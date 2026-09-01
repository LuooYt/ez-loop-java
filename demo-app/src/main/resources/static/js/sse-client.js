/**
 * SSE 流式客户端。
 * 连接 HMS Core SSE 端点，解析事件并触发对应回调。
 */
class SSEClient {
    constructor() {
        this.eventSource = null;
        this.listeners = {};
    }

    /**
     * 注册事件监听器。
     * @param {string} event - 事件名：token, tool_use, thinking, ask_user, permission, complete, error, done
     * @param {function} callback
     */
    on(event, callback) {
        if (!this.listeners[event]) this.listeners[event] = [];
        this.listeners[event].push(callback);
    }

    /**
     * 连接到 SSE 流。
     * @param {string} sessionId
     * @param {string} message
     */
    connect(sessionId, message) {
        this.disconnect();

        const url = API.chat.streamUrl(sessionId, message);
        this.eventSource = new EventSource(url);

        // 注册所有事件
        const eventTypes = ['token', 'tool_use', 'thinking', 'ask_user', 'permission', 'complete', 'error'];
        for (const type of eventTypes) {
            this.eventSource.addEventListener(type, (e) => {
                try {
                    const data = JSON.parse(e.data);
                    this._emit(type, data);
                } catch (err) {
                    console.error('SSE parse error for', type, err);
                }
            });
        }

        // 连接错误
        this.eventSource.onerror = (e) => {
            console.error('SSE connection error:', e);
            this._emit('error', { message: 'SSE 连接错误' });
            this.disconnect();
        };

        // 连接打开
        this.eventSource.onopen = () => {
            console.log('SSE connected for session', sessionId);
        };
    }

    /**
     * 断开 SSE 连接。
     */
    disconnect() {
        if (this.eventSource) {
            this.eventSource.close();
            this.eventSource = null;
        }
    }

    _emit(event, data) {
        const handlers = this.listeners[event];
        if (handlers) {
            for (const fn of handlers) {
                try { fn(data); } catch (e) { console.error('SSE handler error:', e); }
            }
        }
    }
}
