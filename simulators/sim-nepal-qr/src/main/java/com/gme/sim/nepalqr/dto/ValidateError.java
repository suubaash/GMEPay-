package com.gme.sim.nepalqr.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validate-API error envelope: {"error":{"code","message","detail"}}.
 */
public class ValidateError {
    public Error error;

    public static class Error {
        public String code;
        public String message;
        public Map<String, Object> detail = new LinkedHashMap<>();
    }

    public static ValidateError of(String code, String message) {
        ValidateError e = new ValidateError();
        e.error = new Error();
        e.error.code = code;
        e.error.message = message;
        return e;
    }
}
