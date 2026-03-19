package com.mulaczos.blofin_trade.service;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class TwilioNotificationService {

    @Value("${twilio.account.sid}")
    private String accountSid;

    @Value("${twilio.auth.token}")
    private String authToken;

    @Value("${twilio.phone.number}")
    private String twilioPhoneNumber;

    @Value("${twilio.recipient.phone}")
    private String recipientPhoneNumber;

    @Value("${twilio.notifications.enabled:false}")
    private boolean notificationsEnabled;

    @PostConstruct
    public void init() {
        if (notificationsEnabled) {
            if (accountSid == null || accountSid.isEmpty() ||
                    authToken == null || authToken.isEmpty() ||
                    twilioPhoneNumber == null || twilioPhoneNumber.isEmpty() ||
                    recipientPhoneNumber == null || recipientPhoneNumber.isEmpty()) {

                log.warn("Powiadomienia SMS są włączone, ale brakuje niezbędnych danych Twilio. SMS-y nie będą wysyłane.");
                notificationsEnabled = false;
            } else {
                Twilio.init(accountSid, authToken);
                log.info("Serwis powiadomień Twilio został zainicjalizowany");
            }
        } else {
            log.info("Powiadomienia SMS są wyłączone");
        }
    }

    public void sendPositionOpenedNotification(String symbol, String side, String quantity, int leverage, String orderType, String entryPrice, String finalUsdtAmount) {
        if (!notificationsEnabled) {
            log.info("Powiadomienia SMS są wyłączone. Pominięto wysyłanie SMS.");
            return;
        }

        try {
            String coin = symbol.replace("-USDT", "").replace("USDT", "");
            String sideFormatted = side.equalsIgnoreCase("Buy") ? "LONG" : "SHORT";
            
            String usdtValue = finalUsdtAmount;
            if (usdtValue == null && entryPrice != null && quantity != null && !quantity.equals("unknown") && !quantity.equals("nieznana")) {
                try {
                    double price = Double.parseDouble(entryPrice);
                    double qty = Double.parseDouble(quantity);
                    usdtValue = String.format("%.2f", price * qty);
                } catch (Exception e) {
                    log.warn("Nie udało się obliczyć wartości USDT dla SMS: entryPrice={}, quantity={}", entryPrice, quantity);
                }
            }

            if (usdtValue == null) usdtValue = "nieznana";

            String messageBody = String.format(
                    "Otwarto pozycję: %s %s | Wartość: %s USDT | Lewar: x%d | Typ: %s",
                    sideFormatted,
                    coin,
                    usdtValue,
                    leverage,
                    orderType
            );

            Message message = Message.creator(
                    new PhoneNumber(recipientPhoneNumber),
                    new PhoneNumber(twilioPhoneNumber),
                    messageBody
            ).create();

            log.info("Wysłano powiadomienie SMS. SID: {}", message.getSid());
        } catch (Exception e) {
            log.error("Błąd podczas wysyłania powiadomienia SMS: {}", e.getMessage(), e);
        }
    }
}
