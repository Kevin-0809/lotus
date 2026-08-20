package com.lotus.gausscmp.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lotus.gausscmp.config.*;
import com.lotus.gausscmp.metadata.SchemaSnapshot;
import com.lotus.gausscmp.web.entity.CompareHistory;
import com.lotus.gausscmp.web.entity.DbConnection;
import com.lotus.gausscmp.web.repository.ConnectionRepository;
import com.lotus.gausscmp.web.repository.HistoryRepository;
import com.lotus.gausscmp.web.service.MetadataStoreService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class CompareController {

    private final ConnectionRepository connectionRepo;
    private final HistoryRepository historyRepo;
    private final MetadataStoreService storeService;
    private final ObjectMapper objectMapper;

    public CompareController(ConnectionRepository connectionRepo,
                            HistoryRepository historyRepository,
                            MetadataStoreService storeService,
                            ObjectMapper objectMapper) {
        this.connectionRepo = connectionRepo;
        this.historyRepo = historyRepository;
        this.storeService = storeService;
        this.objectMapper = objectMapper;
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
        List<String> include = req.includeTables() != null ? req.includeTables() : List.of();
        List<String> exclude = req.excludeTables() != null ? req.excludeTables() : List.of();
        var result = new CompareService().compareStructure(srcSnap, tgtSnap, tgt.getSchema(), ddl, include, exclude);

        saveHistory("STRUCTURE", src, tgt, req, result.report(), result.ddlScript(), null, result.sequenceDdlScript());
        return result;
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

        var result = new CompareService().compareData(config, srcSnap, tgtSnap);
        saveHistory("DATA", src, tgt, req, result.report(), null, result.dmlScript(), null);
        return result;
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

        boolean ddl = Boolean.TRUE.equals(req.generateDdl());
        var result = new CompareService().compareBoth(config, srcSnap, tgtSnap, ddl);
        saveHistory("BOTH", src, tgt, req, result.report(), result.ddlScript(), result.dmlScript(),
                     result.sequenceDdlScript());
        return result;
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

        List<String> include = req.includeTables() != null ? req.includeTables() : List.of();
        List<String> exclude = req.excludeTables() != null ? req.excludeTables() : List.of();
        List<String> dataTables = req.dataCompareTables() != null ? req.dataCompareTables() : List.of();

        OutputConfig output = new OutputConfig("./report", false, false, false);
        OptionsConfig opts = new OptionsConfig(
            req.parallelism() != null ? req.parallelism() : 1,
            req.chunkSize() != null ? req.chunkSize() : 5000,
            req.drillDown() != null ? req.drillDown() : true,
            req.checksumFunction() != null ? req.checksumFunction() : "md5",
            new TableFilterConfig(include, exclude),
            output,
            req.syncDirection() != null ? req.syncDirection() : "source-to-target",
            req.maxDisplayRows() != null ? req.maxDisplayRows() : 1000,
            0,
            dataTables
        );
        return new CompareConfig(source, target, opts);
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
        } catch (Exception ignored) { }
    }
}
