package com.lotus.gausscmp.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lotus.gausscmp.config.*;
import com.lotus.gausscmp.metadata.SchemaSnapshot;
import com.lotus.gausscmp.report.ReportModel;
import com.lotus.gausscmp.web.CompareRequest;
import com.lotus.gausscmp.web.CompareService;
import com.lotus.gausscmp.web.entity.*;
import com.lotus.gausscmp.web.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** 每日定时比对：先采集两端元数据，再执行比对，统计各阶段耗时并邮件通知 */
@Service
public class ScheduleCompareService {
    private static final Logger LOG = LoggerFactory.getLogger(ScheduleCompareService.class);

    private final ScheduleRepository scheduleRepo;
    private final ScheduleRunRepository runRepo;
    private final ConnectionRepository connectionRepo;
    private final HistoryRepository historyRepo;
    private final MetadataCollectService collectService;
    private final MetadataStoreService storeService;
    private final CompareService compareService;
    private final CompareTableConfigRepository tableConfigRepo;
    private final NotifyMailService mailService;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<Long, ReentrantLock> runLocks = new ConcurrentHashMap<>();

    public ScheduleCompareService(ScheduleRepository scheduleRepo,
                                  ScheduleRunRepository runRepo,
                                  ConnectionRepository connectionRepo,
                                  HistoryRepository historyRepo,
                                  MetadataCollectService collectService,
                                  MetadataStoreService storeService,
                                  CompareService compareService,
                                  CompareTableConfigRepository tableConfigRepo,
                                  NotifyMailService mailService,
                                  ObjectMapper objectMapper) {
        this.scheduleRepo = scheduleRepo;
        this.runRepo = runRepo;
        this.connectionRepo = connectionRepo;
        this.historyRepo = historyRepo;
        this.collectService = collectService;
        this.storeService = storeService;
        this.compareService = compareService;
        this.tableConfigRepo = tableConfigRepo;
        this.mailService = mailService;
        this.objectMapper = objectMapper;
    }

    /** 每 30 秒扫描一次到期的启用任务（同一天不重复执行） */
    @Scheduled(fixedDelay = 30_000, initialDelay = 20_000)
    public void scanAndTrigger() {
        LocalTime now = LocalTime.now();
        for (CompareSchedule s : scheduleRepo.findByEnabledTrue()) {
            LocalTime daily;
            try {
                daily = LocalTime.parse(s.getDailyTime());
            } catch (Exception e) {
                LOG.warn("定时任务时间格式非法: id={}, dailyTime={}", s.getId(), s.getDailyTime());
                continue;
            }
            boolean alreadyRunToday = s.getLastRunAt() != null
                && s.getLastRunAt().toLocalDate().equals(LocalDate.now());
            if (alreadyRunToday || now.isBefore(daily)) continue;
            try {
                execute(s.getId());
            } catch (Exception e) {
                LOG.error("定时比对执行失败: scheduleId={}", s.getId(), e);
            }
        }
    }

    /** 手动触发（立即执行） */
    public CompareScheduleRun runNow(Long scheduleId) {
        return execute(scheduleId);
    }

    public CompareScheduleRun execute(Long scheduleId) {
        CompareSchedule s = scheduleRepo.findById(scheduleId)
            .orElseThrow(() -> new NoSuchElementException("任务不存在: " + scheduleId));
        ReentrantLock lock = runLocks.computeIfAbsent(scheduleId, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new IllegalStateException("该任务正在执行中: " + s.getName());
        }
        try {
            CompareScheduleRun run = new CompareScheduleRun();
            run.setScheduleId(scheduleId);
            run.setStartedAt(LocalDateTime.now());
            run.setStatus("RUNNING");
            run = runRepo.saveAndFlush(run);
            long totalStart = System.currentTimeMillis();
            try {
                DbConnection src = connectionRepo.findById(s.getSourceConnectionId())
                    .orElseThrow(() -> new NoSuchElementException("源数据源不存在: " + s.getSourceConnectionId()));
                DbConnection tgt = connectionRepo.findById(s.getTargetConnectionId())
                    .orElseThrow(() -> new NoSuchElementException("目标数据源不存在: " + s.getTargetConnectionId()));

                // 阶段1: 采集源端元数据
                long t0 = System.currentTimeMillis();
                collectService.collect(src.getId(), null);
                long collectSrcMs = System.currentTimeMillis() - t0;
                run.setCollectSourceMs(collectSrcMs);

                // 阶段2: 采集目标端元数据
                t0 = System.currentTimeMillis();
                collectService.collect(tgt.getId(), null);
                long collectTgtMs = System.currentTimeMillis() - t0;
                run.setCollectTargetMs(collectTgtMs);

                // 阶段3: 比对
                t0 = System.currentTimeMillis();
                SchemaSnapshot srcSnap = storeService.loadSnapshot(src.getId());
                SchemaSnapshot tgtSnap = storeService.loadSnapshot(tgt.getId());
                var resolvedFilters = ScheduleTableConfigResolver.resolve(
                    tableConfigRepo.findAllByOrderByTableTypeAscTableNameAsc(),
                    splitCsv(s.getIncludeTables()), splitCsv(s.getExcludeTables()));
                List<String> include = resolvedFilters.include();
                List<String> exclude = resolvedFilters.exclude();
                String compareType = "BOTH".equalsIgnoreCase(s.getCompareType()) ? "BOTH" : "STRUCTURE";
                ReportModel report;
                String ddlScript = null, seqDdl = null, dmlScript = null;
                if ("BOTH".equals(compareType)) {
                    CompareConfig config = buildConfig(src, tgt, include, exclude, resolvedFilters.dataTables());
                    var r = compareService.compareBoth(config, srcSnap, tgtSnap, true, null);
                    report = r.report();
                    ddlScript = r.ddlScript();
                    seqDdl = r.sequenceDdlScript();
                    dmlScript = r.dmlScript();
                } else {
                    var r = compareService.compareStructure(srcSnap, tgtSnap, tgt.getSchema(), true,
                        include, exclude, 8);
                    report = r.report();
                    ddlScript = r.ddlScript();
                    seqDdl = r.sequenceDdlScript();
                }
                long compareMs = System.currentTimeMillis() - t0;
                run.setCompareMs(compareMs);
                run.setStructureConsistent((long) report.structureConsistent());
                run.setStructureDifferent((long) report.structureDifferent());
                run.setDataConsistent((long) report.dataConsistent());
                run.setDataDifferent((long) report.dataDifferent());
                run.setDataSkipped((long) report.dataSkipped());
                saveHistory(compareType, src, tgt, s, include, exclude, report, ddlScript, dmlScript, seqDdl);

                // 阶段4: 邮件通知
                t0 = System.currentTimeMillis();
                mailService.sendCompareResult(s.getNotifyEmails(), s.getName(), "SUCCESS",
                    report.structureConsistent(), report.structureDifferent(),
                    report.dataConsistent(), report.dataDifferent(), report.dataSkipped(),
                    collectSrcMs, collectTgtMs, compareMs,
                    System.currentTimeMillis() - totalStart, null, report);
                run.setNotifyMs(System.currentTimeMillis() - t0);

                run.setTotalMs(System.currentTimeMillis() - totalStart);
                run.setStatus("SUCCESS");
                run.setFinishedAt(LocalDateTime.now());
                run = runRepo.save(run);
                updateScheduleStatus(s, "SUCCESS");
                LOG.info("定时比对完成: schedule={}, srcCollect={}ms, tgtCollect={}ms, compare={}ms, total={}ms",
                    s.getName(), collectSrcMs, collectTgtMs, compareMs, run.getTotalMs());
                return run;
            } catch (Exception e) {
                run.setStatus("FAILED");
                run.setErrorMsg(truncate(e.getMessage()));
                run.setTotalMs(System.currentTimeMillis() - totalStart);
                run.setFinishedAt(LocalDateTime.now());
                run = runRepo.save(run);
                updateScheduleStatus(s, "FAILED");
                try {
                    mailService.sendCompareResult(s.getNotifyEmails(), s.getName(), "FAILED",
                        0, 0, 0, 0, 0,
                        run.getCollectSourceMs() == null ? 0 : run.getCollectSourceMs(),
                        run.getCollectTargetMs() == null ? 0 : run.getCollectTargetMs(),
                        run.getCompareMs() == null ? 0 : run.getCompareMs(),
                        run.getTotalMs(), e.getMessage(), null);
                } catch (Exception ignore) {}
                LOG.error("定时比对失败: scheduleId={}, name={}", s.getId(), s.getName(), e);
                return run;
            }
        } finally {
            lock.unlock();
            runLocks.remove(scheduleId, lock);
        }
    }

    private void updateScheduleStatus(CompareSchedule s, String status) {
        s.setLastRunAt(LocalDateTime.now());
        s.setLastStatus(status);
        scheduleRepo.save(s);
    }

    private CompareConfig buildConfig(DbConnection src, DbConnection tgt,
                                      List<String> include, List<String> exclude, List<String> dataTables) {
        SourceConfig source = new SourceConfig(src.getUrl(), src.getUsername(), src.getPassword(), src.getSchema(), true);
        SourceConfig target = new SourceConfig(tgt.getUrl(), tgt.getUsername(), tgt.getPassword(), tgt.getSchema(), true);
        OptionsConfig opts = new OptionsConfig(8, 5000, true, "md5",
            new TableFilterConfig(include, exclude), "source-to-target", 1000, 0, dataTables);
        return new CompareConfig(source, target, opts);
    }

    private void saveHistory(String compareType, DbConnection src, DbConnection tgt, CompareSchedule s,
                             List<String> include, List<String> exclude, ReportModel report,
                             String ddl, String dml, String seqDdl) {
        try {
            CompareHistory h = new CompareHistory();
            h.setCompareType(compareType);
            h.setSourceConnectionId(src.getId());
            h.setSourceConnectionName(src.getName());
            h.setTargetConnectionId(tgt.getId());
            h.setTargetConnectionName(tgt.getName());
            h.setSourceSchema(src.getSchema());
            h.setTargetSchema(tgt.getSchema());
            h.setOptionsJson(objectMapper.writeValueAsString(new CompareRequest(
                src.getId(), tgt.getId(), 8, 5000, true, "md5", "source-to-target",
                1000, 0L, include, exclude, List.of(), true, false, null)));
            h.setStructureConsistent(report.structureConsistent());
            h.setStructureDifferent(report.structureDifferent());
            h.setDataConsistent(report.dataConsistent());
            h.setDataDifferent(report.dataDifferent());
            h.setDataSkipped(report.dataSkipped());
            h.setStatus("SUCCESS");
            h.setReportJson(objectMapper.writeValueAsString(report));
            String fullDdl = "";
            if (seqDdl != null && !seqDdl.isEmpty()) fullDdl += seqDdl + "\n\n";
            if (ddl != null) fullDdl += ddl;
            h.setDdlScript(fullDdl.isEmpty() ? null : fullDdl);
            h.setDmlScript(dml);
            historyRepo.save(h);
        } catch (Exception e) {
            LOG.warn("定时比对保存历史失败: schedule={}", s.getName(), e);
        }
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split("[,;]")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 3900 ? s.substring(0, 3900) : s;
    }
}
