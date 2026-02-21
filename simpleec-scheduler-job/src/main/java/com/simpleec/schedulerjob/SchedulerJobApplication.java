package com.simpleec.schedulerjob;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.simpleec.common", "com.simpleec.schedulerjob"})
@EnableScheduling
public class SchedulerJobApplication {
    public static void main(String[] args) {
        SpringApplication.run(SchedulerJobApplication.class, args);
    }
}
