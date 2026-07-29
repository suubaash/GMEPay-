package com.gme.pay.scheme.ninepay.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.scheme.ninepay.client.NinepayApiClient;
import com.gme.pay.scheme.ninepay.client.NinepayErrorException;
import com.gme.pay.scheme.ninepay.client.NinepayTransportException;
import com.gme.pay.scheme.ninepay.dto.IpnAck;
import com.gme.pay.scheme.ninepay.dto.PayoutRequest;
import com.gme.pay.scheme.ninepay.dto.PayoutResponse;
import com.gme.pay.scheme.ninepay.persistence.IpnRejectReason;
import com.gme.pay.scheme.ninepay.persistence.NpIpnEventEntity;
import com.gme.pay.scheme.ninepay.persistence.NpIpnEventRepository;
import com.gme.pay.scheme.ninepay.persistence.NpPayoutEntity;
import com.gme.pay.scheme.ninepay.persistence.NpPayoutRepository;
import com.gme.pay.scheme.ninepay.sign.NinepaySigner;
import com.gme.pay.scheme.ninepay.status.PayoutStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural tests for {@link NinepaySchemeAdapter}: VND validation, the idempotency /
 * 1062-duplicate / timeout→poll gates (never resubmit), and IPN application including the
 * post-SUCCESS 009 bank reversal and signature-failure auditing.
 */
class NinepaySchemeAdapterTest {

    private static final String REQ = "GMEPAY9P202607270000000001";

    private NinepayApiClient client;
    private NpPayoutRepository payouts;
    private NpIpnEventRepository ipnEvents;
    private NinepaySigner ourSigner;
    private NinepaySigner ninepaySideSigner; // acts as 9Pay: signs IPNs with 9Pay's private key
    private ObjectMapper mapper;
    private NinepayIpnReplayGuard replayGuard;
    private NinepaySchemeAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        client = mock(NinepayApiClient.class);
        payouts = mock(NpPayoutRepository.class);
        ipnEvents = mock(NpIpnEventRepository.class);
        mapper = new ObjectMapper();

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair ours = generator.generateKeyPair();
        KeyPair ninepays = generator.generateKeyPair();
        // Our signer holds OUR private key + 9PAY's public key (the verify direction).
        ourSigner = new NinepaySigner(ours.getPrivate(), ninepays.getPublic(), "SHA256");
        ninepaySideSigner = new NinepaySigner(ninepays.getPrivate(), ours.getPublic(), "SHA256");

        when(payouts.save(any(NpPayoutEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(payouts.findByRequestId(anyString())).thenReturn(Optional.empty());
        when(ipnEvents.save(any(NpIpnEventEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ipnEvents.saveAndFlush(any(NpIpnEventEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(client.partnerId()).thenReturn("GMEPAY");

        // T5-4: fixed clock so the IPN acceptance window is deterministic. IPN bodies below
        // are stamped created_at 2026-07-27 10:00:00 (GMT+7) = 03:00Z, i.e. well inside the
        // default 7-day window relative to this instant.
        replayGuard = new NinepayIpnReplayGuard(ipnEvents, Clock.fixed(
                Instant.parse("2026-07-27T06:00:00Z"), ZoneOffset.UTC),
                NinepayIpnReplayGuard.DEFAULT_MAX_AGE_MINUTES);

        adapter = new NinepaySchemeAdapter(client, ourSigner, payouts, ipnEvents, mapper, replayGuard);
    }

    private static PayoutRequest payout(long amountVnd) {
        return new PayoutRequest(REQ, "970418", "1023020330000", 0, "NGUYEN VAN A",
                amountVnd, "GME PAYOUT 123", null, null, null, null, null, null, null);
    }

    // ------------------------------------------------------------------ validation

    @Test
    @DisplayName("VND validation: below the 2,000 VND minimum is rejected before any wire call")
    void validation_minAmount() {
        ApiException ex = assertThrows(ApiException.class, () -> adapter.submitPayout(payout(1_999L)));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
        verify(client, never()).transfer(any());
        verify(payouts, never()).save(any());
    }

    @Test
    @DisplayName("VND validation: exactly 2,000 VND passes")
    void validation_minAmountBoundary() {
        when(client.transfer(any())).thenReturn(new NinepayApiClient.TransferResult(
                REQ, "TXN1", 2_000L, 2_200L, 200L, "PENDING", "2026-07-27 10:00:00", null));
        PayoutResponse resp = adapter.submitPayout(payout(2_000L));
        assertEquals("PENDING", resp.status());
    }

    @Test
    @DisplayName("content validation: 9Pay-forbidden special characters are rejected")
    void validation_contentCharset() {
        PayoutRequest bad = new PayoutRequest(REQ, "970418", "1023020330000", 0, "NGUYEN VAN A",
                50_000L, "GME|PAYOUT_1", null, null, null, null, null, null, null);
        ApiException ex = assertThrows(ApiException.class, () -> adapter.submitPayout(bad));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
        verify(client, never()).transfer(any());
    }

    // ------------------------------------------------------------------ happy path

    @Test
    @DisplayName("submit: row persisted before the wire call; scheme accept recorded")
    void submit_happyPath() {
        when(client.transfer(any())).thenReturn(new NinepayApiClient.TransferResult(
                REQ, "TXN1", 50_000L, 50_500L, 500L, "PROCESSING", "2026-07-27 10:00:00", "ok"));

        PayoutResponse resp = adapter.submitPayout(payout(50_000L));

        assertEquals("PROCESSING", resp.status());
        assertEquals("TXN1", resp.transactionId());
        assertEquals(50_000L, resp.amountVnd());
        assertEquals(500L, resp.feeVnd());
        ArgumentCaptor<NpPayoutEntity> saved = ArgumentCaptor.forClass(NpPayoutEntity.class);
        verify(payouts, times(2)).save(saved.capture()); // pre-wire + post-accept
        assertEquals(PayoutStatus.PROCESSING, saved.getValue().getStatus());
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    @DisplayName("replay of a FINAL payout returns the stored state without touching 9Pay")
    void replay_finalReturnsStored() {
        NpPayoutEntity stored = new NpPayoutEntity(REQ, "970418", "1023020330000", 0,
                "NGUYEN VAN A", 50_000L, "GME PAYOUT", null);
        stored.recordSchemeAccept("TXN1", PayoutStatus.SUCCESS, 500L, 50_500L, null, null);
        when(payouts.findByRequestId(REQ)).thenReturn(Optional.of(stored));

        PayoutResponse resp = adapter.submitPayout(payout(50_000L));

        assertEquals("SUCCESS", resp.status());
        verify(client, never()).transfer(any());
        verify(client, never()).transferInfoByRequestId(anyString(), anyString());
    }

    @Test
    @DisplayName("replay of a NON-final payout refreshes via transfer/info — still no resubmit")
    void replay_nonFinalPolls() {
        NpPayoutEntity stored = new NpPayoutEntity(REQ, "970418", "1023020330000", 0,
                "NGUYEN VAN A", 50_000L, "GME PAYOUT", null);
        stored.recordStatus(PayoutStatus.PENDING);
        when(payouts.findByRequestId(REQ)).thenReturn(Optional.of(stored));
        when(client.transferInfoByRequestId(anyString(), org.mockito.ArgumentMatchers.eq(REQ)))
                .thenReturn(new NinepayApiClient.TransferInfo(
                        REQ, "TXN1", 50_000L, 50_500L, "SUCCESS", "2026-07-27 10:00:00"));

        PayoutResponse resp = adapter.submitPayout(payout(50_000L));

        assertEquals("SUCCESS", resp.status());
        verify(client, never()).transfer(any());
    }

    @Test
    @DisplayName("9Pay 1062 duplicate request_id → adopt existing transfer via poll, never resubmit")
    void duplicate1062_pollsInsteadOfResubmitting() {
        when(client.transfer(any())).thenThrow(new NinepayErrorException("1062", "request_id already taken"));
        when(client.transferInfoByRequestId(anyString(), org.mockito.ArgumentMatchers.eq(REQ)))
                .thenReturn(new NinepayApiClient.TransferInfo(
                        REQ, "TXN9", 50_000L, 50_500L, "SUCCESS", "2026-07-27 09:00:00"));

        PayoutResponse resp = adapter.submitPayout(payout(50_000L));

        assertEquals("SUCCESS", resp.status());
        assertEquals("TXN9", resp.transactionId());
        verify(client, times(1)).transfer(any()); // exactly one submit attempt
    }

    // ------------------------------------------------------------------ timeout → poll gate

    @Test
    @DisplayName("ambiguous timeout on submit → transfer/info poll resolves the true state; no resubmit")
    void timeout_pollGateResolves() {
        when(client.transfer(any())).thenThrow(new NinepayTransportException("read timeout"));
        when(client.transferInfoByRequestId(anyString(), org.mockito.ArgumentMatchers.eq(REQ)))
                .thenReturn(new NinepayApiClient.TransferInfo(
                        REQ, "TXN2", 50_000L, 50_500L, "PROCESSING", "2026-07-27 10:00:00"));

        PayoutResponse resp = adapter.submitPayout(payout(50_000L));

        assertEquals("PROCESSING", resp.status());
        assertEquals("TXN2", resp.transactionId());
        verify(client, times(1)).transfer(any());
        verify(client, times(1)).transferInfoByRequestId(anyString(), anyString());
    }

    @Test
    @DisplayName("timeout + 9Pay confirms not-exists (1021) → UNKNOWN, retry left to the hub")
    void timeout_pollNotFoundLeavesUnknown() {
        when(client.transfer(any())).thenThrow(new NinepayTransportException("read timeout"));
        when(client.transferInfoByRequestId(anyString(), anyString()))
                .thenThrow(new NinepayErrorException("1021", "Transaction not exists"));

        PayoutResponse resp = adapter.submitPayout(payout(50_000L));

        assertEquals("UNKNOWN", resp.status());
        assertNull(resp.transactionId());
        verify(client, times(1)).transfer(any()); // never resubmitted
    }

    @Test
    @DisplayName("timeout + poll ALSO times out (prolonged outage) → UNKNOWN, never auto-failed")
    void timeout_pollTimeoutLeavesUnknown() {
        when(client.transfer(any())).thenThrow(new NinepayTransportException("read timeout"));
        when(client.transferInfoByRequestId(anyString(), anyString()))
                .thenThrow(new NinepayTransportException("read timeout again"));

        PayoutResponse resp = adapter.submitPayout(payout(50_000L));

        assertEquals("UNKNOWN", resp.status());
        verify(client, times(1)).transfer(any());
    }

    @Test
    @DisplayName("definitive business rejection (1024 insufficient balance) → FAILED with the 9Pay code")
    void definitiveRejection_failsWithCode() {
        when(client.transfer(any())).thenThrow(new NinepayErrorException("1024", "Insufficient balance"));

        PayoutResponse resp = adapter.submitPayout(payout(50_000L));

        assertEquals("FAILED", resp.status());
        assertEquals("1024", resp.errorCode());
        verify(client, never()).transferInfoByRequestId(anyString(), anyString());
    }

    // ------------------------------------------------------------------ IPN

    private String ipnBody(String requestId, String code, String status, Long fee, NinepaySigner signAs) {
        ObjectNode node = mapper.createObjectNode();
        node.put("request_id", requestId);
        node.put("partner_id", "GMEPAY");
        node.put("trans_id", "TXN1");
        node.put("request_amount", 50_000L);
        node.put("fee", fee == null ? 500L : fee);
        node.put("transfer_amount", 50_500L);
        node.put("type", "TRANSFER_BANK");
        node.put("status", status);
        node.put("created_at", "2026-07-27 10:00:00");
        node.put("message", "APPROVED BY BANK");
        node.put("approved_at", "2026-07-27 10:00:05");
        if (code != null) {
            node.put("code", code);
        }
        String canonical = NinepaySigner.canonical(requestId, "GMEPAY", "TXN1", 50_000L,
                fee == null ? 500L : fee, 50_500L, "TRANSFER_BANK", status, "2026-07-27 10:00:00");
        node.put("signature", signAs.sign(canonical));
        return node.toString();
    }

    private NpPayoutEntity storedPayout(PayoutStatus status) {
        NpPayoutEntity stored = new NpPayoutEntity(REQ, "970418", "1023020330000", 0,
                "NGUYEN VAN A", 50_000L, "GME PAYOUT", null);
        stored.recordSchemeAccept("TXN1", status, null, null, null, null);
        when(payouts.findByRequestId(REQ)).thenReturn(Optional.of(stored));
        return stored;
    }

    @Test
    @DisplayName("IPN 000: signature verified, event persisted, payout → SUCCESS with fee")
    void ipn_successCode000() {
        NpPayoutEntity stored = storedPayout(PayoutStatus.PROCESSING);

        IpnAck ack = adapter.handleIpn(ipnBody(REQ, "000", "SUCCESS", 500L, ninepaySideSigner));

        assertEquals("RECEIVED", ack.status());
        assertEquals("SUCCESS", ack.payoutStatus());
        assertEquals(PayoutStatus.SUCCESS, stored.getStatus());
        assertEquals("000", stored.getLastIpnCode());
        // T5-4: an APPLIED event claims its event_key via saveAndFlush (the UNIQUE constraint
        // is what makes the replay guard hold under concurrent redeliveries).
        ArgumentCaptor<NpIpnEventEntity> event = ArgumentCaptor.forClass(NpIpnEventEntity.class);
        verify(ipnEvents).saveAndFlush(event.capture());
        assertTrue(event.getValue().isSignatureValid());
        assertEquals("000", event.getValue().getCode());
        assertTrue(event.getValue().isApplied());
        assertNotNull(event.getValue().getEventKey());
        assertNull(event.getValue().getRejectReason());
    }

    @Test
    @DisplayName("IPN 009 after SUCCESS: payout flips to REVERSED, reversed_at stamped, event recorded")
    void ipn_reversal009AfterSuccess() {
        NpPayoutEntity stored = storedPayout(PayoutStatus.SUCCESS);

        IpnAck ack = adapter.handleIpn(ipnBody(REQ, "009", "SUCCESS", 500L, ninepaySideSigner));

        assertEquals("REVERSED", ack.payoutStatus());
        assertEquals(PayoutStatus.REVERSED, stored.getStatus());
        assertNotNull(stored.getReversedAt());
        assertEquals("009", stored.getLastIpnCode());
        ArgumentCaptor<NpIpnEventEntity> event = ArgumentCaptor.forClass(NpIpnEventEntity.class);
        verify(ipnEvents).saveAndFlush(event.capture()); // the reversal audit record
        assertEquals("009", event.getValue().getCode());
        assertTrue(event.getValue().isSignatureValid());
        assertTrue(event.getValue().isApplied());
    }

    @Test
    @DisplayName("IPN 008: payout parked HELD pending merchant confirmation")
    void ipn_held008() {
        NpPayoutEntity stored = storedPayout(PayoutStatus.PROCESSING);

        IpnAck ack = adapter.handleIpn(ipnBody(REQ, "008", "PROCESSING", null, ninepaySideSigner));

        assertEquals("HELD", ack.payoutStatus());
        assertEquals(PayoutStatus.HELD, stored.getStatus());
    }

    @Test
    @DisplayName("IPN with a forged signature: event audited signature_valid=false, push rejected, payout untouched")
    void ipn_forgedSignatureRejected() {
        NpPayoutEntity stored = storedPayout(PayoutStatus.SUCCESS);
        // Forged: signed with OUR key, not 9Pay's — must not verify against 9Pay's public key.
        String forged = ipnBody(REQ, "009", "SUCCESS", 500L, ourSigner);

        ApiException ex = assertThrows(ApiException.class, () -> adapter.handleIpn(forged));

        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
        assertEquals(PayoutStatus.SUCCESS, stored.getStatus()); // NOT reversed by a forgery
        ArgumentCaptor<NpIpnEventEntity> event = ArgumentCaptor.forClass(NpIpnEventEntity.class);
        verify(ipnEvents).save(event.capture()); // still audited
        assertFalse(event.getValue().isSignatureValid());
        verify(payouts, never()).save(any());
    }

    @Test
    @DisplayName("IPN for an unknown request_id: audited and ACKed without a payout update")
    void ipn_unknownRequestIdAudited() {
        IpnAck ack = adapter.handleIpn(ipnBody("SOMEONE-ELSE", "000", "SUCCESS", 500L, ninepaySideSigner));

        assertEquals("RECEIVED", ack.status());
        assertNull(ack.payoutStatus());
        verify(ipnEvents).save(any(NpIpnEventEntity.class));
        verify(payouts, never()).save(any());
    }

    // ------------------------------------------------------------------ T5-4 IPN replay

    /**
     * Makes the guard behave as if the (request_id, trans_id, code) identity of {@code code}
     * has already been applied — i.e. simulates the persisted state left by a first delivery,
     * which is what a replay would run into.
     */
    private void alreadyApplied(String requestId, String code, String schemeCreatedAt) {
        String key = replayGuard.eventKey(requestId, "TXN1", code);
        NpIpnEventEntity applied = new NpIpnEventEntity(requestId, "TXN1", code, "SUCCESS",
                "{}", true);
        applied.markApplied(key);
        applied.setSchemeCreatedAt(schemeCreatedAt);
        when(ipnEvents.findByEventKey(key)).thenReturn(Optional.of(applied));
        when(ipnEvents.findByRequestIdAndAppliedTrueOrderByReceivedAtDesc(requestId))
                .thenReturn(List.of(applied));
    }

    @Test
    @DisplayName("T5-4: a duplicate IPN 000 (same request_id/trans_id/code) is ignored idempotently")
    void ipn_duplicate000IsIgnored() {
        NpPayoutEntity stored = storedPayout(PayoutStatus.SUCCESS);
        alreadyApplied(REQ, "000", "2026-07-27 10:00:00");

        IpnAck ack = adapter.handleIpn(ipnBody(REQ, "000", "SUCCESS", 500L, ninepaySideSigner));

        // ACKed (9Pay must not keep retrying) but nothing was mutated.
        assertEquals("RECEIVED", ack.status());
        assertEquals("SUCCESS", ack.payoutStatus());
        verify(payouts, never()).save(any());
        // The replay is still recorded, flagged, and does NOT claim the event identity.
        ArgumentCaptor<NpIpnEventEntity> event = ArgumentCaptor.forClass(NpIpnEventEntity.class);
        verify(ipnEvents).save(event.capture());
        assertFalse(event.getValue().isApplied());
        assertEquals(IpnRejectReason.DUPLICATE.name(), event.getValue().getRejectReason());
        assertNull(event.getValue().getEventKey());
        verify(ipnEvents, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("T5-4: a REPLAYED 009 does not reverse twice (reversed_at is not re-stamped)")
    void ipn_replayed009DoesNotDoubleReverse() {
        // Payout already reversed by the genuine 009, whose identity is on file.
        NpPayoutEntity stored = storedPayout(PayoutStatus.SUCCESS);
        stored.recordIpn("009", PayoutStatus.REVERSED, "TXN1", 500L, 50_500L, "REVERSED BY BANK");
        java.time.Instant firstReversedAt = stored.getReversedAt();
        assertNotNull(firstReversedAt);
        alreadyApplied(REQ, "009", "2026-07-27 10:00:00");

        IpnAck ack = adapter.handleIpn(ipnBody(REQ, "009", "SUCCESS", 500L, ninepaySideSigner));

        assertEquals("REVERSED", ack.payoutStatus());
        assertEquals(PayoutStatus.REVERSED, stored.getStatus());
        assertEquals(firstReversedAt, stored.getReversedAt(), "reversal must not be re-applied");
        verify(payouts, never()).save(any());
        verify(ipnEvents, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("T5-4: a stale 000 replayed AFTER the 009 cannot un-reverse the payout")
    void ipn_stale000AfterReversalIsRejected() {
        NpPayoutEntity stored = storedPayout(PayoutStatus.SUCCESS);
        stored.recordIpn("009", PayoutStatus.REVERSED, "TXN1", 500L, 50_500L, "REVERSED BY BANK");
        // The 009 is on file with a LATER signed timestamp than the 000 being replayed.
        String key009 = replayGuard.eventKey(REQ, "TXN1", "009");
        NpIpnEventEntity reversal = new NpIpnEventEntity(REQ, "TXN1", "009", "SUCCESS", "{}", true);
        reversal.markApplied(key009);
        reversal.setSchemeCreatedAt("2026-07-27 11:00:00");
        when(ipnEvents.findByEventKey(key009)).thenReturn(Optional.of(reversal));
        when(ipnEvents.findByRequestIdAndAppliedTrueOrderByReceivedAtDesc(REQ))
                .thenReturn(List.of(reversal));

        // A genuine, correctly-signed 000 from 10:00 — captured and resent after the reversal.
        IpnAck ack = adapter.handleIpn(ipnBody(REQ, "000", "SUCCESS", 500L, ninepaySideSigner));

        assertEquals("REVERSED", ack.payoutStatus(), "the reversal must stand");
        assertEquals(PayoutStatus.REVERSED, stored.getStatus());
        verify(payouts, never()).save(any());
        ArgumentCaptor<NpIpnEventEntity> event = ArgumentCaptor.forClass(NpIpnEventEntity.class);
        verify(ipnEvents).save(event.capture());
        assertFalse(event.getValue().isApplied());
        assertEquals(IpnRejectReason.STALE_ORDER.name(), event.getValue().getRejectReason());
    }

    @Test
    @DisplayName("T5-4: the genuine 000 → 009 sequence still reverses (replay guard must not block it)")
    void ipn_genuine000Then009StillReverses() {
        NpPayoutEntity stored = storedPayout(PayoutStatus.PROCESSING);

        // 1. The success IPN lands and is applied.
        IpnAck first = adapter.handleIpn(ipnBody(REQ, "000", "SUCCESS", 500L, ninepaySideSigner));
        assertEquals("SUCCESS", first.payoutStatus());
        assertEquals(PayoutStatus.SUCCESS, stored.getStatus());

        // 2. Persisted state after that first delivery: the 000 identity is on file with the
        //    SAME signed created_at the 009 will carry (9Pay reuses it, and the 009's signed
        //    body is byte-identical to the 000's — only the unsigned `code` differs).
        alreadyApplied(REQ, "000", "2026-07-27 10:00:00");

        // 3. The delayed bank reversal must STILL be applied.
        IpnAck reversal = adapter.handleIpn(ipnBody(REQ, "009", "SUCCESS", 500L, ninepaySideSigner));

        assertEquals("REVERSED", reversal.payoutStatus());
        assertEquals(PayoutStatus.REVERSED, stored.getStatus());
        assertNotNull(stored.getReversedAt());
        verify(ipnEvents, times(2)).saveAndFlush(any(NpIpnEventEntity.class));
    }

    @Test
    @DisplayName("T5-4: an IPN older than the acceptance window is audited but not applied")
    void ipn_beyondAcceptanceWindowIsRejected() {
        NpPayoutEntity stored = storedPayout(PayoutStatus.PROCESSING);
        // A 1-minute window makes the fixture's 2026-07-27 10:00:00 (+07:00 = 03:00Z) stamp
        // three hours old relative to the guard's fixed 06:00Z clock.
        NinepayIpnReplayGuard tightGuard = new NinepayIpnReplayGuard(ipnEvents,
                Clock.fixed(Instant.parse("2026-07-27T06:00:00Z"), ZoneOffset.UTC), 1L);
        NinepaySchemeAdapter tightAdapter = new NinepaySchemeAdapter(
                client, ourSigner, payouts, ipnEvents, mapper, tightGuard);

        IpnAck ack = tightAdapter.handleIpn(ipnBody(REQ, "000", "SUCCESS", 500L, ninepaySideSigner));

        assertEquals("PROCESSING", ack.payoutStatus(), "state unchanged");
        assertEquals(PayoutStatus.PROCESSING, stored.getStatus());
        verify(payouts, never()).save(any());
        ArgumentCaptor<NpIpnEventEntity> event = ArgumentCaptor.forClass(NpIpnEventEntity.class);
        verify(ipnEvents).save(event.capture());
        assertEquals(IpnRejectReason.EXPIRED.name(), event.getValue().getRejectReason());
    }

    @Test
    @DisplayName("T5-4: a late PENDING (code 004) cannot demote an already-SUCCESS payout")
    void ipn_lateStatusRegressionIsRejected() {
        NpPayoutEntity stored = storedPayout(PayoutStatus.SUCCESS);

        IpnAck ack = adapter.handleIpn(ipnBody(REQ, "004", "PENDING", 500L, ninepaySideSigner));

        assertEquals("SUCCESS", ack.payoutStatus());
        assertEquals(PayoutStatus.SUCCESS, stored.getStatus());
        verify(payouts, never()).save(any());
        ArgumentCaptor<NpIpnEventEntity> event = ArgumentCaptor.forClass(NpIpnEventEntity.class);
        verify(ipnEvents).save(event.capture());
        assertEquals(IpnRejectReason.STATUS_REGRESSION.name(), event.getValue().getRejectReason());
    }

    @Test
    @DisplayName("T5-4: a signature-invalid IPN never claims the event identity (9Pay may retry)")
    void ipn_signatureFailureLeavesTheIdentityFree() {
        storedPayout(PayoutStatus.PROCESSING);
        String forged = ipnBody(REQ, "000", "SUCCESS", 500L, ourSigner);

        assertThrows(ApiException.class, () -> adapter.handleIpn(forged));

        ArgumentCaptor<NpIpnEventEntity> event = ArgumentCaptor.forClass(NpIpnEventEntity.class);
        verify(ipnEvents).save(event.capture());
        assertNull(event.getValue().getEventKey(),
                "a rejected event must not occupy the key a genuine redelivery needs");
        assertEquals(IpnRejectReason.SIGNATURE_INVALID.name(), event.getValue().getRejectReason());

        // ...and the genuine redelivery of the SAME identity is then applied normally.
        IpnAck ack = adapter.handleIpn(ipnBody(REQ, "000", "SUCCESS", 500L, ninepaySideSigner));
        assertEquals("SUCCESS", ack.payoutStatus());
    }
}
