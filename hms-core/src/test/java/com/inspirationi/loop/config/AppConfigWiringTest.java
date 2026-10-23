package com.inspirationi.loop.config;

import com.inspirationi.loop.api.DefaultPromptManager;
import com.inspirationi.loop.api.PromptManager;
import com.inspirationi.loop.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AppConfig} 的装配约定：缺一側提供商不影响启动、可被集成方覆写。
 * <p>
 * 这两条都只有真实容器能证明 —— {@code ObjectProvider} 的「缺席返回 null」语义、
 * {@code @ConditionalOnMissingBean} 的生效时机，都是 Spring 的行为而不是本类的逻辑。
 * <p>
 * 只加载 {@code AppConfig} 本身，另外补上它需要的两个与本主题无关的协作者
 * （{@code PromptManager} / {@code ToolRegistry}）—— 它们由 {@code ApiAutoConfiguration}
 * 提供，整体加载会牵进会话管理器等一大串依赖。
 */
class AppConfigWiringTest {

    /** 记录自己是否被选中 —— 断言「返回的是哪一个」时用实例身份。 */
    private static ChatModel stubModel(String name) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new IllegalStateException(name + " 不应在装配测试中被调用");
            }

            @Override
            public ChatOptions getOptions() {
                return null;
            }

            @Override
            public String toString() {
                return name;
            }
        };
    }

    /** AppConfig 里除 ChatModel 之外的两个外部协作者，只求容器能 refresh。 */
    @Configuration(proxyBeanMethods = false)
    static class Collaborators {
        @Bean
        PromptManager promptManager() {
            return (PromptManager) Proxy.newProxyInstance(
                    PromptManager.class.getClassLoader(),
                    new Class<?>[]{PromptManager.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getGlobalPrompt", "getSessionPrompt", "buildFullPrompt" -> "";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "stub-prompt-manager";
                        default -> null;
                    });
        }

        @Bean
        ToolRegistry toolRegistry() {
            return new ToolRegistry();
        }
    }

    /** 只声明 OpenAI 一側模型，模拟集成方只配了一个提供商。 */
    @Configuration(proxyBeanMethods = false)
    static class OpenAiOnly {
        @Bean
        ChatModel openAiChatModel() {
            return stubModel("openai");
        }
    }

    /** 两个提供商都在，用于验证「按配置选」。 */
    @Configuration(proxyBeanMethods = false)
    static class BothProviders {
        @Bean
        ChatModel openAiChatModel() {
            return stubModel("openai");
        }

        @Bean
        ChatModel anthropicChatModel() {
            return stubModel("anthropic");
        }
    }

    /** 集成方接管全局提示词 —— Bean 名与内置实现相同。 */
    @Configuration(proxyBeanMethods = false)
    static class CustomGlobalPrompt {
        @Bean
        String globalPrompt() {
            return "集成方自定义的全局提示词";
        }
    }

    /**
     * 每个用例都显式指定 {@code hms-core.provider} —— 不指定时 Spring 会从环境变量
     * 兜底（{@code HMS_CORE_PROVIDER}），构建机上的取值不该影响断言结果。
     */
    private ApplicationContextRunner runner(String provider) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AppConfig.class))
                .withUserConfiguration(Collaborators.class)
                .withPropertyValues("hms-core.provider=" + provider);
    }

    private static List<String> promptBeanNames(org.springframework.context.ApplicationContext context) {
        return new ArrayList<>(List.of(context.getBeanNamesForType(String.class)));
    }

    /**
     * 只配了 OpenAI 时，缺失的 {@code anthropicChatModel} 不得阻止启动。
     * <p>
     * 这是 R4.4 要修的核心缺陷：原先两个 {@code @Qualifier} 参数都是强制依赖，
     * 只用一种提供商的项目会在启动报 {@code NoSuchBeanDefinitionException}，
     * 而那个缺失的 Bean 永远不会被用到。
     */
    @Test
    void missingOneProviderDoesNotBlockStartup() {
        runner("openai").withUserConfiguration(OpenAiOnly.class).run(context -> {
            assertNull(context.getStartupFailure());
            assertSame(context.getBean("openAiChatModel"), context.getBean("activeChatModel"),
                    "只有 OpenAI 时，activeChatModel 应直接就是它");
        });
    }

    /** 配置指向 anthropic 但只装了 OpenAI —— 回退到可用的一方，而不是起不来。 */
    @Test
    void fallsBackToAvailableProviderWhenConfiguredOneIsMissing() {
        runner("anthropic").withUserConfiguration(OpenAiOnly.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertSame(context.getBean("openAiChatModel"), context.getBean("activeChatModel"),
                            "指定的提供商缺席时应回退到可用的一方");
                });
    }

    /** 两者都在时，按 {@code hms-core.provider} 选择。 */
    @Test
    void selectsConfiguredProviderWhenBothArePresent() {
        runner("anthropic").withUserConfiguration(BothProviders.class)
                .run(context -> assertSame(context.getBean("anthropicChatModel"),
                        context.getBean("activeChatModel")));

        runner("openai").withUserConfiguration(BothProviders.class)
                .run(context -> assertSame(context.getBean("openAiChatModel"),
                        context.getBean("activeChatModel")));
    }

    /** 一个都没有时给出可执行的错误，而不是让容器抛一个只有栈帧的注入失败。 */
    @Test
    void noProviderFailsWithActionableMessage() {
        runner("openai").run(context -> {
            assertNotNull(context.getStartupFailure(), "没有任何 ChatModel 时启动必须失败");
            String message = rootMessage(context.getStartupFailure());
            assertTrue(message.contains("openAiChatModel") && message.contains("anthropicChatModel"),
                    "错误信息应点名两个候选 Bean，实际为：" + message);
            assertTrue(message.contains("spring.ai."),
                    "错误信息应指出修复位置（Spring AI 配置），实际为：" + message);
        });
    }

    /** 无自定义时回落到内置默认提示词。 */
    @Test
    void defaultsToBuiltinGlobalPrompt() {
        runner("openai").withUserConfiguration(OpenAiOnly.class).run(context -> {
            assertEquals(DefaultPromptManager.DEFAULT_GLOBAL_PROMPT,
                    context.getBean("globalPrompt"),
                    "未配置时应使用内置默认提示词");
        });
    }

    /** {@code hms-core.global-prompt} 配置优先于内置默认值。 */
    @Test
    void configuredGlobalPromptWins() {
        runner("openai").withUserConfiguration(OpenAiOnly.class)
                .withPropertyValues("hms-core.global-prompt=来自配置的提示词")
                .run(context -> assertEquals("来自配置的提示词", context.getBean("globalPrompt")));
    }

    /**
     * 集成方声明同名 Bean 即可接管。
     * <p>
     * 缺了 {@code @ConditionalOnMissingBean(name="globalPrompt")} 时这里会撞名 —
     * BeanDefinitionOverrideException 或候选歧义，都不是集成方能自己解决的。
     */
    @Test
    void integratorCanOverrideGlobalPrompt() {
        runner("openai").withUserConfiguration(OpenAiOnly.class, CustomGlobalPrompt.class).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals("集成方自定义的全局提示词", context.getBean("globalPrompt"));
            assertEquals(1, promptBeanNames(context).stream()
                            .filter(name -> name.equals("globalPrompt")).count(),
                    "同名 Bean 只应存在一个，实际：" + promptBeanNames(context));
        });
    }

    /** 取整条因果链的 message —— Spring 会把 Bean 创建失败包进 BeanCreationException。 */
    private static String rootMessage(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                all.append(current.getMessage()).append('\n');
            }
        }
        return all.toString();
    }
}
