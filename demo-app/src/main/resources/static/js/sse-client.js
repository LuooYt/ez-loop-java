/**
 * SSE 流式客户端。
 * 连接 HMS Core SSE 端点，解析事件并触发对应回调。
 */
class SSEClient {
    constructor() {
        this.eventSource = null;
        this.listeners = {};
        /**
         * 本轮流是否已正常收尾（收到 complete 或 error 事件）。
         * 服务端 complete() 关闭连接后 EventSource 必然触发 onerror，
         * 靠这个标志把「说完了」和「真的断了」区分开。
         */
        this.finished = false;
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
        this.finished = false;

        const url = API.chat.streamUrl(sessionId, message);
        const source = new EventSource(url);
        this.eventSource = source;

        // 注册所有事件
        const eventTypes = ['token', 'tool_use', 'thinking', 'ask_user', 'permission', 'complete', 'error'];
        for (const type of eventTypes) {
            source.addEventListener(type, (e) => {
                // complete/error 是本轮的终止事件，之后服务端会关闭连接
                if (type === 'complete' || type === 'error') {
                    this.finished = true;
                }
                try {
                    const data = JSON.parse(e.data);
                    this._emit(type, data);
                } catch (err) {
                    console.error('SSE parse error for', type, err);
                }
            });
        }

        // 连接断开：正常收尾后的断开是预期行为，不是错误。
        // 未收尾就断开才是真故障（网络中断 / 服务端崩溃），此时也必须
        // 主动 close()，否则 EventSource 会自动重连并重跑一轮 Agent。
        source.onerror = (e) => {
            const finished = this.finished;
            this.disconnect();
            if (finished) {
                console.log('SSE closed by server after completion');
                return;
            }
            console.error('SSE connection error:', e);
            this._emit('error', { message: 'SSE 连接中断' });
        };

        // 连接打开
        source.onopen = () => {
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
