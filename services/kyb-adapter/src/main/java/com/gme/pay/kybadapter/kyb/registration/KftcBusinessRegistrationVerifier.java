package com.gme.pay.kybadapter.kyb.registration;

import com.gme.pay.kyb.KybSubject;
import com.gme.pay.kybadapter.kyb.BusinessRegistrationVerifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Production {@link BusinessRegistrationVerifier} placeholder for KFTC's
 * 사업자등록 진위확인 (business-registration authenticity check) rail. Activated
 * by {@code gmepay.kyb.biz-reg.provider=kftc} — the same property-selected
 * adapter wiring as config-registry's {@code KftcVerificationAdapter} — and
 * currently fails loudly: GME's production certificate for the KFTC / NTS
 * channel has not been provisioned yet, and a silent fake here would let an
 * environment believe a partner's business registration was authenticated when
 * it was not.
 *
 * <p>When the certificate lands this class gains the HTTP client + signing
 * config (the same shape as {@code OctaKybAdapter}); the port, the orchestration
 * write path, the persisted run log and the event roster all stay unchanged
 * (the verdict is already modelled as {@code BizRegStatus.VERIFIED}).
 */
@Component
@ConditionalOnProperty(name = "gmepay.kyb.biz-reg.provider", havingValue = "kftc")
public class KftcBusinessRegistrationVerifier implements BusinessRegistrationVerifier {

    @Override
    public BizRegResult verify(KybSubject subject) {
        throw new UnsupportedOperationException(
                "KFTC 사업자등록 진위확인 production certificate pending");
    }
}
