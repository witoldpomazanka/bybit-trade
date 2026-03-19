package com.mulaczos.blofin_trade.exception;

/**
 * Wyjątek reprezentujący błąd biznesowy zwrócony przez API BloFin
 * (np. niewystarczający margin, niepoprawny symbol itp.).
 * Odróżnia go od RuntimeException – pozwala handler-owi na zwrot 422 zamiast 500.
 */
public class BlofinApiException extends RuntimeException {

    private final String apiCode;
    private final String apiMsg;

    public BlofinApiException(String apiCode, String apiMsg) {
        super("BŁĄD API BLOFIN [%s]: %s".formatted(apiCode, apiMsg));
        this.apiCode = apiCode;
        this.apiMsg = apiMsg;
    }

    public String getApiCode() {
        return apiCode;
    }

    public String getApiMsg() {
        return apiMsg;
    }
}
