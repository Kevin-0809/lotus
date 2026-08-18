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

    @Test
    void stripsSchemaPrefixFromDefinition() {
        assertThat(DefinitionNormalizer.normalize("create index idx on adp.t1 (id)", "adp"))
            .isEqualTo("create index idx on t1 (id)");
        assertThat(DefinitionNormalizer.normalize("foreign key (a) references adp.t2(id)", "adp"))
            .isEqualTo("foreign key (a) references t2(id)");
    }

    @Test
    void stripsSchemaPrefixCaseInsensitive() {
        assertThat(DefinitionNormalizer.normalize("create index idx on ADP.t1 (id)", "adp"))
            .isEqualTo("create index idx on t1 (id)");
    }

    @Test
    void stripSchemaPrefixFromName() {
        assertThat(DefinitionNormalizer.stripSchemaPrefix("adp.t1", "adp")).isEqualTo("t1");
        assertThat(DefinitionNormalizer.stripSchemaPrefix("tss.t1", "adp")).isEqualTo("tss.t1");
        assertThat(DefinitionNormalizer.stripSchemaPrefix(null, "adp")).isNull();
    }
}
