package com.simpleec.backendjob;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.simpleec")
public class BackendJobApplication {
    public static void main(String[] args) {
        SpringApplication.run(BackendJobApplication.class, args);
    }
}
