package com.inspirationi.hmsweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * HMS Web Demo 主启动类。
 * <p>
 * 扫描 hms-core 的自动配置包和本项目的包，
 * 提供 Web 界面来调用 HMS Core 的 AI Agent 能力。
 */
@SpringBootApplication(scanBasePackages = {"com.inspirationi.loop", "com.inspirationi.hmsweb"})
public class HmsWebDemoApplication {

    /**
     * 应用启动入口：通过 Spring Boot 内嵌 Web 服务器启动 HMS Web Demo。
     */
    public static void main(String[] args) {
        SpringApplication.run(HmsWebDemoApplication.class, args);
    }
}
