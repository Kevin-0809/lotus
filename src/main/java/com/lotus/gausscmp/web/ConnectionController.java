package com.lotus.gausscmp.web;

import com.lotus.gausscmp.web.entity.DbConnection;
import com.lotus.gausscmp.web.repository.ConnectionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;

@RestController
@RequestMapping("/api/connections")
@CrossOrigin
public class ConnectionController {

    private final ConnectionRepository repo;

    public ConnectionController(ConnectionRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DbConnection c : repo.findAllByOrderByIdAsc()) {
            result.add(toDto(c, true));
        }
        return result;
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        DbConnection c = repo.findById(id).orElseThrow(() -> new NoSuchElementException("数据源不存在: " + id));
        return toDto(c, true);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, String> body) {
        DbConnection c = new DbConnection(
            require(body, "name"),
            require(body, "url"),
            require(body, "username"),
            body.getOrDefault("password", ""),
            require(body, "schema"),
            body.getOrDefault("remark", "")
        );
        try {
            c = repo.save(c);
            return toDto(c, false);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("数据源名称已存在: " + c.getName());
        }
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        DbConnection c = repo.findById(id).orElseThrow(() -> new NoSuchElementException("数据源不存在: " + id));
        if (body.containsKey("name")) c.setName(body.get("name"));
        if (body.containsKey("url")) c.setUrl(body.get("url"));
        if (body.containsKey("username")) c.setUsername(body.get("username"));
        if (body.containsKey("password")) c.setPassword(body.get("password"));
        if (body.containsKey("schema")) c.setSchema(body.get("schema"));
        if (body.containsKey("remark")) c.setRemark(body.get("remark"));
        try {
            c = repo.save(c);
            return toDto(c, false);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("数据源名称已存在: " + c.getName());
        }
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        repo.deleteById(id);
        return Map.of("success", true);
    }

    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable Long id) {
        DbConnection c = repo.findById(id).orElseThrow(() -> new NoSuchElementException("数据源不存在: " + id));
        return testConnection(c.getUrl(), c.getUsername(), c.getPassword(), c.getSchema());
    }

    @PostMapping("/test")
    public Map<String, Object> testInline(@RequestBody Map<String, String> body) {
        return testConnection(require(body, "url"), require(body, "username"),
            body.getOrDefault("password", ""), require(body, "schema"));
    }

    private Map<String, Object> testConnection(String url, String user, String pass, String schema) {
        Map<String, Object> r = new LinkedHashMap<>();
        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            String catalog = conn.getCatalog();
            r.put("success", true);
            r.put("message", "连接成功 (catalog=" + catalog + ")");
            try (var rs = conn.getMetaData().getSchemas()) {
                List<String> schemas = new ArrayList<>();
                while (rs.next()) schemas.add(rs.getString("TABLE_SCHEM"));
                r.put("schemas", schemas);
            }
        } catch (Exception e) {
            r.put("success", false);
            r.put("message", e.getMessage());
        }
        return r;
    }

    private static Map<String, Object> toDto(DbConnection c, boolean maskPassword) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("url", c.getUrl());
        m.put("username", c.getUsername());
        m.put("password", maskPassword ? "********" : c.getPassword());
        m.put("schema", c.getSchema());
        m.put("remark", c.getRemark());
        m.put("createdAt", c.getCreatedAt() != null ? c.getCreatedAt().toString() : null);
        m.put("collectedAt", c.getCollectedAt() != null ? c.getCollectedAt().toString() : null);
        return m;
    }

    private static String require(Map<String, String> body, String key) {
        String v = body.get(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("字段缺失: " + key);
        return v;
    }
}
