package com.lotus.gausscmp.metadata;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TypeNormalizerTest {

    @Test
    void normalizesIntAliases() {
        assertThat(TypeNormalizer.normalize("int4")).isEqualTo("integer");
        assertThat(TypeNormalizer.normalize("int8")).isEqualTo("bigint");
        assertThat(TypeNormalizer.normalize("int")).isEqualTo("integer");
        assertThat(TypeNormalizer.normalize("smallint")).isEqualTo("smallint");
    }

    @Test
    void normalizesVarchar() {
        assertThat(TypeNormalizer.normalize("varchar(10)")).isEqualTo("character varying(10)");
        assertThat(TypeNormalizer.normalize("character varying")).isEqualTo("character varying");
    }

    @Test
    void normalizesArray() {
        assertThat(TypeNormalizer.normalize("int4[]")).isEqualTo("integer[]");
        assertThat(TypeNormalizer.normalize("integer[]")).isEqualTo("integer[]");
    }

    @Test
    void keepsFullTimestamp() {
        assertThat(TypeNormalizer.normalize("timestamp without time zone"))
            .isEqualTo("timestamp without time zone");
        assertThat(TypeNormalizer.normalize("timestamptz"))
            .isEqualTo("timestamp with time zone");
    }

    @Test
    void preservesNumericPrecision() {
        assertThat(TypeNormalizer.normalize("numeric(10,2)")).isEqualTo("numeric(10,2)");
    }
}
