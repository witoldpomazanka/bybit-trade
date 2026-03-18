package com.mulaczos.bybit_trade;

import com.mulaczos.bybit_trade.service.BlofinApiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Slf4j
@Configuration
public class BlofinApiConfig {

    @Value("${blofin.api.key}")
    private String apiKey;

    @Value("${blofin.api.secret}")
    private String secretKey;

    @Value("${blofin.api.passphrase}")
    private String passphrase;

    @Bean
    @Primary
    public BlofinApiClient blofinApiClient() {
        log.info("Inicjalizacja klienta BloFin API. Endpoint: https://openapi.blofin.com");
        return new BlofinApiClient(apiKey, secretKey, passphrase, "https://openapi.blofin.com");
    }
}
