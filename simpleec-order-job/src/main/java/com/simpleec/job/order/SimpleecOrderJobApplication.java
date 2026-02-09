package com.simpleec.job.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.simpleec")
@MapperScan("com.simpleec.core.mapper")
public class SimpleecOrderJobApplication {
    public static void main(String[] args) {
        SpringApplication.run(SimpleecOrderJobApplication.class, args);
    }
}
