package com.mulaczos.blofin_trade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BlofinTradeApplication {

    public static void main(String[] args) {
        SpringApplication.run(BlofinTradeApplication.class, args);
    }
}

