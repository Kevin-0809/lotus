package com.lotus.gausscmp.sync;

import com.lotus.gausscmp.compare.diff.*;
import com.lotus.gausscmp.metadata.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;

class DdlScriptGeneratorTest {

    @Test
    void generatesCreateTableForMissing() {
        TableStructureDiff diff = new TableStructureDiff("t", true, false, TableStructureStatus.DIFFERENT,
            List.of(new ColumnDiff(DiffType.TABLE_MISSING_IN_TARGET, "t", null, null, null)),
            List.of(), List.of(), Optional.empty());
        ColumnMeta col = new ColumnMeta("id", "integer", false, null, null, 1);
        TableMeta table = new TableMeta("t", null, List.of(col), List.of(), List.of(), false);
        String ddl = new DdlScriptGenerator("app").generate(List.of(diff), tableMetaMap(table));
        assertThat(ddl).contains("CREATE TABLE \"app\".\"t\"");
        assertThat(ddl).contains("\"id\" integer");
    }

    @Test
    void generatesAddColumn() {
        TableStructureDiff diff = new TableStructureDiff("t", true, true, TableStructureStatus.DIFFERENT,
            List.of(new ColumnDiff(DiffType.COLUMN_MISSING_IN_TARGET, "name", null, null, null)),
            List.of(), List.of(), Optional.empty());
        String ddl = new DdlScriptGenerator("app").generate(List.of(diff), java.util.Map.of());
        assertThat(ddl).contains("ALTER TABLE \"app\".\"t\" ADD COLUMN \"name\"");
    }

    @Test
    void generatesAlterColumnType() {
        TableStructureDiff diff = new TableStructureDiff("t", true, true, TableStructureStatus.DIFFERENT,
            List.of(new ColumnDiff(DiffType.COLUMN_MISMATCH, "id", "integer", "bigint", "dataType")),
            List.of(), List.of(), Optional.empty());
        String ddl = new DdlScriptGenerator("app").generate(List.of(diff), java.util.Map.of());
        assertThat(ddl).contains("ALTER TABLE \"app\".\"t\" ALTER COLUMN \"id\" TYPE integer");
    }

    @Test
    void generatesCreateIndex() {
        TableStructureDiff diff = new TableStructureDiff("t", true, true, TableStructureStatus.DIFFERENT,
            List.of(), List.of(),
            List.of(new IndexDiff(DiffType.INDEX_MISSING_IN_TARGET, "idx_t", "create index idx_t on t (id)", null)),
            Optional.empty());
        String ddl = new DdlScriptGenerator("app").generate(List.of(diff), java.util.Map.of());
        assertThat(ddl).contains("-- INDEX_MISSING_IN_TARGET: idx_t");
        assertThat(ddl).contains("CREATE INDEX \"idx_t\"");
    }

    private static java.util.Map<String, TableMeta> tableMetaMap(TableMeta... tables) {
        var m = new java.util.HashMap<String, TableMeta>();
        for (TableMeta t : tables) m.put(t.name(), t);
        return m;
    }
}
