package com.chitthi.usage;

import com.chitthi.sarvam.SarvamResilience;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Per-unit price estimates, all sourced from the requirements doc's
 * "Approximate cost per 10-page letter" section - Vision at ~₹0.5/page,
 * Bulbul at ~₹30 per 10,000 characters (₹0.003/char), and Translate priced
 * comparably per character. These are estimates for FR10's cost ledger, not
 * Sarvam's actual billed rate, which isn't published per unit.
 */
@ConfigurationProperties(prefix = "chitthi.usage.pricing")
public record UsagePricing(BigDecimal visionPerPage, BigDecimal translatePerChar, BigDecimal ttsPerChar) {

    /** @param endpoint one of {@link SarvamResilience}'s endpoint name constants */
    public BigDecimal costFor(String endpoint, int units) {
        BigDecimal rate = switch (endpoint) {
            case SarvamResilience.VISION_SUBMIT -> visionPerPage;
            case SarvamResilience.TRANSLATE -> translatePerChar;
            case SarvamResilience.TTS -> ttsPerChar;
            default -> throw new IllegalArgumentException("Unknown endpoint: " + endpoint);
        };
        return rate.multiply(BigDecimal.valueOf(units));
    }
}
