package com.chitthi.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RatelimitPropertiesTest {

    @Test
    void zeroDisablesTheFilter() {
        assertThat(new RatelimitProperties(0).enabled()).isFalse();
    }

    @Test
    void aPositiveLimitEnablesTheFilter() {
        assertThat(new RatelimitProperties(60).enabled()).isTrue();
    }
}
