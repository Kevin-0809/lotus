package com.lotus.gausscmp.web.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "compare_schedule_run")
public class CompareScheduleRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt = LocalDateTime.now();

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "collect_source_ms")
    private Long collectSourceMs;

    @Column(name = "collect_target_ms")
    private Long collectTargetMs;

    @Column(name = "compare_ms")
    private Long compareMs;

    @Column(name = "notify_ms")
    private Long notifyMs;

    @Column(name = "total_ms")
    private Long totalMs;

    @Column(name = "structure_consistent")
    private Long structureConsistent;

    @Column(name = "structure_different")
    private Long structureDifferent;

    @Column(name = "data_consistent")
    private Long dataConsistent;

    @Column(name = "data_different")
    private Long dataDifferent;

    @Column(name = "data_skipped")
    private Long dataSkipped;

    @Column(name = "error_msg", length = 4000)
    private String errorMsg;

    public CompareScheduleRun() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getScheduleId() { return scheduleId; }
    public void setScheduleId(Long v) { this.scheduleId = v; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime v) { this.startedAt = v; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime v) { this.finishedAt = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Long getCollectSourceMs() { return collectSourceMs; }
    public void setCollectSourceMs(Long v) { this.collectSourceMs = v; }
    public Long getCollectTargetMs() { return collectTargetMs; }
    public void setCollectTargetMs(Long v) { this.collectTargetMs = v; }
    public Long getCompareMs() { return compareMs; }
    public void setCompareMs(Long v) { this.compareMs = v; }
    public Long getNotifyMs() { return notifyMs; }
    public void setNotifyMs(Long v) { this.notifyMs = v; }
    public Long getTotalMs() { return totalMs; }
    public void setTotalMs(Long v) { this.totalMs = v; }
    public Long getStructureConsistent() { return structureConsistent; }
    public void setStructureConsistent(Long v) { this.structureConsistent = v; }
    public Long getStructureDifferent() { return structureDifferent; }
    public void setStructureDifferent(Long v) { this.structureDifferent = v; }
    public Long getDataConsistent() { return dataConsistent; }
    public void setDataConsistent(Long v) { this.dataConsistent = v; }
    public Long getDataDifferent() { return dataDifferent; }
    public void setDataDifferent(Long v) { this.dataDifferent = v; }
    public Long getDataSkipped() { return dataSkipped; }
    public void setDataSkipped(Long v) { this.dataSkipped = v; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String v) { this.errorMsg = v; }
}
