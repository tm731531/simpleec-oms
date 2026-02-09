package com.simpleec.gateway;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.simpleec")
@MapperScan("com.simpleec.core.mapper")
public class SimpleecGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(SimpleecGatewayApplication.class, args);
    }
}
