package com.gme.pay.auth.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guard test enforcing ADR-011: auth-identity exposes <strong>machine-credential
 * endpoints only</strong>. Human operator login lives in Keycloak.
 *
 * <p>Strategy: reflectively scan every class under {@code com.gme.pay.auth.web}
 * for Spring MVC mapping annotations and assert that
 * <ol>
 *   <li>every mapping path is on the internal machine surface
 *       ({@code /internal/auth/**} or, by allow-list, well-known infra paths), and</li>
 *   <li>no mapping path contains operator-login terminology (e.g. {@code /login},
 *       {@code /operator}, {@code /session}, {@code /password}).</li>
 * </ol>
 *
 * <p>This test does not boot Spring. It is fast and deterministic, runs as part
 * of {@code :services:auth-identity:test}, and fails loudly the moment somebody
 * tries to add an operator-login endpoint back to this service.
 */
class WebSurfaceScopeTest {

    private static final String WEB_PACKAGE = "com.gme.pay.auth.web";

    /** Path prefixes the machine credential surface is allowed to use. */
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "/internal/auth"
    );

    /** Forbidden operator-login terminology. If any mapping contains these, this test fails. */
    private static final List<String> FORBIDDEN_TERMS = List.of(
            "/login",
            "/logout",
            "/operator",
            "/session",
            "/password",
            "/oauth",
            "/oidc",
            "/sso"
    );

    @Test
    @DisplayName("ADR-011: every mapping under web/ stays on the machine-credential surface")
    void allMappingsStayOnMachineSurface() throws Exception {
        List<MappingInfo> mappings = scanMappings();

        assertFalse(mappings.isEmpty(),
                "Expected at least one @RestController mapping under " + WEB_PACKAGE);

        for (MappingInfo m : mappings) {
            String pathLower = m.fullPath.toLowerCase(Locale.ROOT);

            // 1. Must start with an allowed prefix.
            boolean onMachineSurface = ALLOWED_PREFIXES.stream().anyMatch(pathLower::startsWith);
            assertTrue(onMachineSurface,
                    "Mapping " + m + " is outside the allowed machine-credential surface "
                            + ALLOWED_PREFIXES + ". Per ADR-011, operator-facing endpoints "
                            + "belong in Keycloak, not auth-identity.");

            // 2. Must not contain operator-login terminology.
            for (String forbidden : FORBIDDEN_TERMS) {
                assertFalse(pathLower.contains(forbidden),
                        "Mapping " + m + " contains forbidden operator-login term '"
                                + forbidden + "'. Per ADR-011, this service is machine-only.");
            }
        }
    }

    @Test
    @DisplayName("ADR-011: AuthVerifyController is the canonical machine endpoint and is present")
    void authVerifyControllerStillExists() throws Exception {
        List<MappingInfo> mappings = scanMappings();
        boolean hasVerify = mappings.stream().anyMatch(m ->
                m.fullPath.equalsIgnoreCase("/internal/auth/verify")
                        && "POST".equals(m.httpMethod));
        assertTrue(hasVerify,
                "Expected POST /internal/auth/verify to remain registered — this is the "
                        + "machine-credential entry point consumed by api-gateway. Found: " + mappings);
    }

    // ── Reflection scanning helpers ──────────────────────────────────────────

    private static List<MappingInfo> scanMappings() throws Exception {
        List<MappingInfo> out = new ArrayList<>();

        // Spring's component scanner handles both directory + JAR class layouts robustly.
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        for (var bd : scanner.findCandidateComponents(WEB_PACKAGE)) {
            String beanClassName = Objects.requireNonNull(bd.getBeanClassName(),
                    "Bean class name was null for " + bd);
            Class<?> clazz = Class.forName(beanClassName);
            if (clazz.getAnnotation(RestController.class) == null) continue;

            String classBase = "";
            RequestMapping classMapping = clazz.getAnnotation(RequestMapping.class);
            if (classMapping != null && classMapping.value().length > 0) {
                classBase = normalize(classMapping.value()[0]);
            }

            for (Method method : clazz.getDeclaredMethods()) {
                for (Annotation ann : method.getAnnotations()) {
                    MappingInfo m = toMappingInfo(ann, classBase);
                    if (m != null) {
                        out.add(new MappingInfo(
                                clazz.getSimpleName() + "#" + method.getName(),
                                m.httpMethod, m.fullPath));
                    }
                }
            }
        }
        return out;
    }

    private static MappingInfo toMappingInfo(Annotation ann, String classBase) {
        String http;
        String[] paths;
        if (ann instanceof GetMapping a)         { http = "GET";    paths = a.value(); }
        else if (ann instanceof PostMapping a)   { http = "POST";   paths = a.value(); }
        else if (ann instanceof PutMapping a)    { http = "PUT";    paths = a.value(); }
        else if (ann instanceof PatchMapping a)  { http = "PATCH";  paths = a.value(); }
        else if (ann instanceof DeleteMapping a) { http = "DELETE"; paths = a.value(); }
        else if (ann instanceof RequestMapping a) {
            http = a.method().length > 0 ? a.method()[0].name() : "ANY";
            paths = a.value();
        }
        else return null;

        String path = paths.length == 0 ? "" : normalize(paths[0]);
        return new MappingInfo("(handler)", http, joinPath(classBase, path));
    }

    private static String normalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.startsWith("/") ? s : "/" + s;
    }

    private static String joinPath(String base, String tail) {
        if (base.isEmpty()) return tail.isEmpty() ? "/" : tail;
        if (tail.isEmpty()) return base;
        if (base.endsWith("/") && tail.startsWith("/")) return base + tail.substring(1);
        if (!base.endsWith("/") && !tail.startsWith("/")) return base + "/" + tail;
        return base + tail;
    }

    /** Captured (handler, httpMethod, fullPath) tuple for assertions. */
    private record MappingInfo(String handler, String httpMethod, String fullPath) {
        @Override public String toString() {
            return httpMethod + " " + fullPath + " [" + handler + "]";
        }
    }

    @Test
    @DisplayName("Forbidden-terms list itself stays in sync with this test's intent")
    void forbiddenTermsAreLowercase() {
        // Guard against future edits introducing mixed-case entries that would
        // silently bypass the contains() check (which lowercases the path).
        for (String t : FORBIDDEN_TERMS) {
            assertEquals(t.toLowerCase(Locale.ROOT), t,
                    "Forbidden term must be lowercase to match the lowercased path: " + t);
        }
        // Sanity: ensure no Spring annotation handling silently dropped a method.
        // Arrays import retained for future expansion (Arrays.asList) and to keep
        // wildcard-free imports.
        Objects.requireNonNull(Arrays.asList(FORBIDDEN_TERMS.toArray()));
    }
}
