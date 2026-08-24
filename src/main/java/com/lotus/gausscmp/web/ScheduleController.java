package com.lotus.gausscmp.web;

import com.lotus.gausscmp.web.entity.CompareSchedule;
import com.lotus.gausscmp.web.entity.CompareScheduleRun;
import com.lotus.gausscmp.web.repository.ConnectionRepository;
import com.lotus.gausscmp.web.repository.ScheduleRepository;
import com.lotus.gausscmp.web.repository.ScheduleRunRepository;
import com.lotus.gausscmp.web.service.NotifyMailService;
import com.lotus.gausscmp.web.service.ScheduleCompareService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/schedules")
@CrossOrigin
public class ScheduleController {

    private final ScheduleRepository scheduleRepo;
    private final ScheduleRunRepository runRepo;
    private final ConnectionRepository connectionRepo;
    private final ScheduleCompareService executeService;
    private final NotifyMailService mailService;

    public ScheduleController(ScheduleRepository scheduleRepo,
                              ScheduleRunRepository runRepo,
                              ConnectionRepository connectionRepo,
                              ScheduleCompareService executeService,
                              NotifyMailService mailService) {
        this.scheduleRepo = scheduleRepo;
        this.runRepo = runRepo;
        this.connectionRepo = connectionRepo;
        this.executeService = executeService;
        this.mailService = mailService;
    }

    public record ScheduleBody(String name, Long sourceConnectionId, Long targetConnectionId,
                               String dailyTime, String compareType, String includeTables,
                               String excludeTables, String notifyEmails, Boolean enabled) {}

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CompareSchedule s : scheduleRepo.findAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("name", s.getName());
            m.put("sourceConnectionId", s.getSourceConnectionId());
            m.put("targetConnectionId", s.getTargetConnectionId());
            m.put("sourceConnectionName", connectionName(s.getSourceConnectionId()));
            m.put("targetConnectionName", connectionName(s.getTargetConnectionId()));
            m.put("dailyTime", s.getDailyTime());
            m.put("compareType", s.getCompareType());
            m.put("includeTables", s.getIncludeTables());
            m.put("excludeTables", s.getExcludeTables());
            m.put("notifyEmails", s.getNotifyEmails());
            m.put("enabled", s.isEnabled());
            m.put("lastRunAt", s.getLastRunAt());
            m.put("lastStatus", s.getLastStatus());
            result.add(m);
        }
        return result;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody ScheduleBody body) {
        CompareSchedule s = new CompareSchedule();
        applyBody(s, body);
        s.setCreatedAt(LocalDateTime.now());
        s = scheduleRepo.save(s);
        return Map.of("success", true, "id", s.getId());
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody ScheduleBody body) {
        CompareSchedule s = scheduleRepo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("任务不存在: " + id));
        applyBody(s, body);
        scheduleRepo.save(s);
        return Map.of("success", true);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        scheduleRepo.deleteById(id);
        return Map.of("success", true);
    }

    @PostMapping("/{id}/toggle")
    public Map<String, Object> toggle(@PathVariable Long id) {
        CompareSchedule s = scheduleRepo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("任务不存在: " + id));
        s.setEnabled(!s.isEnabled());
        scheduleRepo.save(s);
        return Map.of("success", true, "enabled", s.isEnabled());
    }

    @PostMapping("/{id}/run")
    public Map<String, Object> runNow(@PathVariable Long id) {
        CompareScheduleRun run = executeService.runNow(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", "SUCCESS".equals(run.getStatus()));
        m.put("runId", run.getId());
        m.put("status", run.getStatus());
        m.put("collectSourceMs", run.getCollectSourceMs());
        m.put("collectTargetMs", run.getCollectTargetMs());
        m.put("compareMs", run.getCompareMs());
        m.put("notifyMs", run.getNotifyMs());
        m.put("totalMs", run.getTotalMs());
        m.put("errorMsg", run.getErrorMsg());
        return m;
    }

    @GetMapping("/{id}/runs")
    public Map<String, Object> runs(@PathVariable Long id,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "10") int size) {
        int pageSize = Math.min(Math.max(1, size), 50);
        int pageNo = Math.max(1, page);
        long total = runRepo.countRuns(id);
        int totalPages = (int) Math.max(1, (total + pageSize - 1) / pageSize);
        List<CompareScheduleRun> items = runRepo.findRuns(id, pageSize, (pageNo - 1) * pageSize);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        m.put("total", total);
        m.put("page", pageNo);
        m.put("totalPages", totalPages);
        return m;
    }

    @GetMapping("/mail-status")
    public Map<String, Object> mailStatus() {
        return mailService.status();
    }

    private String connectionName(Long id) {
        return connectionRepo.findById(id).map(c -> c.getName()).orElse("-");
    }

    private void applyBody(CompareSchedule s, ScheduleBody b) {
        if (b.name() == null || b.name().isBlank()) throw new IllegalArgumentException("任务名称不能为空");
        if (b.sourceConnectionId() == null || b.targetConnectionId() == null)
            throw new IllegalArgumentException("源/目标数据源不能为空");
        String time = b.dailyTime() != null ? b.dailyTime().trim() : "08:00";
        if (!time.matches("\\d{1,2}:\\d{2}")) throw new IllegalArgumentException("执行时间格式必须为 HH:mm");
        if (!"STRUCTURE".equalsIgnoreCase(b.compareType()) && !"BOTH".equalsIgnoreCase(b.compareType()))
            throw new IllegalArgumentException("比对类型仅支持 STRUCTURE/BOTH");
        s.setName(b.name().trim());
        s.setSourceConnectionId(b.sourceConnectionId());
        s.setTargetConnectionId(b.targetConnectionId());
        s.setDailyTime(time);
        s.setCompareType(b.compareType().toUpperCase());
        s.setIncludeTables(b.includeTables());
        s.setExcludeTables(b.excludeTables());
        s.setNotifyEmails(b.notifyEmails());
        s.setEnabled(b.enabled() == null || b.enabled());
    }
}
