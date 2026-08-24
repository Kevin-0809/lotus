package com.lotus.gausscmp.web.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;
import java.time.LocalDateTime;

@Entity
@Table(name = "meta_sequence", indexes = {
    @Index(name = "idx_meta_sequence_source", columnList = "connection_id, schema_name, sequence_name")
})
@Comment("采集的序列元数据")
public class MetaSequence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键ID")
    private Long id;

    @Column(name = "connection_id", nullable = false)
    @Comment("数据源ID")
    private Long connectionId;

    @Column(name = "connection_name", nullable = false, length = 200)
    @Comment("数据源名称快照")
    private String connectionName;

    @Column(name = "schema_name", nullable = false, length = 100)
    @Comment("Schema名称")
    private String schemaName;

    @Column(name = "sequence_name", nullable = false, length = 200)
    @Comment("序列名")
    private String sequenceName;

    @Column(name = "data_type", length = 50)
    @Comment("数据类型")
    private String dataType;

    @Column(name = "start_value")
    @Comment("起始值")
    private Long startValue;

    @Column(name = "increment_by")
    @Comment("步长")
    private Long incrementBy;

    @Column(name = "min_value")
    @Comment("最小值")
    private Long minValue;

    @Column(name = "max_value")
    @Comment("最大值")
    private Long maxValue;

    @Column(name = "cache_size")
    @Comment("缓存大小")
    private Long cacheSize;

    @Column(name = "cycle", nullable = false)
    @Comment("是否循环(true=是,false=否)")
    private boolean cycle;

    @Column(name = "collected_at", nullable = false, updatable = false)
    @Comment("采集时间")
    private LocalDateTime collectedAt;

    public MetaSequence() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getConnectionId() { return connectionId; }
    public void setConnectionId(Long v) { this.connectionId = v; }
    public String getConnectionName() { return connectionName; }
    public void setConnectionName(String v) { this.connectionName = v; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String v) { this.schemaName = v; }
    public String getSequenceName() { return sequenceName; }
    public void setSequenceName(String v) { this.sequenceName = v; }
    public String getDataType() { return dataType; }
    public void setDataType(String v) { this.dataType = v; }
    public Long getStartValue() { return startValue; }
    public void setStartValue(Long v) { this.startValue = v; }
    public Long getIncrementBy() { return incrementBy; }
    public void setIncrementBy(Long v) { this.incrementBy = v; }
    public Long getMinValue() { return minValue; }
    public void setMinValue(Long v) { this.minValue = v; }
    public Long getMaxValue() { return maxValue; }
    public void setMaxValue(Long v) { this.maxValue = v; }
    public Long getCacheSize() { return cacheSize; }
    public void setCacheSize(Long v) { this.cacheSize = v; }
    public boolean isCycle() { return cycle; }
    public void setCycle(boolean v) { this.cycle = v; }
    public LocalDateTime getCollectedAt() { return collectedAt; }
    public void setCollectedAt(LocalDateTime v) { this.collectedAt = v; }
}
