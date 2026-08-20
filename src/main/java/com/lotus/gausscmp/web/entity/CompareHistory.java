package com.lotus.gausscmp.web.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;
import java.time.LocalDateTime;

@Entity
@Table(name = "compare_history")
@Comment("比对历史记录表")
public class CompareHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键ID")
    private Long id;

    @Column(name = "source_connection_id")
    @Comment("源数据源ID")
    private Long sourceConnectionId;

    @Column(name = "source_connection_name", length = 100)
    @Comment("源数据源名称")
    private String sourceConnectionName;

    @Column(name = "target_connection_id")
    @Comment("目标数据源ID")
    private Long targetConnectionId;

    @Column(name = "target_connection_name", length = 100)
    @Comment("目标数据源名称")
    private String targetConnectionName;

    @Column(name = "source_schema", length = 100)
    @Comment("源Schema")
    private String sourceSchema;

    @Column(name = "target_schema", length = 100)
    @Comment("目标Schema")
    private String targetSchema;

    @Column(name = "options_json", length = 4000)
    @Comment("比对选项JSON")
    private String optionsJson;

    @Column(name = "structure_consistent")
    @Comment("结构一致表数")
    private long structureConsistent;

    @Column(name = "structure_different")
    @Comment("结构差异表数")
    private long structureDifferent;

    @Column(name = "data_consistent")
    @Comment("数据一致表数")
    private long dataConsistent;

    @Column(name = "data_different")
    @Comment("数据差异表数")
    private long dataDifferent;

    @Column(name = "data_skipped")
    @Comment("数据跳过表数")
    private long dataSkipped;

    @Column(length = 20)
    @Comment("比对状态(SUCCESS/FAILED)")
    private String status;

    @Column(name = "compare_type", length = 10)
    @Comment("比对类型(STRUCTURE/DATA/BOTH)")
    private String compareType;

    @Column(name = "error_msg", length = 2000)
    @Comment("错误信息")
    private String errorMsg;

    @Lob
    @Column(name = "report_json", columnDefinition = "LONGTEXT")
    @Comment("比对报告JSON")
    private String reportJson;

    @Lob
    @Column(name = "ddl_script", columnDefinition = "LONGTEXT")
    @Comment("DDL同步脚本")
    private String ddlScript;

    @Lob
    @Column(name = "dml_script", columnDefinition = "LONGTEXT")
    @Comment("DML同步脚本")
    private String dmlScript;

    @Column(name = "duration_ms")
    @Comment("比对耗时(毫秒)")
    private long durationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("创建时间")
    private LocalDateTime createdAt;

    public CompareHistory() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSourceConnectionId() { return sourceConnectionId; }
    public void setSourceConnectionId(Long v) { this.sourceConnectionId = v; }
    public String getSourceConnectionName() { return sourceConnectionName; }
    public void setSourceConnectionName(String v) { this.sourceConnectionName = v; }
    public Long getTargetConnectionId() { return targetConnectionId; }
    public void setTargetConnectionId(Long v) { this.targetConnectionId = v; }
    public String getTargetConnectionName() { return targetConnectionName; }
    public void setTargetConnectionName(String v) { this.targetConnectionName = v; }
    public String getSourceSchema() { return sourceSchema; }
    public void setSourceSchema(String v) { this.sourceSchema = v; }
    public String getTargetSchema() { return targetSchema; }
    public void setTargetSchema(String v) { this.targetSchema = v; }
    public String getOptionsJson() { return optionsJson; }
    public void setOptionsJson(String v) { this.optionsJson = v; }
    public long getStructureConsistent() { return structureConsistent; }
    public void setStructureConsistent(long v) { this.structureConsistent = v; }
    public long getStructureDifferent() { return structureDifferent; }
    public void setStructureDifferent(long v) { this.structureDifferent = v; }
    public long getDataConsistent() { return dataConsistent; }
    public void setDataConsistent(long v) { this.dataConsistent = v; }
    public long getDataDifferent() { return dataDifferent; }
    public void setDataDifferent(long v) { this.dataDifferent = v; }
    public long getDataSkipped() { return dataSkipped; }
    public void setDataSkipped(long v) { this.dataSkipped = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getCompareType() { return compareType; }
    public void setCompareType(String v) { this.compareType = v; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String v) { this.errorMsg = v; }
    public String getReportJson() { return reportJson; }
    public void setReportJson(String v) { this.reportJson = v; }
    public String getDdlScript() { return ddlScript; }
    public void setDdlScript(String v) { this.ddlScript = v; }
    public String getDmlScript() { return dmlScript; }
    public void setDmlScript(String v) { this.dmlScript = v; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long v) { this.durationMs = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
}
