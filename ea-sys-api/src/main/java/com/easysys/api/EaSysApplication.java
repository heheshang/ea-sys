package com.easysys.api;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.easysys.api.mapper")
public class EaSysApplication {

    public static void main(String[] args) {
        SpringApplication.run(EaSysApplication.class, args);
    }
}