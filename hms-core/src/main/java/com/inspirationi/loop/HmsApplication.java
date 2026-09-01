package com.inspirationi.loop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * HMS Core — AI Agent SDK 主启动类。
 * <p>
 * 作为 Spring Boot 自动配置入口，被其他 Java Web 应用集成时引入依赖即可。
 * 无法独立运行（无 CLI/TUI），仅提供核心 SDK Bean。
 */
@SpringBootApplication
public class HmsApplication {

    /**
     * 应用入口 —— 启动 Spring Boot 容器，加载自动配置 Bean。
     */
    public static void main(String[] args) {
        SpringApplication.run(HmsApplication.class, args);
    }
}
