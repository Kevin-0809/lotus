package com.lotus.gausscmp.web;

import com.lotus.gausscmp.web.entity.CompareHistory;
import com.lotus.gausscmp.web.repository.HistoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/history")
@CrossOrigin
public class HistoryController {

    private final HistoryRepository repo;

    public HistoryController(HistoryRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page - 1), Math.min(100, size));
        Page<CompareHistory> p = repo.findAllByOrderByCreatedAtDesc(pageable);
        List<Map<String, Object>> items = new ArrayList<>();
        for (CompareHistory h : p.getContent()) {
            items.add(toSummary(h));
        }
        return Map.of(
            "items", items,
            "total", p.getTotalElements(),
            "page", page,
            "size", size,
            "totalPages", p.getTotalPages()
        );
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        CompareHistory h = repo.findById(id).orElseThrow(() -> new NoSuchElementException("历史记录不存在: " + id));
        return toDetail(h);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        repo.deleteById(id);
        return Map.of("success", true);
    }

    private Map<String, Object> toSummary(CompareHistory h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", h.getId());
        m.put("compareType", h.getCompareType());
        m.put("sourceConnectionName", h.getSourceConnectionName());
        m.put("targetConnectionName", h.getTargetConnectionName());
        m.put("sourceSchema", h.getSourceSchema());
        m.put("targetSchema", h.getTargetSchema());
        m.put("structureConsistent", h.getStructureConsistent());
        m.put("structureDifferent", h.getStructureDifferent());
        m.put("dataConsistent", h.getDataConsistent());
        m.put("dataDifferent", h.getDataDifferent());
        m.put("dataSkipped", h.getDataSkipped());
        m.put("status", h.getStatus());
        m.put("durationMs", h.getDurationMs());
        m.put("createdAt", h.getCreatedAt() != null ? h.getCreatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> toDetail(CompareHistory h) {
        Map<String, Object> m = toSummary(h);
        m.put("optionsJson", h.getOptionsJson());
        m.put("errorMsg", h.getErrorMsg());
        m.put("reportJson", h.getReportJson());
        m.put("ddlScript", h.getDdlScript());
        m.put("dmlScript", h.getDmlScript());
        m.put("sourceConnectionId", h.getSourceConnectionId());
        m.put("targetConnectionId", h.getTargetConnectionId());
        return m;
    }
}
