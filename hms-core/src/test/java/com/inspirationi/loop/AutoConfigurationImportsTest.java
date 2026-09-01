package com.inspirationi.loop;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守护 Spring Boot 自动装配注册表。
 * <p>
 * hms-core 作为 SDK 被引入时，其 Bean 必须经
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 自动装配 —— 集成方不应被要求声明 {@code scanBasePackages}。该文件一旦缺失或
 * 漏项，症状是集成方启动时报 NoSuchBeanDefinitionException，且完全不指向根因。
 */
class AutoConfigurationImportsTest {

    private static final String IMPORTS_PATH =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    /** 必须被注册的自动配置类 —— 少一个就有整批 Bean 无法装配。 */
    private static final List<String> REQUIRED = List.of(
            "com.inspirationi.loop.config.AppConfig",
            "com.inspirationi.loop.config.ToolConfiguration",
            "com.inspirationi.loop.api.ApiAutoConfiguration",
            "com.inspirationi.loop.web.WebBridgeAutoConfiguration");

    private static List<String> readImports() throws IOException {
        ClassLoader cl = AutoConfigurationImportsTest.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(IMPORTS_PATH)) {
            assertNotNull(in, IMPORTS_PATH + " 必须存在于 classpath，否则 SDK 无法被自动装配");
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines()
                        .map(String::strip)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .toList();
            }
        }
    }

    @Test
    void importsFileListsAllAutoConfigurationClasses() throws IOException {
        List<String> declared = readImports();
        for (String required : REQUIRED) {
            assertTrue(declared.contains(required),
                    "自动装配注册表缺少 " + required + "，该类的 Bean 不会被装配");
        }
    }

    @Test
    void everyDeclaredClassIsLoadableAndAnnotated() throws IOException {
        for (String className : readImports()) {
            Class<?> type;
            try {
                type = Class.forName(className);
            } catch (ClassNotFoundException e) {
                throw new AssertionError(
                        "注册表引用了不存在的类 " + className + "（重命名或移动后忘了同步？）", e);
            }
            assertTrue(
                    type.isAnnotationPresent(
                            org.springframework.boot.autoconfigure.AutoConfiguration.class),
                    className + " 必须标注 @AutoConfiguration —— 仅 @Configuration "
                            + "只能被组件扫描发现，作为库分发时不生效");
        }
    }
}
