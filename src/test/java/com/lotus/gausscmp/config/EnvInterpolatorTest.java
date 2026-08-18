package com.lotus.gausscmp.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EnvInterpolatorTest {

    @Test
    void resolvesSimpleVar() {
        String result = EnvInterpolator.interpolate("prefix-${PATH}-suffix");
        assertThat(result).startsWith("prefix-").endsWith("-suffix").isNotEqualTo("prefix-${PATH}-suffix");
    }

    @Test
    void resolvesVarWithDefault() {
        String result = EnvInterpolator.interpolate("${GAUSSCMP_NONEXIST:-5432}");
        assertThat(result).isEqualTo("5432");
    }

    @Test
    void throwsOnMissingRequiredVar() {
        assertThatThrownBy(() -> EnvInterpolator.interpolate("${GAUSSCMP_DEFINITELY_MISSING}"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("GAUSSCMP_DEFINITELY_MISSING");
    }

    @Test
    void leavesPlainTextUntouched() {
        assertThat(EnvInterpolator.interpolate("plain-text")).isEqualTo("plain-text");
    }
}
