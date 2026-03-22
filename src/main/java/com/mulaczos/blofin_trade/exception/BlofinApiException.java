package com.mulaczos.blofin_trade.exception;

import lombok.Getter;

/**
 * Wyjątek reprezentujący błąd biznesowy zwrócony przez API BloFin
 * (np. niewystarczający margin, niepoprawny symbol itp.).
 * Odróżnia go od RuntimeException – pozwala handler-owi na zwrot 422 zamiast 500.
 */
@Getter
public class BlofinApiException extends RuntimeException {

    public static final String PENDING_ORDERS_PREVENT_LEVERAGE_ADJUSTMENT = "110007";
    public static final String OPERATION_NOT_SUPPORTED = "152404";

    private final String apiCode;
    private final String apiMsg;

    public BlofinApiException(String apiCode, String apiMsg) {
        super("BŁĄD API BLOFIN [%s]: %s".formatted(apiCode, apiMsg));
        this.apiCode = apiCode;
        this.apiMsg = apiMsg;
    }

}
