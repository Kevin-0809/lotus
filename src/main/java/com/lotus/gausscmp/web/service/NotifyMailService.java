package com.lotus.gausscmp.web.service;

import jakarta.mail.internet.MimeMessage;
import com.lotus.gausscmp.compare.diff.*;
import com.lotus.gausscmp.report.ReportModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 比对结果邮件通知 */
@Service
public class NotifyMailService {
    private static final Logger LOG = LoggerFactory.getLogger(NotifyMailService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JavaMailSender mailSender;
    private final boolean enabled;
    private final String from;
    private final String cc;

    public NotifyMailService(JavaMailSender mailSender,
                             @Value("${notify.mail.enabled:false}") boolean enabled,
                             @Value("${notify.mail.from:gausscmp@localhost}") String from,
                             @Value("${notify.mail.cc:}") String cc) {
        this.mailSender = mailSender;
        this.enabled = enabled;
        this.from = from;
        this.cc = cc;
    }

    public boolean enabled() { return enabled; }

    /** 当前邮件配置状态（不含密码），用于页面诊断 */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("from", from);
        m.put("cc", cc == null ? "" : cc);
        return m;
    }

    public void sendCompareResult(String toCsv, String taskName, String status,
                                  long structureConsistent, long structureDifferent,
                                  long dataConsistent, long dataDifferent, long dataSkipped,
                                  long collectSourceMs, long collectTargetMs, long compareMs,
                                  long totalMs, String error, ReportModel report) {
        if (!enabled || toCsv == null || toCsv.isBlank()) return;
        List<String> to = Arrays.stream(toCsv.split("[,;]"))
            .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (to.isEmpty()) return;
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(to.toArray(new String[0]));
            List<String> ccList = cc == null ? List.of() : Arrays.stream(cc.split("[,;]"))
                .map(String::trim).filter(x -> !x.isEmpty()).toList();
            if (!ccList.isEmpty()) helper.setCc(ccList.toArray(new String[0]));
            boolean failed = !"SUCCESS".equals(status);
            helper.setSubject((failed ? "【比对异常】" : structureDifferent > 0 ? "【发现差异】" : "【比对一致】")
                + taskName + " - " + LocalDateTime.now().format(FMT));
            helper.setText(buildBody(taskName, status, structureConsistent, structureDifferent,
                dataConsistent, dataDifferent, dataSkipped, collectSourceMs, collectTargetMs,
                    compareMs, totalMs, error, report), true);
            mailSender.send(msg);
            LOG.info("比对结果邮件已发送: task={}, to={}", taskName, to);
        } catch (Exception e) {
            LOG.warn("比对结果邮件发送失败: task={}, to={}, error={}", taskName, to, e.toString(), e);
        }
    }

    private String buildBody(String taskName, String status, long sc, long sd,
                             long dc, long dd, long ds, long csm, long ctm, long cm,
                             long tm, String error, ReportModel report) {
        StringBuilder b = new StringBuilder();
        b.append("<div style='font-family:Segoe UI,Arial,sans-serif;font-size:14px;color:#18181b'>");
        b.append("<h2 style='margin:0 0 16px'>GaussDB 比对平台 · 定时比对报告</h2>");
        b.append("<p style='color:#63636b'>任务: <b>").append(esc(taskName)).append("</b> · 时间: ")
            .append(LocalDateTime.now().format(FMT)).append("</p>");
        b.append("<table style='border-collapse:collapse;margin-bottom:16px'>");
        b.append(row("状态", "SUCCESS".equals(status)
            ? "<span style='color:#1f9d61;font-weight:600'>成功</span>"
            : "<span style='color:#e5484d;font-weight:600'>失败</span>"));
        b.append(row("结构一致 / 差异", "<span style='color:#1f9d61'>" + sc + "</span> / <span style='color:#e5484d'>" + sd + "</span>"));
        if (dc + dd + ds > 0) {
            b.append(row("数据一致 / 差异 / 跳过",
                "<span style='color:#1f9d61'>" + dc + "</span> / <span style='color:#e5484d'>" + dd
                    + "</span> / <span style='color:#63636b'>" + ds + "</span>"));
        }
        b.append(row("源端采集耗时", fmtMs(csm)));
        b.append(row("目标端采集耗时", fmtMs(ctm)));
        b.append(row("比对耗时", fmtMs(cm)));
        b.append(row("总耗时", fmtMs(tm)));
        b.append("</table>");
        if (error != null && !error.isBlank()) {
            b.append("<p style='background:#fdf1f1;border:1px solid #e5484d;border-radius:6px;padding:10px 12px;color:#e5484d'>")
                .append(esc(error)).append("</p>");
        }
        appendDetails(b, report);
        b.append("<p style='background:#fffbe6;border:1px solid #ffe58f;border-radius:6px;padding:10px 12px;color:#8c6d1f;font-size:12px'>")
            .append("备注：当前邮件为系统自动比对结果，仅用于差异提醒。若需要获取差异 DDL，请手工执行结构比对，并由人工审核 DDL 内容；审核确认后，才能在差异环境执行追补。")
            .append("</p>");
        b.append("<p style='color:#9494a0;font-size:12px'>此邮件由 GaussDB Schema 比对平台自动发送，请勿回复。</p></div>");
        return b.toString();
    }

    private void appendDetails(StringBuilder b, ReportModel report) {
        if (report == null) return;
        b.append("<h3 style='margin:20px 0 8px'>差异明细</h3>");
        java.util.Set<String> tableNames = new java.util.LinkedHashSet<>();
        java.util.Map<String, TableDataDiff> dataByTable = new java.util.LinkedHashMap<>();
        if (report.dataDiff() != null) {
            report.dataDiff().tableDiffs().stream()
                .filter(d -> d.status() == TableDataStatus.DIFFERENT)
                .forEach(d -> dataByTable.put(d.tableName(), d));
        }
        if (report.structureDiff() != null) {
            for (TableStructureDiff table : report.structureDiff().tableDiffs()) {
                if (table.status() == TableStructureStatus.CONSISTENT) continue;
                tableNames.add(table.tableName());
                appendTableHeader(b, table.tableName());
                if (!table.existsInSource() || !table.existsInTarget())
                    b.append("<li>").append(!table.existsInSource() ? "源端缺失表" : "目标端缺失表").append("</li>");
                table.columnDiffs().forEach(d -> appendItem(b, d.type().getLabel() + "：字段 " + d.columnName() + "（源：" + safe(d.sourceValue()) + "；目标：" + safe(d.targetValue()) + "）"));
                table.constraintDiffs().forEach(d -> appendItem(b, d.type().getLabel() + "：约束 " + d.constraintName() + "（源：" + safe(d.sourceDef()) + "；目标：" + safe(d.targetDef()) + "）"));
                table.indexDiffs().forEach(d -> appendItem(b, d.type().getLabel() + "：索引 " + d.indexName() + "（源：" + safe(d.sourceDef()) + "；目标：" + safe(d.targetDef()) + "）"));
                int partitionLimit = Math.min(10, table.partitionDiffs().size());
                for (int i = 0; i < partitionLimit; i++) {
                    PartitionDiff d = table.partitionDiffs().get(i);
                    appendItem(b, d.type().getLabel() + "：分区 " + d.partitionName() + "（源：" + safe(d.sourceBoundary()) + "；目标：" + safe(d.targetBoundary()) + "）");
                }
                if (table.partitionDiffs().size() > partitionLimit)
                    appendItem(b, "分区差异其余 " + (table.partitionDiffs().size() - partitionLimit) + " 条省略");
                table.commentDiff().ifPresent(d -> appendItem(b, "表注释不一致：" + d));
                TableDataDiff data = dataByTable.get(table.tableName());
                if (data != null)
                    appendItem(b, "数据行数不一致（源：" + data.sourceRowCount() + "；目标：" + data.targetRowCount() + "）");
                b.append("</ul>");
            }
        }
        for (TableDataDiff d : dataByTable.values()) {
                if (tableNames.contains(d.tableName())) continue;
                tableNames.add(d.tableName());
                appendTableHeader(b, d.tableName());
                appendItem(b, "数据行数不一致（源：" + d.sourceRowCount() + "；目标：" + d.targetRowCount() + "）");
                b.append("</ul>");
        }
        if (report.structureDiff() != null && !report.structureDiff().sequenceDiffs().isEmpty()) {
            b.append("<p style='margin:14px 0 4px'><b>序列差异</b></p><ul>");
            report.structureDiff().sequenceDiffs().forEach(d -> appendItem(b, d.type().getLabel() + "：序列 " + d.sequenceName() + "（源：" + safe(d.sourceDef()) + "；目标：" + safe(d.targetDef()) + "）"));
            b.append("</ul>");
        }
        if (tableNames.isEmpty() && (report.structureDiff() == null || report.structureDiff().sequenceDiffs().isEmpty()))
            b.append("<p style='color:#1f9d61'>未发现结构或数据差异。</p>");
    }

    private static void appendTableHeader(StringBuilder b, String tableName) {
        b.append("<p style='margin:12px 0 4px;padding:7px 10px;background:#f5f5f5;border-left:3px solid #1677ff'><b>表：")
            .append(esc(tableName)).append("</b></p><ul>");
    }

    private static void appendItem(StringBuilder b, String value) {
        b.append("<li>").append(esc(value)).append("</li>");
    }

    private static String safe(String value) { return value == null || value.isBlank() ? "-" : value; }

    private static String row(String k, String v) {
        return "<tr><td style='border:1px solid #ececec;padding:6px 14px;background:#fafafa'>" + k
            + "</td><td style='border:1px solid #ececec;padding:6px 14px'>" + v + "</td></tr>";
    }

    private static String fmtMs(long ms) {
        if (ms < 1000) return ms + " ms";
        return String.format("%.1f s", ms / 1000.0);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
