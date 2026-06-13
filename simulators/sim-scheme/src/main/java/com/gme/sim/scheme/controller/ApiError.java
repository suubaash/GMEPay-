package com.gme.sim.scheme.controller;

/**
 * Uniform error envelope returned by the scheme simulator.
 */
public record ApiError(String code, String message) {}
