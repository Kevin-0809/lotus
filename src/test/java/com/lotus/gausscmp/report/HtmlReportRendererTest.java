package com.lotus.gausscmp.report;

import com.lotus.gausscmp.compare.diff.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class HtmlReportRendererTest {

    @Test
    void rendersSummaryAndDiffs() {
        TableStructureDiff sd = new TableStructureDiff("t1", true, true, TableStructureStatus.DIFFERENT,
            List.of(new ColumnDiff(DiffType.COLUMN_MISSING_IN_TARGET, "name", null, null, null)),
            List.of(), List.of(), java.util.Optional.empty());
        StructureDiffResult sdr = new StructureDiffResult(List.of(sd));
        RowDiff rd = new RowDiff(RowDiffType.MISSING_IN_TARGET, java.util.Map.of("id", 1),
            java.util.Map.of("id", 1, "name", "a"), null, List.of());
        TableDataDiff dd = new TableDataDiff("t1", List.of("id"), TableDataStatus.DIFFERENT, 1, 0,
            new ChunkStats(1, 0, 1), List.of(rd), null);
        DataDiffResult ddr = new DataDiffResult(List.of(dd));
        ReportModel model = new ReportModel("app", "app", 0, 1, 0, 1, 0, sdr, ddr);
        String html = new HtmlReportRenderer().render(model);
        assertThat(html).contains("<html");
        assertThat(html).contains("GaussDB Schema 比对报告");
        assertThat(html).contains("结构差异");
        assertThat(html).contains("数据差异");
        assertThat(html).contains("t1");
        assertThat(html).contains("COLUMN_MISSING_IN_TARGET");
    }

    @Test
    void truncatesRowsBeyondLimit() {
        List<RowDiff> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(new RowDiff(RowDiffType.MISSING_IN_TARGET, java.util.Map.of("id", i),
                java.util.Map.of("id", i), null, List.of()));
        }
        TableDataDiff dd = new TableDataDiff("t", List.of("id"), TableDataStatus.DIFFERENT, 5, 0,
            new ChunkStats(1, 0, 1), rows, null);
        DataDiffResult ddr = new DataDiffResult(List.of(dd));
        ReportModel model = new ReportModel("a", "a", 1, 0, 0, 1, 0,
            new StructureDiffResult(List.of()), ddr);
        String html = new HtmlReportRenderer().render(model, 3);
        assertThat(html).contains("截断");
        assertThat(html).contains("3 / 5");
    }
}
