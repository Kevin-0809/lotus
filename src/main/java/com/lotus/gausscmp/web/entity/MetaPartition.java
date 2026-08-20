package com.lotus.gausscmp.web.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "meta_partition")
@Comment("采集的分区元数据")
public class MetaPartition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键ID")
    private Long id;

    @Column(name = "table_id", nullable = false)
    @Comment("关联表ID")
    private Long tableId;

    @Column(name = "partition_name", nullable = false, length = 200)
    @Comment("分区名")
    private String partitionName;

    @Column(name = "parent_name", length = 200)
    @Comment("父分区名")
    private String parentName;

    @Column(name = "ordinal")
    @Comment("分区内序号")
    private int ordinal;

    @Column(name = "boundary_expr", length = 2000)
    @Comment("分区边界表达式")
    private String boundaryExpr;

    @Column(name = "is_sub_partition", nullable = false)
    @Comment("是否子分区(true=是,false=否)")
    private boolean subPartition;

    @Column(name = "tablespace", length = 100)
    @Comment("表空间")
    private String tablespace;

    @Column(name = "estimated_rows")
    @Comment("估算行数")
    private Long estimatedRows;

    public MetaPartition() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTableId() { return tableId; }
    public void setTableId(Long v) { this.tableId = v; }
    public String getPartitionName() { return partitionName; }
    public void setPartitionName(String v) { this.partitionName = v; }
    public String getParentName() { return parentName; }
    public void setParentName(String v) { this.parentName = v; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int v) { this.ordinal = v; }
    public String getBoundaryExpr() { return boundaryExpr; }
    public void setBoundaryExpr(String v) { this.boundaryExpr = v; }
    public boolean isSubPartition() { return subPartition; }
    public void setSubPartition(boolean v) { this.subPartition = v; }
    public String getTablespace() { return tablespace; }
    public void setTablespace(String v) { this.tablespace = v; }
    public Long getEstimatedRows() { return estimatedRows; }
    public void setEstimatedRows(Long v) { this.estimatedRows = v; }
}
