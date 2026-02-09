package com.simpleec.job.scheduler;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.simpleec")
@MapperScan("com.simpleec.core.mapper")
@EnableScheduling
public class SimpleecSchedulerJobApplication {
    public static void main(String[] args) {
        SpringApplication.run(SimpleecSchedulerJobApplication.class, args);
    }
}
