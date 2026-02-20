package com.simpleec.retryjob;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.simpleec")
public class RetryJobApplication {
    public static void main(String[] args) {
        SpringApplication.run(RetryJobApplication.class, args);
    }
}
