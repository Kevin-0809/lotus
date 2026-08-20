package com.lotus.gausscmp.web.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "meta_index")
@Comment("采集的索引元数据")
public class MetaIndex {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键ID")
    private Long id;

    @Column(name = "table_id", nullable = false)
    @Comment("关联表ID")
    private Long tableId;

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
