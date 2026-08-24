package com.lotus.gausscmp.web.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "meta_constraint", indexes = {
    @Index(name = "idx_meta_constraint_source", columnList = "connection_id, schema_name, table_name, constraint_name")
})
@Comment("采集的约束元数据")
public class MetaConstraint {

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

    @Column(name = "constraint_name", nullable = false, length = 200)
    @Comment("约束名")
    private String constraintName;

    @Column(name = "type", nullable = false, length = 1)
    @Comment("约束类型(p=主键/u=唯一/f=外键/c=检查)")
    private String type;

    @Column(name = "definition", length = 2000)
    @Comment("约束定义")
    private String definition;

    @Column(name = "columns", length = 500)
    @Comment("约束列名(逗号分隔)")
    private String columns;

    @Column(name = "ref_table", length = 200)
    @Comment("外键引用表")
    private String refTable;

    public MetaConstraint() {}

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
    public String getConstraintName() { return constraintName; }
    public void setConstraintName(String v) { this.constraintName = v; }
    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public String getDefinition() { return definition; }
    public void setDefinition(String v) { this.definition = v; }
    public String getColumns() { return columns; }
    public void setColumns(String v) { this.columns = v; }
    public String getRefTable() { return refTable; }
    public void setRefTable(String v) { this.refTable = v; }
}
