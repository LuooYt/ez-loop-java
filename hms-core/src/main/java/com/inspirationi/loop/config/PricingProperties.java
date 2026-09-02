package com.inspirationi.loop.config;

import com.inspirationi.loop.telemetry.BuiltinModelPricing.ModelRate;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Token 计费配置 —— {@code hms-core.pricing.*}。
 * <p>
 * <b>为什么这里用 {@code @ConfigurationProperties} 而项目其余处用 {@code @Value}</b>：
 * 费率是「模型模式 → 三个价格」的嵌套映射，{@code @Value} 表达不了。其余标量配置
 * 沿用既有的 {@code @Value} 风格不变。
 * <p>
 * <b>为什么要能配</b>：价格会变、新模型会出。内置价目表写死在 SDK 里意味着每次调价
 * 都得等新版本，集成方在此期间只能拿到过期金额。配置覆盖让运维改一行 yml 即可，
 * 无需发版。
 * <p>
 * 示例（键是模型名的<b>子串</b>，大小写不敏感；更长的模式优先匹配）：
 * <pre>{@code
 * hms-core:
 *   pricing:
 *     models:
 *       claude-opus-4-8:
 *         input: 15.0
 *         output: 75.0
 *         cache-read: 1.5
 * }</pre>
 * 单位统一为<b>每百万 token 的美元价</b>。未列出的模型沿用
 * {@link com.inspirationi.loop.telemetry.BuiltinModelPricing#defaultRates() 内置默认值}；
 * 同名键则覆盖之。
 */
@ConfigurationProperties(prefix = "hms-core.pricing")
public class PricingProperties {

    /** 模型模式 → 费率。为空时全部使用内置默认值。 */
    private Map<String, Rate> models = new LinkedHashMap<>();

    public Map<String, Rate> getModels() {
        return models;
    }

    public void setModels(Map<String, Rate> models) {
        this.models = models != null ? models : new LinkedHashMap<>();
    }

    /**
     * 转成 {@link BuiltinModelPricing} 需要的费率表，跳过填写不完整的条目。
     * <p>
     * 缺项的条目<b>整条丢弃</b>而非按 0 补齐：0 会被当成「这项免费」并静默算进总额，
     * 让一份漏配的价目表给出看似合理的错误金额。丢弃则回落到内置默认值，行为可预期。
     */
    public Map<String, ModelRate> toModelRates() {
        Map<String, ModelRate> rates = new LinkedHashMap<>();
        models.forEach((pattern, rate) -> {
            if (pattern != null && !pattern.isBlank() && rate != null && rate.isComplete()) {
                rates.put(pattern, rate.toModelRate());
            }
        });
        return rates;
    }

    /** 配置文件里未填全的条目（便于装配时 warn 出来，而不是静默忽略）。 */
    public Map<String, Rate> incompleteEntries() {
        Map<String, Rate> bad = new LinkedHashMap<>();
        models.forEach((pattern, rate) -> {
            if (pattern == null || pattern.isBlank() || rate == null || !rate.isComplete()) {
                bad.put(String.valueOf(pattern), rate);
            }
        });
        return bad;
    }

    /**
     * 单个模型的费率（每百万 token 美元）。
     * <p>
     * 用可变 JavaBean 而非 record：Spring Boot 的构造器绑定要求所有属性都出现，
     * 而这里希望「漏填某项」能被检测出来并给出明确提示（见 {@link #isComplete()}），
     * 而不是在绑定阶段抛一个难读的异常。
     */
    public static class Rate {

        /** 普通输入的每百万 token 美元价。 */
        private BigDecimal input;
        /** 输出的每百万 token 美元价。 */
        private BigDecimal output;
        /** 缓存读取的每百万 token 美元价。 */
        private BigDecimal cacheRead;

        public BigDecimal getInput() { return input; }
        public void setInput(BigDecimal input) { this.input = input; }

        public BigDecimal getOutput() { return output; }
        public void setOutput(BigDecimal output) { this.output = output; }

        public BigDecimal getCacheRead() { return cacheRead; }
        public void setCacheRead(BigDecimal cacheRead) { this.cacheRead = cacheRead; }

        /** 三项是否都已填写 —— 缺任一项即视为无效配置。 */
        public boolean isComplete() {
            return input != null && output != null && cacheRead != null;
        }

        ModelRate toModelRate() {
            return new ModelRate(input, output, cacheRead);
        }

        @Override
        public String toString() {
            return "input=" + input + ", output=" + output + ", cacheRead=" + cacheRead;
        }
    }
}
