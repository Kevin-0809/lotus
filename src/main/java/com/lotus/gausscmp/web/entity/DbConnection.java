package com.lotus.gausscmp.web.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;
import java.time.LocalDateTime;

@Entity
@Table(name = "db_connection")
@Comment("数据源连接配置表")
public class DbConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键ID")
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    @Comment("数据源名称")
    private String name;

    @Column(nullable = false, length = 500)
    @Comment("JDBC连接URL")
    private String url;

    @Column(nullable = false, length = 100)
    @Comment("数据库用户名")
    private String username;

    @Column(length = 200)
    @Comment("数据库密码")
    private String password;

    @Column(name = "schema_name", nullable = false, length = 100)
    @Comment("Schema名称")
    private String schema;

    @Column(length = 200)
    @Comment("备注说明")
    private String remark;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("创建时间")
    private LocalDateTime createdAt;

    @Column(name = "collected_at")
    @Comment("最近元数据采集时间")
    private LocalDateTime collectedAt;

    public DbConnection() {}

    public DbConnection(String name, String url, String username, String password, String schema, String remark) {
        this.name = name;
        this.url = url;
        this.username = username;
        this.password = password;
        this.schema = schema;
        this.remark = remark;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getCollectedAt() { return collectedAt; }
    public void setCollectedAt(LocalDateTime v) { this.collectedAt = v; }
}
