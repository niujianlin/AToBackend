package com.ato.account;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.util.TimeZone;

@SpringBootApplication
@ConfigurationPropertiesScan("com.ato.account.config")
@MapperScan("com.ato.account.mapper")
public class AtoAccountApplication {

    public static void main(String[] args) {
        // D18：全链路按 UTC 处理，避免宿主时区影响时间比较与落库
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(AtoAccountApplication.class, args);
    }
}
