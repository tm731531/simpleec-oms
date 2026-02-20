package com.simpleec.orderjob;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.simpleec")
public class OrderJobApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderJobApplication.class, args);
    }
}
