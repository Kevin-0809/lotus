package com.lotus.gausscmp.metadata;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DefinitionNormalizerTest {

    @Test
    void collapsesWhitespace() {
        assertThat(DefinitionNormalizer.normalize("  CHECK   (x  >  0) "))
            .isEqualTo("check (x > 0)");
    }

    @Test
    void unifiesDefaultValueAliases() {
        assertThat(DefinitionNormalizer.normalizeDefaultValue("now()")).isEqualTo("current_timestamp");
        assertThat(DefinitionNormalizer.normalizeDefaultValue(" NOW ( ) ")).isEqualTo("current_timestamp");
    }

    @Test
    void stripsQuotesConsistently() {
        assertThat(DefinitionNormalizer.normalizeDefaultValue("'abc'")).isEqualTo("abc");
        assertThat(DefinitionNormalizer.normalizeDefaultValue("abc")).isEqualTo("abc");
    }

    @Test
    void normalizesNull() {
        assertThat(DefinitionNormalizer.normalizeDefaultValue("NULL")).isEqualTo("null");
        assertThat(DefinitionNormalizer.normalizeDefaultValue(null)).isNull();
    }
}
