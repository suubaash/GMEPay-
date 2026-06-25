package com.gme.pay.scheme.zeropay.transport;

import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.CHARSET;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_AMOUNT;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_MERCHANT_FEE;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_MERCHANT_ID;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_MESSAGE_TYPE;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_RESPONSE_CODE;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_SYSTEM_CODE;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_TXN_DIVISION;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.F_TXN_UNIQUE_NO;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.MPM_DYNAMIC_420000;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.MSG_PAYMENT_RESP;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.ONLINE_MESSAGE_LENGTH;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.SYSTEM_CODE;
import static com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMessages.TXN_DIV_MPM_DYNAMIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gme.pay.scheme.zeropay.jeonmun.JeonmunCodec;
import com.gme.pay.scheme.zeropay.jeonmun.ZeroPayFrame;
import com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMpm420000;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Verifies the REAL ZeroPay 전문/TCP transport (Step 8) end-to-end against a loopback 전문 server:
 * a 0200 MPM (420000) request is framed + sent over a socket, a 전문-speaking peer decodes it and
 * replies with a framed 0210, and the transport decodes the approval + merchant fee. This is the
 * round-trip proof that the socket + {@link ZeroPayFrame} framing + codec interoperate over the wire.
 */
class ZeroPayTcpTransportTest {

    private static ZeroPayMpm420000.PaymentRequest sampleRequest() {
        return new ZeroPayMpm420000.PaymentRequest(
                "PTNRTXN0001", "001",
                50_000L, 0L,
                "01", "QRSERIAL0001", "ABCD",
                "MERCHANT0001", "TERMINAL0001",
                true, "00000001",
                LocalDate.of(2026, 6, 26),
                LocalDateTime.of(2026, 6, 26, 9, 30, 0));
    }

    /**
     * Stand up a one-shot loopback server that decodes the request 전문, captures key fields, and
     * replies with a framed 0210 (response code 000, merchant fee 150). Returns the server socket
     * (caller closes) and exposes the decoded request via {@code captured}.
     */
    private static Thread startJeonmunServer(ServerSocket server,
                                             AtomicReference<Map<Integer, String>> captured) {
        Thread t = new Thread(() -> {
            try (Socket conn = server.accept();
                 InputStream in = conn.getInputStream();
                 OutputStream out = conn.getOutputStream()) {
                byte[] reqBody = ZeroPayFrame.readBody(in);
                Map<Integer, String> req =
                        JeonmunCodec.decode(MPM_DYNAMIC_420000, reqBody, CHARSET, ONLINE_MESSAGE_LENGTH);
                captured.set(req);

                Map<Integer, String> resp = new HashMap<>();
                resp.put(F_SYSTEM_CODE, SYSTEM_CODE);
                resp.put(F_MESSAGE_TYPE, MSG_PAYMENT_RESP);       // 0210
                resp.put(F_TXN_DIVISION, TXN_DIV_MPM_DYNAMIC);
                resp.put(F_RESPONSE_CODE, "000");                  // approved
                resp.put(F_TXN_UNIQUE_NO, req.get(F_TXN_UNIQUE_NO)); // echo
                resp.put(F_MERCHANT_FEE, "150");                   // scheme-returned fee (field 41)
                byte[] respBody =
                        JeonmunCodec.encode(MPM_DYNAMIC_420000, resp, CHARSET, ONLINE_MESSAGE_LENGTH);
                out.write(ZeroPayFrame.frame(respBody));
                out.flush();
            } catch (Exception e) {
                // test thread — surface via captured staying null
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Test
    void submitMpm_roundTripsAJeonmunOverTcp() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            AtomicReference<Map<Integer, String>> captured = new AtomicReference<>();
            startJeonmunServer(server, captured);

            ZeroPayTcpTransport transport = new ZeroPayTcpTransport(
                    "127.0.0.1", server.getLocalPort(), 2000, 2000);
            ZeroPayMpm420000.Response resp = transport.submitMpm(sampleRequest());

            // The server received and decoded our 0200 request correctly.
            Map<Integer, String> req = captured.get();
            assertEquals(SYSTEM_CODE, req.get(F_SYSTEM_CODE).trim());
            // F_AMOUNT is N-type: zero-padded to width 12 ("000000050000"), parses cleanly.
            assertEquals(50_000L, Long.parseLong(req.get(F_AMOUNT).trim()));
            assertEquals("MERCHANT0001", req.get(F_MERCHANT_ID).trim());
            assertEquals("PTNRTXN0001", req.get(F_TXN_UNIQUE_NO).trim());

            // ...and we decoded its 0210 response: approved + scheme-returned merchant fee.
            assertEquals("0210", resp.messageType());
            assertEquals("000", resp.responseCode());
            assertTrue(resp.approved(), "response code 000 must decode as approved");
            assertEquals(150L, resp.merchantFeeKrw());
            assertEquals("PTNRTXN0001", resp.txnUniqueNo().trim());
        }
    }

    @Test
    void exchange_throwsTransportException_whenPeerUnreachable() {
        // Nothing listening on this port → connect refused must surface as a transport failure,
        // never silently as an approval.
        ZeroPayTcpTransport transport = new ZeroPayTcpTransport("127.0.0.1", 1, 500, 500);
        assertThrows(ZeroPayTransportException.class,
                () -> transport.submitMpm(sampleRequest()));
    }
}
