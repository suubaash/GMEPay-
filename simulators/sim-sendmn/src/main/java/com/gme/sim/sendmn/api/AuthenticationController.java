package com.gme.sim.sendmn.api;

import com.gme.sim.sendmn.config.SimSendmnProperties;
import com.gme.sim.sendmn.token.TokenStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * POST /api/Authentication — credentials in HEADERS ({@code Username}/{@code AgentCode}/
 * {@code AuthKey}, no body), response is <b>plain JSON</b> (not the encryptedData
 * envelope): {@code {code, message, detail:{token, note, processId}}}, code "0" on
 * success. Error codes per the doc: S201/S202 missing header, S101 bad AuthKey,
 * S103 bad AgentCode/Username. Token valid 90 minutes.
 */
@RestController
public class AuthenticationController {

    private final SimSendmnProperties props;
    private final TokenStore tokenStore;

    public AuthenticationController(SimSendmnProperties props, TokenStore tokenStore) {
        this.props = props;
        this.tokenStore = tokenStore;
    }

    @PostMapping("/api/Authentication")
    public ResponseEntity<Map<String, Object>> authenticate(
            @RequestHeader(name = "Username", required = false) String username,
            @RequestHeader(name = "AgentCode", required = false) String agentCode,
            @RequestHeader(name = "AuthKey", required = false) String authKey) {

        if (username == null || username.isBlank()) {
            return error("S201", "Username is missing! Please put Username in the header.");
        }
        if (agentCode == null || agentCode.isBlank()) {
            return error("S202", "AgentCode is missing! Please put AgentCode in the header.");
        }
        if (!props.getUsername().equals(username) || !props.getAgentCode().equals(agentCode)) {
            return error("S103", "AgentCode or Username is invalid!");
        }
        if (authKey == null || !props.getAuthKey().equals(authKey)) {
            return error("S101", "AuthKey is invalid!");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("token", tokenStore.issue());
        detail.put("note", "Token will only be Valid for 90 minutes.");
        detail.put("processId", UUID.randomUUID().toString());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "0");
        body.put("message", "Success");
        body.put("detail", detail);
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<Map<String, Object>> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return ResponseEntity.ok(body);
    }
}
