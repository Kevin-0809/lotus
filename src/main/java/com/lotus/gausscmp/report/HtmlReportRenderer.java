package com.lotus.gausscmp.report;

import com.lotus.gausscmp.compare.diff.*;
import java.util.*;

public final class HtmlReportRenderer {
    private static final String CSS = """
        body { font-family: sans-serif; margin: 20px; color: #222; }
        .summary { background: #f4f6f8; padding: 15px; border-radius: 8px; margin-bottom: 20px; }
        .summary h2 { margin-top: 0; }
        .tabs { display: flex; gap: 5px; margin-bottom: 10px; }
        .tab { padding: 8px 16px; cursor: pointer; border: 1px solid #ccc; border-radius: 4px 4px 0 0; background: #eee; }
        .tab.active { background: #fff; border-bottom: 1px solid #fff; }
        .panel { display: none; border: 1px solid #ccc; padding: 15px; }
        .panel.active { display: block; }
        table { border-collapse: collapse; width: 100%; margin-bottom: 10px; }
        th, td { border: 1px solid #ddd; padding: 6px 10px; text-align: left; }
        th { background: #f0f0f0; }
        .diff { color: #c0392b; font-weight: bold; }
        .skip { color: #7f8c8d; }
        """;

    public String render(ReportModel model) {
        return render(model, 1000);
    }

    public String render(ReportModel model, int maxDisplayRows) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta charset=\"UTF-8\"><title>GaussDB Schema 比对报告</title><style>").append(CSS).append("</style></head><body>");
        renderSummary(sb, model);
        renderTabs(sb);
        sb.append("<div id=\"structure\" class=\"panel active\">");
        renderStructure(sb, model.structureDiff());
        sb.append("</div>");
        sb.append("<div id=\"data\" class=\"panel\">");
        renderData(sb, model.dataDiff(), maxDisplayRows);
        sb.append("</div>");
        sb.append("<script>")
          .append("document.querySelectorAll('.tab').forEach(t=>t.onclick(()=>{")
          .append("document.querySelectorAll('.tab').forEach(x=>x.classList.remove('active'));")
          .append("document.querySelectorAll('.panel').forEach(x=>x.classList.remove('active'));")
          .append("t.classList.add('active');document.getElementById(t.dataset.target).classList.add('active');")
          .append("}));")
          .append("</script>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private void renderSummary(StringBuilder sb, ReportModel m) {
        sb.append("<div class=\"summary\"><h2>GaussDB Schema 比对报告</h2>");
        sb.append("<p>源 schema: <b>").append(m.sourceSchema()).append("</b> → 目标 schema: <b>").append(m.targetSchema()).append("</b></p>");
        sb.append("<table><tr><th>类别</th><th>一致</th><th>差异</th><th>跳过</th></tr>");
        sb.append("<tr><td>结构</td><td>").append(m.structureConsistent()).append("</td><td class=\"diff\">")
          .append(m.structureDifferent()).append("</td><td>-</td></tr>");
        sb.append("<tr><td>数据</td><td>").append(m.dataConsistent()).append("</td><td class=\"diff\">")
          .append(m.dataDifferent()).append("</td><td class=\"skip\">").append(m.dataSkipped()).append("</td></tr>");
        sb.append("</table></div>");
    }

    private void renderTabs(StringBuilder sb) {
        sb.append("<div class=\"tabs\">");
        sb.append("<div class=\"tab active\" data-target=\"structure\">结构差异</div>");
        sb.append("<div class=\"tab\" data-target=\"data\">数据差异</div>");
        sb.append("</div>");
    }

    private void renderStructure(StringBuilder sb, StructureDiffResult sdr) {
        for (TableStructureDiff t : sdr.tableDiffs()) {
            if (t.status() == TableStructureStatus.CONSISTENT) continue;
            sb.append("<h3>").append(t.tableName()).append(" <span class=\"diff\">").append(t.status()).append("</span></h3>");
            if (!t.columnDiffs().isEmpty()) {
                sb.append("<table><tr><th>类型</th><th>列</th><th>源</th><th>目标</th><th>字段</th></tr>");
                for (ColumnDiff cd : t.columnDiffs())
                    sb.append("<tr><td>").append(cd.type()).append("</td><td>").append(cd.columnName())
                      .append("</td><td>").append(cd.sourceValue()).append("</td><td>").append(cd.targetValue())
                      .append("</td><td>").append(cd.field()).append("</td></tr>");
                sb.append("</table>");
            }
            if (!t.constraintDiffs().isEmpty()) {
                sb.append("<table><tr><th>类型</th><th>约束</th><th>源</th><th>目标</th></tr>");
                for (ConstraintDiff cd : t.constraintDiffs())
                    sb.append("<tr><td>").append(cd.type()).append("</td><td>").append(cd.constraintName())
                      .append("</td><td>").append(cd.sourceDef()).append("</td><td>").append(cd.targetDef()).append("</td></tr>");
                sb.append("</table>");
            }
            if (!t.indexDiffs().isEmpty()) {
                sb.append("<table><tr><th>类型</th><th>索引</th><th>源</th><th>目标</th></tr>");
                for (IndexDiff id : t.indexDiffs())
                    sb.append("<tr><td>").append(id.type()).append("</td><td>").append(id.indexName())
                      .append("</td><td>").append(id.sourceDef()).append("</td><td>").append(id.targetDef()).append("</td></tr>");
                sb.append("</table>");
            }
        }
    }

    private void renderData(StringBuilder sb, DataDiffResult ddr, int maxDisplayRows) {
        for (TableDataDiff t : ddr.tableDiffs()) {
            sb.append("<h3>").append(t.tableName()).append(" <span class=\"").append(t.status() == TableDataStatus.CONSISTENT ? "" : "diff")
              .append("\">").append(t.status()).append("</span></h3>");
            if (t.status() != TableDataStatus.DIFFERENT) {
                if (t.status() == TableDataStatus.SKIPPED) sb.append("<p class=\"skip\">跳过原因: ").append(t.skippedReason()).append("</p>");
                continue;
            }
            sb.append("<p>源行数: ").append(t.sourceRowCount()).append(" / 目标行数: ").append(t.targetRowCount())
              .append(" | 块统计: 共 ").append(t.chunkStats().total()).append(", 一致 ").append(t.chunkStats().consistent())
              .append(", 差异 ").append(t.chunkStats().mismatched()).append("</p>");
            if (!t.rowDiffs().isEmpty()) {
                int shown = Math.min(t.rowDiffs().size(), maxDisplayRows);
                sb.append("<table><tr><th>差异类型</th><th>主键</th><th>源值</th><th>目标值</th></tr>");
                for (int i = 0; i < shown; i++) {
                    RowDiff rd = t.rowDiffs().get(i);
                    sb.append("<tr><td>").append(rd.type()).append("</td><td>").append(rd.keyValues())
                      .append("</td><td>").append(rd.sourceValues()).append("</td><td>").append(rd.targetValues()).append("</td></tr>");
                }
                sb.append("</table>");
                if (t.rowDiffs().size() > maxDisplayRows)
                    sb.append("<p class=\"skip\">截断: 显示 ").append(shown).append(" / ").append(t.rowDiffs().size()).append("（完整见 result.json / dml_sync.sql）</p>");
            }
        }
    }
}
