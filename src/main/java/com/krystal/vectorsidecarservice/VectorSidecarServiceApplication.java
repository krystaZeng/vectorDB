package com.krystal.vectorsidecarservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VectorSidecarServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(VectorSidecarServiceApplication.class, args);
    }

}
