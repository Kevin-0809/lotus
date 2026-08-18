package com.lotus.gausscmp.metadata;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class MetadataModelTest {

    @Test
    void tableMetaDerivesHasPrimaryKey() {
        ColumnMeta col = new ColumnMeta("id", "integer", false, null, null, 1);
        ConstraintMeta pk = new ConstraintMeta("pk_t", ConstraintType.PRIMARY, "PRIMARY KEY (id)", List.of("id"), null);
        TableMeta t = new TableMeta("t", "tbl", List.of(col), List.of(pk), List.of(), true);
        assertThat(t.hasPrimaryKeyOrUnique()).isTrue();
        assertThat(t.primaryKey()).contains(pk);
    }

    @Test
    void tableWithoutConstraintsHasNoKey() {
        TableMeta t = new TableMeta("t", null, List.of(), List.of(), List.of(), false);
        assertThat(t.hasPrimaryKeyOrUnique()).isFalse();
    }

    @Test
    void snapshotHoldsSchemaAndTables() {
        TableMeta t = new TableMeta("t", null, List.of(), List.of(), List.of(), false);
        SchemaSnapshot snap = new SchemaSnapshot("app", List.of(t));
        assertThat(snap.schemaName()).isEqualTo("app");
        assertThat(snap.tables()).hasSize(1);
    }
}
