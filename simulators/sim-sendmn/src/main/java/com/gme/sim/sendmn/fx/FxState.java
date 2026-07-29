package com.gme.sim.sendmn.fx;

import com.gme.sim.sendmn.config.SimSendmnProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The rate SendMN has "registered" with the partner (doc §9) — the sim's source of
 * truth for the Confirm-side SETTLEMENT_AMOUNT re-check (error 307). Seeded from
 * {@code gmepay.sim.sendmn.default-rate}; changed via POST /sim/fx-rate (each change
 * mints a fresh FX_TICKER_NO, as SendMN's periodic registrations would).
 */
@Component
public class FxState {

    /** USD settlement amounts are Decimal(18,4); rounding mirrors the adapter (HALF_UP). */
    public static final int SETTLEMENT_SCALE = 4;

    private static final DateTimeFormatter NOTICE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private final AtomicLong tickerSeq = new AtomicLong(0);
    private volatile BigDecimal rate;
    private volatile String fxTickerNo;
    private volatile String noticeDate;

    public FxState(SimSendmnProperties props) {
        set(props.getDefaultRate());
    }

    /** Registers a new buy rate and mints a new ticker id. */
    public synchronized void set(BigDecimal newRate) {
        if (newRate == null || newRate.signum() <= 0) {
            throw new IllegalArgumentException("rate must be > 0");
        }
        this.rate = newRate;
        this.fxTickerNo = String.format("SIMTKR-%06d", tickerSeq.incrementAndGet());
        this.noticeDate = LocalDate.now(ZoneOffset.UTC).format(NOTICE_FMT);
    }

    public BigDecimal rate() { return rate; }
    public String fxTickerNo() { return fxTickerNo; }
    public String noticeDate() { return noticeDate; }

    /** LOCAL / RATE at scale 4 HALF_UP — must match the adapter's FxRateService. */
    public BigDecimal settlementAmount(BigDecimal localAmount) {
        return localAmount.divide(rate, SETTLEMENT_SCALE, RoundingMode.HALF_UP);
    }
}
