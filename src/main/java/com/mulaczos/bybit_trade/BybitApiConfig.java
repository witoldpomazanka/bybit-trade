package com.mulaczos.bybit_trade;

import com.mulaczos.bybit_trade.service.BybitApiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class BybitApiConfig {

    @Value("${bybit.api.key}")
    private String apiKey;

    @Value("${bybit.api.secret}")
    private String secretKey;

    @Value("${bybit.api.testnet:false}")
    private boolean isTestnet;

    @Bean
    public BybitApiClient bybitApiClient() {
        String baseUrl = isTestnet 
                ? "https://api-testnet.bybit.com" 
                : "https://api.bybit.com";
        
        log.info("Inicjalizacja klienta Bybit API. Testnet: {}", isTestnet);
        return new BybitApiClient(apiKey, secretKey, baseUrl);
    }
} 