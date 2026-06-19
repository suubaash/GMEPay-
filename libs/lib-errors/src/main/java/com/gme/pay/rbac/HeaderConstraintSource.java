package com.gme.pay.rbac;

import com.gme.pay.rbac.constraint.Constraint;
import com.gme.pay.rbac.constraint.ConstraintType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default {@link ConstraintSource}: decodes the constraints the edge stamped into the
 * {@link RbacHeaders#CONSTRAINTS} header. Keeps downstream services free of any DB hop —
 * the gateway resolved the principal's effective permissions <em>and</em> their attached
 * constraints once, at the edge, and stamped both.
 *
 * <p><b>Wire format</b> (dependency-free, no Jackson — the engine reads a flat String map):
 * <pre>
 *   TYPE:k=v;k=v|TYPE:k=v
 * </pre>
 * {@code |} separates constraints, {@code :} separates the {@link ConstraintType} from its
 * config, {@code ;} separates config pairs, {@code =} separates key from value, and {@code ,}
 * separates members of a set value. Example:
 * <pre>
 *   TIME:timezone=Asia/Tokyo;startHour=9;endHour=17;days=MON,TUE,WED,THU,FRI|LOCATION:countries=JP|AMOUNT:maxAmount=1000
 * </pre>
 * Malformed or unknown-type segments are skipped (fail-soft on decode); the engine itself
 * fails closed on an unknown {@link ConstraintType} it is asked to evaluate.
 */
public class HeaderConstraintSource implements ConstraintSource {

    @Override
    public List<Constraint> constraintsFor(String permission, PermissionContext ctx,
                                           HttpServletRequest request) {
        return parse(request.getHeader(RbacHeaders.CONSTRAINTS));
    }

    /** Parse the encoded header into constraints. Package-private for direct unit testing. */
    static List<Constraint> parse(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        List<Constraint> out = new ArrayList<>();
        for (String segment : encoded.split("\\|")) {
            if (segment.isBlank()) {
                continue;
            }
            int colon = segment.indexOf(':');
            String typeToken = (colon >= 0 ? segment.substring(0, colon) : segment).trim();
            ConstraintType type = parseType(typeToken);
            if (type == null) {
                continue; // unknown type token — skip (fail-soft on decode)
            }
            Map<String, String> config = new LinkedHashMap<>();
            if (colon >= 0 && colon < segment.length() - 1) {
                for (String pair : segment.substring(colon + 1).split(";")) {
                    int eq = pair.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    config.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
            out.add(new Constraint(type, config));
        }
        return out;
    }

    private static ConstraintType parseType(String token) {
        for (ConstraintType t : ConstraintType.values()) {
            if (t.name().equalsIgnoreCase(token)) {
                return t;
            }
        }
        return null;
    }
}
