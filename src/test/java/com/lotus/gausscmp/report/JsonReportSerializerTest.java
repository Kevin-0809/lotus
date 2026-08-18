package com.lotus.gausscmp.report;

import com.lotus.gausscmp.compare.diff.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class JsonReportSerializerTest {

    @Test
    void serializesReportToJson() {
        TableStructureDiff sd = new TableStructureDiff("t", true, true, TableStructureStatus.CONSISTENT,
            List.of(), List.of(), List.of(), java.util.Optional.empty());
        StructureDiffResult sdr = new StructureDiffResult(List.of(sd));
        TableDataDiff dd = new TableDataDiff("t", List.of("id"), TableDataStatus.CONSISTENT, 5, 5,
            new ChunkStats(1, 1, 0), List.of(), null);
        DataDiffResult ddr = new DataDiffResult(List.of(dd));
        ReportModel model = new ReportModel("app", "app", 1, 0, 1, 0, 0, sdr, ddr);
        String json = JsonReportSerializer.serialize(model);
        assertThat(json).contains("\"sourceSchema\":\"app\"");
        assertThat(json).contains("\"structureConsistent\":1");
        assertThat(json).contains("\"CONSISTENT\"");
    }

    @Test
    void deserializesRoundTrip() {
        TableStructureDiff sd = new TableStructureDiff("t", true, true, TableStructureStatus.DIFFERENT,
            List.of(), List.of(), List.of(), java.util.Optional.empty());
        StructureDiffResult sdr = new StructureDiffResult(List.of(sd));
        ReportModel model = new ReportModel("a", "b", 0, 1, 0, 0, 0, sdr, new DataDiffResult(List.of()));
        String json = JsonReportSerializer.serialize(model);
        ReportModel back = JsonReportSerializer.deserialize(json);
        assertThat(back.sourceSchema()).isEqualTo("a");
        assertThat(back.structureDifferent()).isEqualTo(1);
    }
}
