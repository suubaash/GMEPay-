package com.gme.pay.payment.sandbox;

import com.gme.pay.payment.persistence.SandboxE2eRunEntity;
import com.gme.pay.payment.persistence.SandboxE2eStepEntity;
import com.gme.pay.payment.sandbox.dto.E2eRunDetail;
import com.gme.pay.payment.sandbox.dto.E2eRunSummary;

import java.math.BigDecimal;
import java.util.List;

/** Maps {@code sandbox_e2e_*} entities to the runner's API DTOs. */
public final class SandboxE2eMapper {

    private SandboxE2eMapper() {
    }

    public static E2eRunDetail toDetail(SandboxE2eRunEntity run) {
        List<E2eRunDetail.Step> steps = run.getSteps().stream()
                .map(SandboxE2eMapper::toStep)
                .toList();
        return new E2eRunDetail(
                run.getId(),
                str(run.getCreatedAt()),
                run.getCountry(),
                run.getPartner(),
                amount(run.getAmount()),
                run.getCurrency(),
                run.getMpmType(),
                run.getStatus(),
                run.getFailedStep(),
                run.getStepCount() != null ? run.getStepCount() : steps.size(),
                steps);
    }

    public static E2eRunSummary toSummary(SandboxE2eRunEntity run) {
        return new E2eRunSummary(
                run.getId(),
                str(run.getCreatedAt()),
                run.getCountry(),
                run.getPartner(),
                amount(run.getAmount()),
                run.getCurrency(),
                run.getMpmType(),
                run.getStatus(),
                run.getFailedStep(),
                run.getStepCount() != null ? run.getStepCount() : 0);
    }

    private static E2eRunDetail.Step toStep(SandboxE2eStepEntity s) {
        return new E2eRunDetail.Step(
                s.getSeq() != null ? s.getSeq() : 0,
                s.getName(),
                s.getStatus(),
                s.getDetail(),
                s.getLatencyMs(),
                s.getHttpStatus());
    }

    private static String str(java.time.Instant instant) {
        return instant != null ? instant.toString() : null;
    }

    private static String amount(BigDecimal amount) {
        return amount != null ? amount.toPlainString() : null;
    }
}
