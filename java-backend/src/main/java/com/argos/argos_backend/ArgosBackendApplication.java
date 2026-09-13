package com.argos.argos_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAspectJAutoProxy
@EnableScheduling

public class ArgosBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArgosBackendApplication.class, args);
        System.out.println("SPRING BOOT RUNNING SUCCESSFULLY!!");
    }
}