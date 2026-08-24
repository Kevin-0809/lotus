package com.lotus.gausscmp.web;

import com.lotus.gausscmp.web.entity.CompareTableConfig;
import com.lotus.gausscmp.web.repository.CompareTableConfigRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/table-configs")
@CrossOrigin
public class TableConfigController {
    private final CompareTableConfigRepository repo;
    public TableConfigController(CompareTableConfigRepository repo) { this.repo = repo; }

    @GetMapping
    public List<Map<String, Object>> list() { return repo.findAllByOrderByTableTypeAscTableNameAsc().stream().map(TableConfigController::dto).toList(); }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        var name = name(body); var type = type(body);
        if (!repo.findByTableNameAndTableType(name, type).isEmpty()) throw new IllegalArgumentException("该类型下表名已存在: " + name);
        try { return dto(repo.save(new CompareTableConfig(name, type, enabled(body), remark(body)))); }
        catch (DataIntegrityViolationException e) { throw new IllegalArgumentException("该类型下表名已存在: " + name); }
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        var c = repo.findById(id).orElseThrow(() -> new NoSuchElementException("表配置不存在: " + id));
        var name = name(body); var type = type(body);
        if (!repo.findByTableNameAndTableTypeAndIdNot(name, type, id).isEmpty()) throw new IllegalArgumentException("该类型下表名已存在: " + name);
        c.setTableName(name); c.setTableType(type); c.setEnabled(enabled(body)); c.setRemark(remark(body));
        return dto(repo.save(c));
    }

    @PostMapping("/{id}/toggle")
    public Map<String, Object> toggle(@PathVariable Long id) {
        var c = repo.findById(id).orElseThrow(() -> new NoSuchElementException("表配置不存在: " + id));
        c.setEnabled(!c.isEnabled()); return dto(repo.save(c));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) { repo.deleteById(id); return Map.of("success", true); }

    private static String name(Map<String, Object> b) { var v = Objects.toString(b.get("tableName"), "").trim(); if (v.isEmpty()) throw new IllegalArgumentException("表名不能为空"); return v; }
    private static CompareTableConfig.TableType type(Map<String, Object> b) { try { return CompareTableConfig.TableType.valueOf(Objects.toString(b.get("tableType"), "")); } catch (Exception e) { throw new IllegalArgumentException("表类型必须是 PARAMETER 或 EXCLUDE"); } }
    private static boolean enabled(Map<String, Object> b) { return !b.containsKey("enabled") || Boolean.TRUE.equals(b.get("enabled")); }
    private static String remark(Map<String, Object> b) { return Objects.toString(b.get("remark"), "").trim(); }
    private static Map<String, Object> dto(CompareTableConfig c) { var m = new LinkedHashMap<String,Object>(); m.put("id", c.getId()); m.put("tableName", c.getTableName()); m.put("tableType", c.getTableType().name()); m.put("enabled", c.isEnabled()); m.put("remark", c.getRemark()); m.put("createdAt", c.getCreatedAt()); m.put("updatedAt", c.getUpdatedAt()); return m; }
}
