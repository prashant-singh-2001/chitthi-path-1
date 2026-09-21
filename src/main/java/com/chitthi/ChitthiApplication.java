package com.chitthi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ChitthiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChitthiApplication.class, args);
    }
}
