package com.simpleec.core.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * 核心服務自動配置 - 確保 simpleec-core.service 包中的 @Service 被掃描和註冊
 *
 * 由 Spring Boot 自動發現（透過 META-INF/spring/AutoConfiguration.imports）
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.simpleec.core.service")
public class ServiceAutoConfiguration {
}
