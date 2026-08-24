package com.lotus.gausscmp.web.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "compare_schedule")
public class CompareSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "source_connection_id", nullable = false)
    private Long sourceConnectionId;

    @Column(name = "target_connection_id", nullable = false)
    private Long targetConnectionId;

    @Column(name = "daily_time", nullable = false, length = 5)
    private String dailyTime = "08:00";

    @Column(name = "compare_type", nullable = false, length = 10)
    private String compareType = "STRUCTURE";

    @Column(name = "include_tables", length = 2000)
    private String includeTables;

    @Column(name = "exclude_tables", length = 2000)
    private String excludeTables;

    @Column(name = "notify_emails", length = 2000)
    private String notifyEmails;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "last_status", length = 20)
    private String lastStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public CompareSchedule() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getSourceConnectionId() { return sourceConnectionId; }
    public void setSourceConnectionId(Long v) { this.sourceConnectionId = v; }
    public Long getTargetConnectionId() { return targetConnectionId; }
    public void setTargetConnectionId(Long v) { this.targetConnectionId = v; }
    public String getDailyTime() { return dailyTime; }
    public void setDailyTime(String v) { this.dailyTime = v; }
    public String getCompareType() { return compareType; }
    public void setCompareType(String v) { this.compareType = v; }
    public String getIncludeTables() { return includeTables; }
    public void setIncludeTables(String v) { this.includeTables = v; }
    public String getExcludeTables() { return excludeTables; }
    public void setExcludeTables(String v) { this.excludeTables = v; }
    public String getNotifyEmails() { return notifyEmails; }
    public void setNotifyEmails(String v) { this.notifyEmails = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public LocalDateTime getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(LocalDateTime v) { this.lastRunAt = v; }
    public String getLastStatus() { return lastStatus; }
    public void setLastStatus(String v) { this.lastStatus = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
}
