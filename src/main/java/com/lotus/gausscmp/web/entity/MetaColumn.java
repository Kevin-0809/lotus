package com.lotus.gausscmp.web.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "meta_column", indexes = {
    @Index(name = "idx_meta_column_source", columnList = "connection_id, schema_name, table_name, column_name")
})
@Comment("采集的字段元数据")
public class MetaColumn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键ID")
    private Long id;

    @Column(name = "table_id", nullable = false)
    @Comment("关联表ID")
    private Long tableId;

    @Column(name = "connection_id", nullable = false)
    @Comment("数据源ID快照")
    private Long connectionId;

    @Column(name = "connection_name", nullable = false, length = 200)
    @Comment("数据源名称快照")
    private String connectionName;

    @Column(name = "schema_name", nullable = false, length = 100)
    @Comment("Schema名称快照")
    private String schemaName;

    @Column(name = "table_name", nullable = false, length = 200)
    @Comment("表名快照")
    private String tableName;

    @Column(name = "column_name", nullable = false, length = 200)
    @Comment("字段名")
    private String columnName;

    @Column(name = "data_type", nullable = false, length = 200)
    @Comment("数据类型")
    private String dataType;

    @Column(name = "nullable", nullable = false)
    @Comment("是否允许NULL(true=允许,false=不允许)")
    private boolean nullable;

    @Column(name = "default_value", length = 1000)
    @Comment("默认值")
    private String defaultValue;

    @Column(length = 1000)
    @Comment("字段注释")
    private String comment;

    @Column(name = "ordinal", nullable = false)
    @Comment("字段序号")
    private int ordinal;

    public MetaColumn() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTableId() { return tableId; }
    public void setTableId(Long v) { this.tableId = v; }
    public Long getConnectionId() { return connectionId; }
    public void setConnectionId(Long v) { this.connectionId = v; }
    public String getConnectionName() { return connectionName; }
    public void setConnectionName(String v) { this.connectionName = v; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String v) { this.schemaName = v; }
    public String getTableName() { return tableName; }
    public void setTableName(String v) { this.tableName = v; }
    public String getColumnName() { return columnName; }
    public void setColumnName(String v) { this.columnName = v; }
    public String getDataType() { return dataType; }
    public void setDataType(String v) { this.dataType = v; }
    public boolean isNullable() { return nullable; }
    public void setNullable(boolean v) { this.nullable = v; }
    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String v) { this.defaultValue = v; }
    public String getComment() { return comment; }
    public void setComment(String v) { this.comment = v; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int v) { this.ordinal = v; }
}
