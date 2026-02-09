package com.simpleec.api;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.simpleec")
@MapperScan("com.simpleec.core.mapper")
public class SimpleecApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(SimpleecApiApplication.class, args);
    }
}
