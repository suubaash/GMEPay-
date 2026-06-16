package com.gme.sim.merchant.scheme;

import java.util.List;

/**
 * A scheme wire message rendered for display: its identity, the raw frame as hex, and the
 * field-by-field breakdown. Scheme-agnostic — ZeroPay returns a 1,000-byte 전문; a future
 * Alipay+/KHQR codec could return its own framing under the same shape.
 *
 * @param scheme       scheme id (e.g. "ZEROPAY")
 * @param protocol     wire protocol label (e.g. "전문 / jeonmun (KFTC TCP)")
 * @param txnDivision  거래구분코드 (e.g. "420000")
 * @param messageType  전문구분코드 (e.g. "0200")
 * @param description  one-line human description
 * @param charset      byte charset of the frame (e.g. "EUC-KR")
 * @param lengthBytes  total frame length in bytes
 * @param hex          the raw frame as uppercase hex
 * @param fields       populated fields, in wire order
 */
public record WireMessage(
        String scheme, String protocol, String txnDivision, String messageType,
        String description, String charset, int lengthBytes, String hex,
        List<WireField> fields) {}
