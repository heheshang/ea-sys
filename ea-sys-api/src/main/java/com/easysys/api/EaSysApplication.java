package com.easysys.api;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = {"com.easysys.api", "com.easysys.engine"})
@ConfigurationPropertiesScan
@MapperScan({"com.easysys.api.mapper", "com.easysys.engine.mapper"})
public class EaSysApplication {

    public static void main(String[] args) {
        SpringApplication.run(EaSysApplication.class, args);
    }
}