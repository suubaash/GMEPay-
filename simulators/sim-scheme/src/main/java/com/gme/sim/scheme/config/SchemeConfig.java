package com.gme.sim.scheme.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SchemeConfig {

    private final SchemeProfile profile;

    /** Spring 6 two-constructor rule: annotate the @Value constructor. */
    @Autowired
    public SchemeConfig(
            @Value("${gmepay.sim.scheme.profile:KHQR}") String profileName) {
        this.profile = SchemeProfile.valueOf(profileName.toUpperCase());
    }

    public SchemeProfile getProfile() {
        return profile;
    }
}
