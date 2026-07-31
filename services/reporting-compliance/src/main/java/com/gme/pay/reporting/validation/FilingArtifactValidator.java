package com.gme.pay.reporting.validation;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Local format validation of a generated regulatory artifact.
 *
 * <h2>Scope — deliberately narrow (GAP T5-2)</h2>
 * A pass means only: <i>the file exists, is non-empty, and contains no unresolved
 * placeholder token</i>. It does <b>not</b> mean the layout has been confirmed by the
 * authority, and it never implies transmission. This is why
 * {@link com.gme.pay.reporting.persistence.ReportFiling.Status#VALIDATED} is a separate
 * state from {@code TRANSMITTED} and {@code ACKNOWLEDGED}.
 *
 * <p>The placeholder check is load-bearing today: BOK FX files still carry
 * {@code TODO_OI03} in the two mandatory code columns (bok_txn_code,
 * bok_fx_reporting_category) because the BOK code mapping is externally gated, so a BOK
 * artifact correctly fails local validation and can never be reported as VALIDATED.
 */
@Component
public class FilingArtifactValidator {

    /** Prefix marking an unresolved, externally-gated field value inside an artifact. */
    public static final String PLACEHOLDER_TOKEN = "TODO_";

    /**
     * Result of validating one artifact.
     *
     * @param valid  true when the artifact passed all local checks
     * @param reason why it failed; null when {@code valid}
     */
    public record Outcome(boolean valid, String reason) {

        public static Outcome pass() {
            return new Outcome(true, null);
        }

        public static Outcome fail(String reason) {
            return new Outcome(false, reason);
        }
    }

    /** Validates the artifact at {@code path}. Never throws — an I/O error is a failure. */
    public Outcome validate(Path path) {
        if (path == null) {
            return Outcome.fail("no artifact path recorded");
        }
        if (!Files.isRegularFile(path)) {
            return Outcome.fail("artifact not found: " + path);
        }
        try {
            if (Files.size(path) == 0L) {
                return Outcome.fail("artifact is empty: " + path);
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.contains(PLACEHOLDER_TOKEN)) {
                return Outcome.fail("artifact contains unresolved placeholder token '"
                        + PLACEHOLDER_TOKEN + "' — mandatory field values are still "
                        + "externally gated: " + path);
            }
            return Outcome.pass();
        } catch (IOException e) {
            return Outcome.fail("artifact unreadable: " + path + " (" + e.getMessage() + ")");
        }
    }
}
