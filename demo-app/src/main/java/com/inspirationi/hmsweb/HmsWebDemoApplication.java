package com.inspirationi.hmsweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * HMS Web Demo 主启动类。
 * <p>
 * 只扫描本项目的包 —— hms-core 的 Bean 经 Spring Boot 自动装配引入
 * （见 hms-core 的 {@code META-INF/spring/...AutoConfiguration.imports}），
 * 集成方无需声明 {@code scanBasePackages}。
 */
@SpringBootApplication
public class HmsWebDemoApplication {

    /**
     * 应用启动入口：通过 Spring Boot 内嵌 Web 服务器启动 HMS Web Demo。
     */
    public static void main(String[] args) {
        SpringApplication.run(HmsWebDemoApplication.class, args);
    }
}
