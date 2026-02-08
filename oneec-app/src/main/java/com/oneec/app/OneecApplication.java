package com.oneec.app;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.oneec")
@MapperScan("com.oneec.core.mapper")
@EnableScheduling
public class OneecApplication {

    public static void main(String[] args) {
        SpringApplication.run(OneecApplication.class, args);
    }
}
