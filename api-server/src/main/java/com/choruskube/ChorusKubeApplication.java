package com.choruskube;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.choruskube")
@ConfigurationPropertiesScan("com.choruskube")
@EnableScheduling
public class ChorusKubeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChorusKubeApplication.class, args);
    }
}
