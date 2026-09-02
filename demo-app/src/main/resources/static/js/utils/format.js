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
     * 格式化预估费用（美元）。
     *
     * 后端在「该模型定价未知」时返回 null —— 必须与 $0.00 区分开：把未知显示成
     * 零会让「没配价目表」被读成「没花钱」。定价已知时注明所用模型，因为价格会随
     * provider 调价而变，不注明依据的金额无法核对。
     *
     * @param cost         费用数值，null 表示定价未知
     * @param pricingModel 算费所用的模型名，可为 null
     */
    cost(cost, pricingModel) {
        if (cost === null || cost === undefined) {
            return '定价未知（可配 hms-core.pricing.models.* 或注入 TokenPricing）';
        }
        const n = Number(cost);
        if (!Number.isFinite(n)) return '定价未知';
        // 小额费用用 4 位小数：典型单轮对话不到 1 分钱，2 位小数会全部显示为 $0.00
        const amount = n < 1 ? `$${n.toFixed(4)}` : `$${n.toFixed(2)}`;
        return pricingModel ? `${amount}（按 ${pricingModel} 价目表）` : amount;
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
