package com.simpleec.channeljob;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.simpleec")
public class ChannelJobApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChannelJobApplication.class, args);
    }
}
