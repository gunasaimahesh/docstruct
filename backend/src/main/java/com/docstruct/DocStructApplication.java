package com.docstruct;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DocStructApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocStructApplication.class, args);
    }
}
