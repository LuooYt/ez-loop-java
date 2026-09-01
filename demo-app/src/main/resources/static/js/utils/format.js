/**
 * 格式化工具函数。
 */
const Format = {
    /**
     * 格式化数字（超过 1000 显示 K，超过 1000000 显示 M）。
     */
    number(n) {
        if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M';
        if (n >= 1000) return (n / 1000).toFixed(1) + 'K';
        return String(n);
    },

    /**
     * 格式化秒数为可读时长。
     */
    duration(seconds) {
        if (seconds < 60) return seconds + 's';
        if (seconds < 3600) return Math.floor(seconds / 60) + 'm ' + (seconds % 60) + 's';
        return Math.floor(seconds / 3600) + 'h ' + Math.floor((seconds % 3600) / 60) + 'm';
    },

    /**
     * 截断字符串。
     */
    truncate(s, maxLen) {
        if (!s) return '';
        if (s.length <= maxLen) return s;
        return s.substring(0, maxLen) + '...';
    },

    /**
     * 获取当前时间字符串 (HH:MM:SS)。
     */
    time() {
        const now = new Date();
        return now.toTimeString().substring(0, 8);
    },

    /**
     * 转义 HTML 特殊字符。
     */
    escapeHtml(s) {
        if (!s) return '';
        return s.replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;');
    }
};
