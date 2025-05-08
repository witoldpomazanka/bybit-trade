package com.mulaczos.bybit_trade.service;

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

    /**
     * Wysyła powiadomienie SMS o otwartej pozycji
     * @param symbol Symbol instrumentu
     * @param side Kierunek pozycji (Buy/Sell)
     * @param quantity Ilość
     * @param leverage Dźwignia
     */
    public void sendPositionOpenedNotification(String symbol, String side, String quantity, int leverage) {
        if (!notificationsEnabled) {
            log.debug("Powiadomienia SMS są wyłączone. Pominięto wysyłanie SMS.");
            return;
        }

        try {
            String messageBody = String.format(
                "BYBIT TRADE: Otwarto pozycję %s dla %s, ilość: %s, dźwignia: x%d",
                side.equals("Buy") ? "LONG" : "SHORT",
                symbol,
                quantity,
                leverage
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