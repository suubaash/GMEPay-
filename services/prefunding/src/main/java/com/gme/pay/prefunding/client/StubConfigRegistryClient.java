package com.gme.pay.prefunding.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default in-memory {@link ConfigRegistryClient}: records every suspension proposal (so
 * tests can assert the breach hook fired) and logs it. Active whenever
 * {@code gmepay.config-registry.client} is not {@code rest} — the service boots standalone
 * for local dev and tests without a config-registry instance.
 */
@Component
public class StubConfigRegistryClient implements ConfigRegistryClient {

    private static final Logger log = LoggerFactory.getLogger(StubConfigRegistryClient.class);

    /** One recorded suspension proposal, mirroring the wire shape the REST client sends. */
    public record Proposal(String aggregateType, String aggregateId,
                           String proposedBy, String payloadJsonb, String reason) { }

    private final List<Proposal> proposals = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void proposePartnerSuspension(String partnerCode, String reason) {
        Proposal proposal = new Proposal(
                "partner", partnerCode, SYSTEM_PROPOSER,
                "{\"status\":\"SUSPENDED\"}", reason);
        proposals.add(proposal);
        log.info("stub config-registry: recorded suspension proposal for partner={} reason={}",
                partnerCode, reason);
    }

    /** Snapshot of every proposal recorded so far (insertion order). */
    public List<Proposal> proposals() {
        synchronized (proposals) {
            return List.copyOf(proposals);
        }
    }

    /** Test hook: forget all recorded proposals. */
    public void clear() {
        proposals.clear();
    }
}
