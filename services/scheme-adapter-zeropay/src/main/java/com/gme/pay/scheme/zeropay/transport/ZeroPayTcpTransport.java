package com.gme.pay.scheme.zeropay.transport;

import com.gme.pay.scheme.zeropay.jeonmun.ZeroPayFrame;
import com.gme.pay.scheme.zeropay.jeonmun.ZeroPayMpm420000;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real ZeroPay 전문-over-TCP transport (Step 8 — go-live blocker). The KFTC ZeroPay online API is a
 * synchronous request/response exchange of 1,000-byte fixed-length 전문 over a TCP socket (EUC-KR),
 * NOT REST — see {@code Documentation/ZeroPay-API-Integration-Parameters.md}. The
 * {@link ZeroPayMpm420000} codec already encodes/decodes the body; this class adds the missing
 * socket + {@link ZeroPayFrame} framing.
 *
 * <p>Flow: encode the 0200 request body → wrap in the 7-byte transport frame → open a socket →
 * write → read the framed 0210 response → decode. One connection per exchange (KFTC sessions are
 * short-lived); connection pooling + mTLS client certs are layered on at production cutover.
 *
 * <p>The adapter selects this over the REST/sim path via {@code adapter.zeropay.transport=TCP}
 * (default {@code REST}). With no public ZeroPay endpoint available pre-go-live, this is verified
 * against a loopback 전문 server in tests; the production host/port/mTLS are config.
 */
public class ZeroPayTcpTransport {

    private static final Logger log = LoggerFactory.getLogger(ZeroPayTcpTransport.class);

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public ZeroPayTcpTransport(String host, int port, int connectTimeoutMs, int readTimeoutMs) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    /**
     * Send a pre-encoded 전문 body and return the decoded response body — the codec-agnostic
     * transport primitive. Frames the request, opens a socket, writes, reads one framed response.
     *
     * @throws ZeroPayTransportException on connect/IO/timeout failure (the caller maps it to a
     *         scheme-unavailable outcome — a transport failure is never silently treated as approval)
     */
    public byte[] exchange(byte[] requestBody) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            try (OutputStream out = socket.getOutputStream();
                 InputStream in = socket.getInputStream()) {
                out.write(ZeroPayFrame.frame(requestBody));
                out.flush();
                return ZeroPayFrame.readBody(in);
            }
        } catch (IOException e) {
            log.error("ZeroPay TCP exchange to {}:{} failed: {}", host, port, e.toString());
            throw new ZeroPayTransportException(
                    "ZeroPay TCP exchange to " + host + ":" + port + " failed: " + e.getMessage(), e);
        }
    }

    /** Encode a 0200 MPM (420000) payment request, exchange it, and decode the 0210 response. */
    public ZeroPayMpm420000.Response submitMpm(ZeroPayMpm420000.PaymentRequest request) {
        byte[] responseBody = exchange(ZeroPayMpm420000.encodePayment(request));
        return ZeroPayMpm420000.decodeResponse(responseBody);
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }
}
