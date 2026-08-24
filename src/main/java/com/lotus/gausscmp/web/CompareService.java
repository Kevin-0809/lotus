package com.lotus.gausscmp.web;

import com.lotus.gausscmp.compare.data.DataComparator;
import com.lotus.gausscmp.compare.diff.*;
import com.lotus.gausscmp.compare.structure.StructureComparator;
import com.lotus.gausscmp.config.*;
import com.lotus.gausscmp.connection.DualDataSource;
import com.lotus.gausscmp.metadata.*;
import com.lotus.gausscmp.concurrency.TableTaskExecutor;
import com.lotus.gausscmp.report.ReportModel;
import com.lotus.gausscmp.sync.*;
import com.lotus.gausscmp.web.service.ProgressTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.sql.Connection;
import java.util.*;

@Service
public final class CompareService {
    private static final Logger LOG = LoggerFactory.getLogger(CompareService.class);

    private final ProgressTracker tracker;

    public CompareService(ProgressTracker tracker) {
        this.tracker = tracker;
    }

    public record StructureCompareResult(
        ReportModel report,
        String ddlScript,
        String sequenceDdlScript
    ) {}

    public record DataCompareResult(
        ReportModel report,
        String dmlScript
    ) {}

    public record FullCompareResult(
        ReportModel report,
        String ddlScript,
        String sequenceDdlScript,
        String dmlScript
    ) {}

    public StructureCompareResult compareStructure(SchemaSnapshot srcSnap, SchemaSnapshot tgtSnap,
                                                    String targetSchema, boolean generateDdl,
                                                    List<String> includeTables, List<String> excludeTables,
                                                    int parallelism) {
        return compareStructure(srcSnap, tgtSnap, targetSchema, generateDdl,
            includeTables, excludeTables, parallelism, null);
    }

    public StructureCompareResult compareStructure(SchemaSnapshot srcSnap, SchemaSnapshot tgtSnap,
                                                    String targetSchema, boolean generateDdl,
                                                    List<String> includeTables, List<String> excludeTables,
                                                    int parallelism, StructureDiffResult precomputed) {
        SchemaSnapshot filteredSrc = filterSnapshot(srcSnap, includeTables, excludeTables);
        SchemaSnapshot filteredTgt = filterSnapshot(tgtSnap, includeTables, excludeTables);
        StructureDiffResult structResult = precomputed != null
            ? precomputed
            : new StructureComparator().compare(filteredSrc, filteredTgt, parallelism);

        long sConsistent = structResult.tableDiffs().stream().filter(d -> d.status() == TableStructureStatus.CONSISTENT).count();
        long sDifferent = structResult.tableDiffs().stream().filter(d -> d.status() != TableStructureStatus.CONSISTENT).count();

        ReportModel report = new ReportModel(srcSnap.schemaName(), tgtSnap.schemaName(),
            sConsistent, sDifferent, 0, 0, 0, structResult, new DataDiffResult(List.of()));

        String ddl = null, seqDdl = null;
        if (generateDdl) {
            Map<String, TableMeta> srcMap = new HashMap<>();
            filteredSrc.tables().forEach(t -> srcMap.put(t.name(), t));
            ddl = new DdlScriptGenerator(targetSchema).generate(structResult.tableDiffs(), srcMap);
            seqDdl = new DdlScriptGenerator(targetSchema).generateSequenceDdl(structResult.sequenceDiffs());
        }
        return new StructureCompareResult(report, ddl, seqDdl);
    }

    public DataCompareResult compareData(CompareConfig config, SchemaSnapshot srcSnap,
                                          SchemaSnapshot tgtSnap) throws Exception {
        var opts = config.options();
        StructureDiffResult structResult = new StructureComparator().compare(
            filterSnapshot(srcSnap, opts.tableFilter().include(), opts.tableFilter().exclude()),
            filterSnapshot(tgtSnap, opts.tableFilter().include(), opts.tableFilter().exclude()),
            opts.parallelism());
        return compareData(config, srcSnap, tgtSnap, structResult);
    }

    public DataCompareResult compareData(CompareConfig config, SchemaSnapshot srcSnap,
                                          SchemaSnapshot tgtSnap,
                                          StructureDiffResult structResult) throws Exception {
        return compareData(config, srcSnap, tgtSnap, structResult, null);
    }

    public DataCompareResult compareData(CompareConfig config, SchemaSnapshot srcSnap,
                                          SchemaSnapshot tgtSnap,
                                          StructureDiffResult structResult,
                                          String progressId) throws Exception {
        var opts = config.options();
        long started = System.nanoTime();
        LOG.info("开始数据比对: sourceSchema={}, targetSchema={}, parallelism={}, chunkSize={}, tableTimeout={}s",
            srcSnap.schemaName(), tgtSnap.schemaName(), opts.parallelism(), opts.chunkSize(), opts.tableTimeoutSeconds());
        if (structResult == null) {
            if (progressId != null) tracker.phase(progressId, "结构比对");
            structResult = new StructureComparator().compare(
                filterSnapshot(srcSnap, opts.tableFilter().include(), opts.tableFilter().exclude()),
                filterSnapshot(tgtSnap, opts.tableFilter().include(), opts.tableFilter().exclude()),
                opts.parallelism());
        }

        Map<String, TableStructureDiff> structMap = new LinkedHashMap<>();
        for (TableStructureDiff d : structResult.tableDiffs()) structMap.put(d.tableName(), d);

        List<String> dataTables = resolveDataTables(structResult, srcSnap, opts);
        if (progressId != null) {
            tracker.phase(progressId, "并发数据比对");
            tracker.total(progressId, dataTables.size());
        }
        List<TableDataDiff> dataDiffs;
        try (var ds = new DualDataSource(config.source(), config.target(), opts.parallelism())) {
            dataDiffs = runDataCompare(ds, srcSnap, dataTables, config, opts, progressId);
        }
        if (progressId != null) tracker.phase(progressId, "汇总比对结果");
        fillSkippedTables(structResult, structMap, srcSnap, tgtSnap, dataDiffs, opts);
        dataDiffs.sort(Comparator.comparing(TableDataDiff::tableName));

        long dConsistent = dataDiffs.stream().filter(d -> d.status() == TableDataStatus.CONSISTENT).count();
        long dDifferent = dataDiffs.stream().filter(d -> d.status() == TableDataStatus.DIFFERENT).count();
        long dSkipped = dataDiffs.stream().filter(d -> d.status() == TableDataStatus.SKIPPED).count();

        ReportModel report = new ReportModel(config.source().schema(), config.target().schema(),
            0, 0, dConsistent, dDifferent, dSkipped,
            structResult, new DataDiffResult(dataDiffs));

        String dml = null;
        LOG.info("数据比对完成: tables={}, consistent={}, different={}, skipped={}, durationMs={}",
            dataDiffs.size(), dConsistent, dDifferent, dSkipped, elapsedMs(started));
        return new DataCompareResult(report, dml);
    }

    public FullCompareResult compareBoth(CompareConfig config, SchemaSnapshot srcSnap,
                                          SchemaSnapshot tgtSnap, boolean generateDdl) throws Exception {
        return compareBoth(config, srcSnap, tgtSnap, generateDdl, null);
    }

    public FullCompareResult compareBoth(CompareConfig config, SchemaSnapshot srcSnap,
                                          SchemaSnapshot tgtSnap, boolean generateDdl,
                                          String progressId) throws Exception {
        var opts = config.options();
        var include = opts.tableFilter().include();
        var exclude = opts.tableFilter().exclude();
        if (progressId != null) tracker.phase(progressId, "结构比对");
        StructureDiffResult structResult = new StructureComparator().compare(
            filterSnapshot(srcSnap, include, exclude),
            filterSnapshot(tgtSnap, include, exclude),
            opts.parallelism());

        var structRes = compareStructure(srcSnap, tgtSnap, config.target().schema(), generateDdl,
            include, exclude, opts.parallelism(), structResult);
        var dataRes = compareData(config, srcSnap, tgtSnap, structResult, progressId);

        ReportModel merged = new ReportModel(
            srcSnap.schemaName(), tgtSnap.schemaName(),
            structRes.report().structureConsistent(), structRes.report().structureDifferent(),
            dataRes.report().dataConsistent(), dataRes.report().dataDifferent(), dataRes.report().dataSkipped(),
            structRes.report().structureDiff(), dataRes.report().dataDiff()
        );
        return new FullCompareResult(merged, structRes.ddlScript(), structRes.sequenceDdlScript(), dataRes.dmlScript());
    }

    private List<String> resolveDataTables(StructureDiffResult structResult, SchemaSnapshot srcSnap, OptionsConfig opts) {
        List<String> dataTables = new ArrayList<>();
        var dataCompareTables = opts.dataCompareTables();
        var include = opts.tableFilter() != null ? opts.tableFilter().include() : List.<String>of();
        var exclude = opts.tableFilter() != null ? opts.tableFilter().exclude() : List.<String>of();
        for (TableStructureDiff d : structResult.tableDiffs()) {
            if (d.status() == TableStructureStatus.CONSISTENT && d.existsInSource() && d.existsInTarget()) {
                TableMeta tm = srcSnap.tables().stream().filter(t -> t.name().equals(d.tableName())).findFirst().orElse(null);
                if (tm != null && !resolveKeyColumns(tm).isEmpty()
                    && matchesDataCompareTable(d.tableName(), dataCompareTables)
                    && shouldInclude(d.tableName(), include, exclude)) {
                    dataTables.add(d.tableName());
                }
            }
        }
        return dataTables;
    }

    private static List<String> resolveKeyColumns(TableMeta tm) {
        List<String> keys = tm.primaryKey().map(ConstraintMeta::columns)
            .orElseGet(() -> tm.constraints().stream().filter(c -> c.type() == ConstraintType.UNIQUE).findFirst()
                .map(ConstraintMeta::columns).orElse(List.of()));
        if (keys == null || keys.isEmpty()) {
            keys = tm.indexes().stream().filter(IndexMeta::isUnique).findFirst()
                .map(IndexMeta::columns).orElse(List.of());
        }
        return keys == null ? List.of() : keys;
    }

    private List<TableDataDiff> runDataCompare(DualDataSource ds, SchemaSnapshot srcSnap,
            List<String> dataTables, CompareConfig config, OptionsConfig opts,
            String progressId) throws Exception {
        List<TableDataDiff> dataDiffs = new ArrayList<>();
        if (dataTables.isEmpty()) return dataDiffs;
        Map<String, TableMeta> sourceTables = new HashMap<>();
        srcSnap.tables().forEach(t -> sourceTables.put(t.name(), t));
        DataComparator dataCmp = new DataComparator(config.source(), config.target(),
            opts.chunkSize(), opts.checksumFunction(), opts.drillDown(), opts.maxDisplayRows());
        try (var exec = new TableTaskExecutor<TableDataDiff>(opts.parallelism(), "data-cmp")) {
            Map<String, TableDataDiff> results = exec.execute(dataTables, table -> {
                try (Connection sc = ds.source().getConnection(); Connection tc = ds.target().getConnection()) {
                    TableMeta tm = sourceTables.get(table);
                    if (tm == null) throw new IllegalStateException("源端表元数据不存在: " + table);
                    List<String> keys = resolveKeyColumns(tm);
                    if (keys.isEmpty()) throw new IllegalStateException("表缺少可用主键/唯一键: " + table);
                    return dataCmp.compareTable(sc, tc, table, keys, TableDataStatus.CONSISTENT);
                } catch (Exception e) {
                    throw new RuntimeException("数据比对失败: " + table, e);
                } finally {
                    if (progressId != null) tracker.increment(progressId);
                }
            }, opts.tableTimeoutSeconds());
            for (String table : dataTables) {
                TableDataDiff d = results.get(table);
                if (d != null) {
                    dataDiffs.add(d);
                } else {
                    dataDiffs.add(new TableDataDiff(table, List.of(), TableDataStatus.SKIPPED,
                        0, 0, new ChunkStats(0, 0, 0), List.of(), "比对任务失败或超时"));
                }
            }
        }
        return dataDiffs;
    }

    private void fillSkippedTables(StructureDiffResult structResult, Map<String, TableStructureDiff> structMap,
            SchemaSnapshot srcSnap, SchemaSnapshot tgtSnap, List<TableDataDiff> dataDiffs, OptionsConfig opts) {
        List<String> allTables = new ArrayList<>();
        srcSnap.tables().forEach(t -> allTables.add(t.name()));
        tgtSnap.tables().forEach(t -> { if (!allTables.contains(t.name())) allTables.add(t.name()); });
        for (String t : allTables) {
            if (dataDiffs.stream().anyMatch(d -> d.tableName().equals(t))) continue;
            TableStructureDiff sd = structMap.get(t);
            String reason;
            if (opts.dataCompareTables().isEmpty()) {
                reason = "未配置数据比对表清单";
            } else if (sd == null) {
                reason = "未通过表过滤条件";
            } else if (sd.status() != TableStructureStatus.CONSISTENT) {
                reason = "结构不一致";
            } else if (sd.existsInSource() && sd.existsInTarget()) {
                TableMeta tm = srcSnap.tables().stream().filter(x -> x.name().equals(t)).findFirst().orElse(null);
                if (tm == null) {
                    reason = "源端表元数据不存在";
                } else if (resolveKeyColumns(tm).isEmpty()) {
                    reason = "无主键/唯一键";
                } else if (!matchesDataCompareTable(t, opts.dataCompareTables())) {
                    reason = "未在数据比对表清单中";
                } else {
                    reason = "比对任务失败或超时";
                }
            } else {
                reason = "单侧存在";
            }
            dataDiffs.add(new TableDataDiff(t, List.of(), TableDataStatus.SKIPPED, 0, 0, new ChunkStats(0,0,0), List.of(), reason));
        }
    }

    private static boolean matchesDataCompareTable(String tableName, List<String> patterns) {
        if (patterns.isEmpty()) return false;
        for (String p : patterns) {
            try {
                if (tableName.matches(p)) return true;
            } catch (Exception e) {
                if (tableName.startsWith(p)) return true;
            }
        }
        return false;
    }

    private SchemaSnapshot filterSnapshot(SchemaSnapshot snap, List<String> include, List<String> exclude) {
        if ((include == null || include.isEmpty()) && (exclude == null || exclude.isEmpty())) return snap;
        List<TableMeta> filtered = snap.tables().stream()
            .filter(t -> shouldInclude(t.name(), include, exclude))
            .toList();
        return new SchemaSnapshot(snap.schemaName(), filtered, snap.sequences());
    }

    private static boolean shouldInclude(String name, List<String> include, List<String> exclude) {
        if (exclude != null) {
            for (String p : exclude) {
                try { if (name.matches(p)) return false; }
                catch (Exception e) { if (name.startsWith(p)) return false; }
            }
        }
        if (include != null && !include.isEmpty()) {
            for (String p : include) {
                try { if (name.matches(p)) return true; }
                catch (Exception e) { if (name.startsWith(p)) return true; }
            }
            return false;
        }
        return true;
    }

    private static long elapsedMs(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
