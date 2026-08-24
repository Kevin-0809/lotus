package com.lotus.gausscmp.web.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;
import java.time.LocalDateTime;

@Entity
@Table(name = "meta_table", indexes = {
    @Index(name = "idx_meta_table_source", columnList = "connection_id, schema_name, table_name")
})
@Comment("采集的表元数据")
public class MetaTable {

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

    @Column(name = "table_name", nullable = false, length = 200)
    @Comment("表名")
    private String tableName;

    @Column(length = 1000)
    @Comment("表注释")
    private String comment;

    @Column(name = "partitioned", nullable = false)
    @Comment("是否分区表(true=是,false=否)")
    private boolean partitioned;

    @Column(name = "partition_strategy", length = 1)
    @Comment("分区策略(r=范围/l=列表/h=哈希)")
    private String partitionStrategy;

    @Column(name = "partition_key", length = 500)
    @Comment("分区键列名(逗号分隔)")
    private String partitionKey;

    @Column(name = "partition_count")
    @Comment("分区数量")
    private Integer partitionCount;

    @Column(name = "collected_at", nullable = false, updatable = false)
    @Comment("采集时间")
    private LocalDateTime collectedAt;

    public MetaTable() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getConnectionId() { return connectionId; }
    public void setConnectionId(Long v) { this.connectionId = v; }
    public String getConnectionName() { return connectionName; }
    public void setConnectionName(String v) { this.connectionName = v; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String v) { this.schemaName = v; }
    public String getTableName() { return tableName; }
    public void setTableName(String v) { this.tableName = v; }
    public String getComment() { return comment; }
    public void setComment(String v) { this.comment = v; }
    public boolean isPartitioned() { return partitioned; }
    public void setPartitioned(boolean v) { this.partitioned = v; }
    public String getPartitionStrategy() { return partitionStrategy; }
    public void setPartitionStrategy(String v) { this.partitionStrategy = v; }
    public String getPartitionKey() { return partitionKey; }
    public void setPartitionKey(String v) { this.partitionKey = v; }
    public Integer getPartitionCount() { return partitionCount; }
    public void setPartitionCount(Integer v) { this.partitionCount = v; }
    public LocalDateTime getCollectedAt() { return collectedAt; }
    public void setCollectedAt(LocalDateTime v) { this.collectedAt = v; }
}
