package com.lotus.gausscmp.web.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "meta_index", indexes = {
    @Index(name = "idx_meta_index_source", columnList = "connection_id, schema_name, table_name, index_name")
})
@Comment("采集的索引元数据")
public class MetaIndex {

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

    @Column(name = "index_name", nullable = false, length = 200)
    @Comment("索引名")
    private String indexName;

    @Column(name = "columns", length = 500)
    @Comment("索引列名(逗号分隔)")
    private String columns;

    @Column(name = "is_unique", nullable = false)
    @Comment("是否唯一索引(true=是,false=否)")
    private boolean unique;

    @Column(name = "is_partial", nullable = false)
    @Comment("是否部分索引(true=是,false=否)")
    private boolean partial;

    @Column(name = "where_clause", length = 2000)
    @Comment("部分索引WHERE条件")
    private String whereClause;

    @Column(name = "definition", length = 2000)
    @Comment("索引完整定义")
    private String definition;

    public MetaIndex() {}

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
    public String getIndexName() { return indexName; }
    public void setIndexName(String v) { this.indexName = v; }
    public String getColumns() { return columns; }
    public void setColumns(String v) { this.columns = v; }
    public boolean isUnique() { return unique; }
    public void setUnique(boolean v) { this.unique = v; }
    public boolean isPartial() { return partial; }
    public void setPartial(boolean v) { this.partial = v; }
    public String getWhereClause() { return whereClause; }
    public void setWhereClause(String v) { this.whereClause = v; }
    public String getDefinition() { return definition; }
    public void setDefinition(String v) { this.definition = v; }
}
