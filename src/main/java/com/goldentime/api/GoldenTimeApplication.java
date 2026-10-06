package com.goldentime.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GoldenTimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(GoldenTimeApplication.class, args);
    }
}
