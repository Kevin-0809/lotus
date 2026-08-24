package com.lotus.gausscmp.web.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;
import java.time.LocalDateTime;

@Entity
@Table(name = "compare_table_config",
    uniqueConstraints = @UniqueConstraint(name = "uk_compare_table_config_name_type", columnNames = {"table_name", "table_type"}))
@Comment("比对表配置")
public class CompareTableConfig {
    public enum TableType { PARAMETER, EXCLUDE }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "table_name", nullable = false, length = 200)
    private String tableName;
    @Enumerated(EnumType.STRING) @Column(name = "table_type", nullable = false, length = 20)
    private TableType tableType;
    @Column(nullable = false)
    private boolean enabled = true;
    @Column(length = 500)
    private String remark;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public CompareTableConfig() {}
    public CompareTableConfig(String tableName, TableType tableType, boolean enabled, String remark) {
        this.tableName = tableName; this.tableType = tableType; this.enabled = enabled; this.remark = remark;
    }
    @PrePersist void onCreate() { var now = LocalDateTime.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void onUpdate() { updatedAt = LocalDateTime.now(); }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTableName() { return tableName; }
    public void setTableName(String v) { this.tableName = v; }
    public TableType getTableType() { return tableType; }
    public void setTableType(TableType v) { this.tableType = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public String getRemark() { return remark; }
    public void setRemark(String v) { this.remark = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
