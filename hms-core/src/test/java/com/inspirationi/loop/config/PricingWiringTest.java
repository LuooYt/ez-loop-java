package com.inspirationi.loop.config;

import com.inspirationi.loop.telemetry.BuiltinModelPricing;
import com.inspirationi.loop.telemetry.TokenPricing;
import com.inspirationi.loop.telemetry.TokenUsage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code hms-core.pricing.*} 的绑定与 {@link TokenPricing} Bean 的可覆写性。
 * <p>
 * 这两件事都无法靠单元测试证明：配置绑定要经过 Spring 的 relaxed binding
 * （{@code cache-read} → {@code setCacheRead}），而「集成方能否覆写」取决于
 * {@link org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean}
 * 是否真的生效。用 {@link ApplicationContextRunner} 起真实容器验证。
 * <p>
 * 只加载 {@link PricingProperties} 与被测的 Bean 方法，不整体加载 {@code AppConfig}
 * —— 后者依赖 ChatModel 等一串外部 Bean，与本测试关心的问题无关。
 */
class PricingWiringTest {

    /** 只暴露 AppConfig 的定价 Bean，绕开它对 ChatModel 等的依赖。 */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PricingProperties.class)
    static class PricingOnlyConfig {
        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(TokenPricing.class)
        TokenPricing tokenPricing(PricingProperties props) {
            return new BuiltinModelPricing(props.toModelRates());
        }
    }

    /** 集成方自己的计费实现 —— 应完全接管，内置价目表不再生效。 */
    @Configuration(proxyBeanMethods = false)
    static class CustomPricingConfig {
        static final BigDecimal FIXED = new BigDecimal("42.00");

        @Bean
        TokenPricing myOwnPricing() {
            return (model, usage) -> Optional.of(FIXED);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PricingOnlyConfig.class));

    /** 默认装配：使用内置价目表。 */
    @Test
    void defaultsToBuiltinPricing() {
        runner.run(context -> {
            TokenPricing pricing = context.getBean(TokenPricing.class);
            assertInstanceOf(BuiltinModelPricing.class, pricing,
                    "未配置时应回落到内置价目表");
            assertEquals(0, new BigDecimal("3").compareTo(
                            pricing.cost("sonnet", new TokenUsage(1_000_000, 0, 0, 0)).orElseThrow()),
                    "内置 Sonnet 输入价应为 $3/M");
        });
    }

    /**
     * yml 覆盖生效 —— 这是「不必等 SDK 发版就能改价」的核心保证。
     * <p>
     * 同时验证 relaxed binding：配置键写 {@code cache-read}，绑定到
     * {@code setCacheRead}。
     */
    @Test
    void configuredRatesOverrideBuiltinDefaults() {
        runner.withPropertyValues(
                        "hms-core.pricing.models.sonnet.input=99.0",
                        "hms-core.pricing.models.sonnet.output=100.0",
                        "hms-core.pricing.models.sonnet.cache-read=1.0")
                .run(context -> {
                    TokenPricing pricing = context.getBean(TokenPricing.class);
                    assertEquals(0, new BigDecimal("99").compareTo(
                                    pricing.cost("claude-sonnet-4-20250514",
                                            new TokenUsage(1_000_000, 0, 0, 0)).orElseThrow()),
                            "yml 里配的费率应覆盖内置默认值");
                });
    }

    /** 可为内置表之外的模型新增费率（私有部署、新模型）。 */
    @Test
    void configurationCanAddUnknownModels() {
        runner.withPropertyValues(
                        "hms-core.pricing.models.my-llm.input=1.0",
                        "hms-core.pricing.models.my-llm.output=2.0",
                        "hms-core.pricing.models.my-llm.cache-read=0.1")
                .run(context -> {
                    TokenPricing pricing = context.getBean(TokenPricing.class);
                    assertTrue(pricing.cost("my-llm-v3", TokenUsage.of(100, 100)).isPresent(),
                            "配置应能为内置表之外的模型定价");
                });
    }

    /**
     * 填不全的条目被跳过，回落到内置默认值。
     * <p>
     * 缺项按 0 补齐会让漏配的价目表静默给出看似合理的错误金额 —— 这里 output 与
     * cache-read 未填，整条应作废而非把 output 当成免费。
     */
    @Test
    void incompleteConfiguredRateIsIgnoredRatherThanTreatedAsFree() {
        runner.withPropertyValues("hms-core.pricing.models.sonnet.input=99.0")
                .run(context -> {
                    TokenPricing pricing = context.getBean(TokenPricing.class);
                    // 回落到内置 Sonnet 费率（input $3 + output $15）
                    assertEquals(0, new BigDecimal("18").compareTo(
                                    pricing.cost("sonnet",
                                            new TokenUsage(1_000_000, 1_000_000, 0, 0)).orElseThrow()),
                            "填不全的条目应整条丢弃并回落内置费率，而非把缺项当免费");

                    PricingProperties props = context.getBean(PricingProperties.class);
                    assertEquals(1, props.incompleteEntries().size(),
                            "不完整条目应可被检出以便 warn，而不是静默忽略");
                });
    }

    /**
     * 集成方声明自己的 Bean 即可完全接管计费。
     * <p>
     * 这是整个抽象的目的所在 —— 若 {@code ConditionalOnMissingBean} 失效，
     * 集成方会拿到内置价目表而浑然不知。
     */
    @Test
    void integratorBeanTakesOverCompletely() {
        runner.withUserConfiguration(CustomPricingConfig.class).run(context -> {
            TokenPricing pricing = context.getBean(TokenPricing.class);
            assertSame(CustomPricingConfig.FIXED,
                    pricing.cost("anything-at-all", TokenUsage.of(1, 1)).orElseThrow(),
                    "集成方的 TokenPricing Bean 应完全接管，内置实现不得再生效");
        });
    }
}
