package com.gme.sim.sendmn.scenario;

import com.gme.sim.sendmn.config.SimSendmnProperties;
import org.springframework.stereotype.Component;

/**
 * Runtime-mutable failure toggles for adapter resilience testing — seeded from
 * properties, flipped via POST /sim/scenario, restored via POST /sim/scenario/reset.
 */
@Component
public class ScenarioState {

    private final SimSendmnProperties props;

    private volatile boolean forceError304;
    private volatile boolean forceError307;
    private volatile boolean neverApprove;
    private volatile long delayMillis;
    private volatile int approveAfterPolls;

    public ScenarioState(SimSendmnProperties props) {
        this.props = props;
        reset();
    }

    public final void reset() {
        forceError304 = props.isForceError304();
        forceError307 = props.isForceError307();
        neverApprove = props.isNeverApprove();
        delayMillis = props.getDelayMillis();
        approveAfterPolls = props.getApproveAfterPolls();
    }

    /** Sleeps for the configured artificial latency (capped at 60s), if any. */
    public void applyDelay() {
        long millis = Math.min(delayMillis, 60_000L);
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isForceError304() { return forceError304; }
    public void setForceError304(boolean v) { this.forceError304 = v; }

    public boolean isForceError307() { return forceError307; }
    public void setForceError307(boolean v) { this.forceError307 = v; }

    public boolean isNeverApprove() { return neverApprove; }
    public void setNeverApprove(boolean v) { this.neverApprove = v; }

    public long getDelayMillis() { return delayMillis; }
    public void setDelayMillis(long v) { this.delayMillis = v; }

    public int getApproveAfterPolls() { return approveAfterPolls; }
    public void setApproveAfterPolls(int v) { this.approveAfterPolls = v; }
}
