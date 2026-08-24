package com.lotus.gausscmp.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lotus.gausscmp.compare.data.ChecksumCalculator;
import com.lotus.gausscmp.config.*;
import com.lotus.gausscmp.metadata.SchemaSnapshot;
import com.lotus.gausscmp.web.entity.CompareHistory;
import com.lotus.gausscmp.web.entity.DbConnection;
import com.lotus.gausscmp.web.repository.ConnectionRepository;
import com.lotus.gausscmp.web.repository.HistoryRepository;
import com.lotus.gausscmp.web.repository.CompareTableConfigRepository;
import com.lotus.gausscmp.web.entity.CompareTableConfig;
import com.lotus.gausscmp.web.service.MetadataStoreService;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class CompareController {
    private static final Logger LOG = LoggerFactory.getLogger(CompareController.class);

    private final ConnectionRepository connectionRepo;
    private final HistoryRepository historyRepo;
    private final MetadataStoreService storeService;
    private final ObjectMapper objectMapper;
    private final CompareService compareService;
    private final com.lotus.gausscmp.web.service.ProgressTracker progressTracker;
    private final CompareTableConfigRepository tableConfigRepo;

    public CompareController(ConnectionRepository connectionRepo,
                            HistoryRepository historyRepository,
                            MetadataStoreService storeService,
                            ObjectMapper objectMapper,
                            CompareService compareService,
                            com.lotus.gausscmp.web.service.ProgressTracker progressTracker,
                            CompareTableConfigRepository tableConfigRepo) {
        this.connectionRepo = connectionRepo;
        this.historyRepo = historyRepository;
        this.storeService = storeService;
        this.objectMapper = objectMapper;
        this.compareService = compareService;
        this.progressTracker = progressTracker;
        this.tableConfigRepo = tableConfigRepo;
    }

    @PostMapping("/compare/structure")
    public CompareService.StructureCompareResult compareStructure(@RequestBody CompareRequest req) throws Exception {
        DbConnection src = connectionRepo.findById(req.sourceConnectionId())
            .orElseThrow(() -> new NoSuchElementException("源数据源不存在: " + req.sourceConnectionId()));
        DbConnection tgt = connectionRepo.findById(req.targetConnectionId())
            .orElseThrow(() -> new NoSuchElementException("目标数据源不存在: " + req.targetConnectionId()));
        requireCollected(src);
        requireCollected(tgt);

        SchemaSnapshot srcSnap = storeService.loadSnapshot(src.getId());
        SchemaSnapshot tgtSnap = storeService.loadSnapshot(tgt.getId());

        boolean ddl = Boolean.TRUE.equals(req.generateDdl());
        List<String> include = effectiveInclude(req);
        List<String> exclude = effectiveExclude(req);
        CompareConfig config = buildConfig(src, tgt, req);
        String progressId = beginProgress(req);
        if (progressId != null) progressTracker.phase(progressId, "结构比对");
        try {
            var result = compareService.compareStructure(srcSnap, tgtSnap, tgt.getSchema(), ddl,
                include, exclude, config.options().parallelism());
            finishProgress(progressId);
            saveHistory("STRUCTURE", src, tgt, req, result.report(), result.ddlScript(), null, result.sequenceDdlScript());
            return result;
        } catch (Exception e) {
            failProgress(progressId, e);
            throw e;
        }
    }

    @PostMapping("/compare/data")
    public CompareService.DataCompareResult compareData(@RequestBody CompareRequest req) throws Exception {
        DbConnection src = connectionRepo.findById(req.sourceConnectionId())
            .orElseThrow(() -> new NoSuchElementException("源数据源不存在: " + req.sourceConnectionId()));
        DbConnection tgt = connectionRepo.findById(req.targetConnectionId())
            .orElseThrow(() -> new NoSuchElementException("目标数据源不存在: " + req.targetConnectionId()));

        SchemaSnapshot srcSnap = storeService.loadSnapshot(src.getId());
        SchemaSnapshot tgtSnap = storeService.loadSnapshot(tgt.getId());
        CompareConfig config = buildConfig(src, tgt, req);

        String progressId = beginProgress(req);
        try {
            var result = compareService.compareData(config, srcSnap, tgtSnap, null, progressId);
            finishProgress(progressId);
            saveHistory("DATA", src, tgt, req, result.report(), null, result.dmlScript(), null);
            return result;
        } catch (Exception e) {
            failProgress(progressId, e);
            throw e;
        }
    }

    @PostMapping("/compare/both")
    public CompareService.FullCompareResult compareBoth(@RequestBody CompareRequest req) throws Exception {
        DbConnection src = connectionRepo.findById(req.sourceConnectionId())
            .orElseThrow(() -> new NoSuchElementException("源数据源不存在: " + req.sourceConnectionId()));
        DbConnection tgt = connectionRepo.findById(req.targetConnectionId())
            .orElseThrow(() -> new NoSuchElementException("目标数据源不存在: " + req.targetConnectionId()));
        requireCollected(src);
        requireCollected(tgt);

        SchemaSnapshot srcSnap = storeService.loadSnapshot(src.getId());
        SchemaSnapshot tgtSnap = storeService.loadSnapshot(tgt.getId());
        CompareConfig config = buildConfig(src, tgt, req);

        String progressId = beginProgress(req);
        try {
            boolean ddl = Boolean.TRUE.equals(req.generateDdl());
            var result = compareService.compareBoth(config, srcSnap, tgtSnap, ddl, progressId);
            finishProgress(progressId);
            saveHistory("BOTH", src, tgt, req, result.report(), result.ddlScript(), result.dmlScript(),
                         result.sequenceDdlScript());
            return result;
        } catch (Exception e) {
            failProgress(progressId, e);
            throw e;
        }
    }

    private String beginProgress(CompareRequest req) {
        String id = req.progressId();
        if (id == null || id.isBlank()) return null;
        progressTracker.register(id, "准备比对");
        return id;
    }

    private void finishProgress(String progressId) {
        if (progressId != null) progressTracker.complete(progressId);
    }

    private void failProgress(String progressId, Exception e) {
        if (progressId != null) progressTracker.fail(progressId, e.getMessage());
    }

    @GetMapping("/health")
    public String health() { return "ok"; }

    private void requireCollected(DbConnection c) {
        if (c.getCollectedAt() == null) {
            throw new IllegalStateException("数据源尚未采集元数据: " + c.getName());
        }
    }

    private CompareConfig buildConfig(DbConnection src, DbConnection tgt, CompareRequest req) {
        SourceConfig source = new SourceConfig(src.getUrl(), src.getUsername(), src.getPassword(), src.getSchema(), true);
        SourceConfig target = new SourceConfig(tgt.getUrl(), tgt.getUsername(), tgt.getPassword(), tgt.getSchema(), true);

        List<String> include = effectiveInclude(req);
        List<String> exclude = effectiveExclude(req);
        List<String> configuredParameters = exactConfiguredNames(CompareTableConfig.TableType.PARAMETER);
        List<String> dataTables = configuredParameters.isEmpty()
            ? (req.dataCompareTables() != null ? req.dataCompareTables() : List.of()) : configuredParameters;

        String checksumFunction = req.checksumFunction() != null ? req.checksumFunction() : "md5";
        if (!ChecksumCalculator.ALLOWED_HASH_FUNCTIONS.contains(
                checksumFunction.trim().toLowerCase())) {
            throw new IllegalArgumentException(
                "不支持的校验和函数: " + checksumFunction + "，仅支持: " + String.join("/", ChecksumCalculator.ALLOWED_HASH_FUNCTIONS));
        }

        OptionsConfig opts = new OptionsConfig(
            normalizeParallelism(req.parallelism()),
            req.chunkSize() != null ? req.chunkSize() : 5000,
            req.drillDown() != null ? req.drillDown() : true,
            checksumFunction.trim().toLowerCase(),
            new TableFilterConfig(include, exclude),
            req.syncDirection() != null ? req.syncDirection() : "source-to-target",
            req.maxDisplayRows() != null ? req.maxDisplayRows() : 1000,
            req.tableTimeoutSeconds() != null ? Math.max(0, req.tableTimeoutSeconds()) : 0,
            dataTables
        );
        return new CompareConfig(source, target, opts);
    }

    private List<String> effectiveInclude(CompareRequest req) {
        List<String> configured = exactConfiguredNames(CompareTableConfig.TableType.PARAMETER);
        if (!configured.isEmpty()) return configured;
        return req.includeTables() != null ? req.includeTables() : List.of();
    }

    private List<String> effectiveExclude(CompareRequest req) {
        List<String> result = new ArrayList<>(req.excludeTables() != null ? req.excludeTables() : List.of());
        result.addAll(exactConfiguredNames(CompareTableConfig.TableType.EXCLUDE));
        return result;
    }

    private List<String> exactConfiguredNames(CompareTableConfig.TableType type) {
        return tableConfigRepo.findByEnabledTrueAndTableType(type).stream()
            .map(CompareTableConfig::getTableName)
            .map(java.util.regex.Pattern::quote)
            .toList();
    }

    private void saveHistory(String compareType, DbConnection src, DbConnection tgt,
                             CompareRequest req, com.lotus.gausscmp.report.ReportModel report,
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
            h.setOptionsJson(objectMapper.writeValueAsString(req));
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
            LOG.warn("保存比对历史失败: compareType={}, sourceId={}, targetId={}, error={}",
                compareType, src.getId(), tgt.getId(), e.toString(), e);
        }
    }

    private static int normalizeParallelism(Integer requested) {
        int defaultValue = 16;
        int value = requested == null ? defaultValue : requested;
        return Math.max(1, Math.min(value, 16));
    }
}
