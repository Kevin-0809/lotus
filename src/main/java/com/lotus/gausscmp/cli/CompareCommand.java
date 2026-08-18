package com.lotus.gausscmp.cli;

import com.lotus.gausscmp.compare.data.DataComparator;
import com.lotus.gausscmp.compare.diff.*;
import com.lotus.gausscmp.compare.structure.StructureComparator;
import com.lotus.gausscmp.config.*;
import com.lotus.gausscmp.connection.DualDataSource;
import com.lotus.gausscmp.metadata.*;
import com.lotus.gausscmp.concurrency.TableTaskExecutor;
import com.lotus.gausscmp.report.*;
import com.lotus.gausscmp.sync.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.*;
import java.util.logging.Logger;

public final class CompareCommand implements Runnable {
    private static final Logger LOG = Logger.getLogger(CompareCommand.class.getName());
    private final CompareConfig config;
    private final Path configFile;
    private int exitCode = 0;

    public CompareCommand(Path configFile, CompareConfig config) {
        this.configFile = configFile;
        this.config = config;
    }

    @Override
    public void run() {
        try {
            exitCode = doCompare();
        } catch (Exception e) {
            LOG.severe("比对失败: " + e.getMessage());
            e.printStackTrace();
            exitCode = 2;
        }
    }

    public int getExitCode() { return exitCode; }

    private int doCompare() throws Exception {
        var opts = config.options();
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        Path outputDir = Path.of(opts.output().dir(), timestamp);
        Files.createDirectories(outputDir);

        try (var ds = new DualDataSource(config.source(), config.target(), opts.parallelism())) {
            MetadataReader reader = new OpenGaussMetadataReader();
            SchemaSnapshot srcSnap, tgtSnap;
            try (Connection sc = ds.source().getConnection(); Connection tc = ds.target().getConnection()) {
                srcSnap = reader.read(sc, config.source().schema(), opts.tableFilter().include(), opts.tableFilter().exclude());
                tgtSnap = reader.read(tc, config.target().schema(), opts.tableFilter().include(), opts.tableFilter().exclude());
            }
            StructureDiffResult structResult = new StructureComparator().compare(srcSnap, tgtSnap);
            Map<String, TableStructureDiff> structMap = new LinkedHashMap<>();
            for (TableStructureDiff d : structResult.tableDiffs()) structMap.put(d.tableName(), d);

            List<String> dataTables = new ArrayList<>();
            for (TableStructureDiff d : structResult.tableDiffs()) {
                if (d.status() == TableStructureStatus.CONSISTENT && d.existsInSource() && d.existsInTarget()) {
                    TableMeta tm = srcSnap.tables().stream().filter(t -> t.name().equals(d.tableName())).findFirst().orElse(null);
                    if (tm != null && tm.hasPrimaryKeyOrUnique()) dataTables.add(d.tableName());
                }
            }
            List<TableDataDiff> dataDiffs = new ArrayList<>();
            if (!dataTables.isEmpty()) {
                DataComparator dataCmp = new DataComparator(config.source(), config.target(),
                    opts.chunkSize(), opts.checksumFunction(), opts.drillDown(), opts.maxDisplayRows());
                try (var exec = new TableTaskExecutor<TableDataDiff>(opts.parallelism())) {
                    Map<String, TableDataDiff> results = exec.execute(dataTables, table -> {
                        try (Connection sc = ds.source().getConnection(); Connection tc = ds.target().getConnection()) {
                            TableMeta tm = srcSnap.tables().stream().filter(t -> t.name().equals(table)).findFirst().orElseThrow();
                            List<String> keys = tm.primaryKey().map(ConstraintMeta::columns)
                                .orElseGet(() -> tm.constraints().stream().filter(c -> c.type() == ConstraintType.UNIQUE).findFirst()
                                    .map(ConstraintMeta::columns).orElse(List.of()));
                            if (keys.isEmpty()) keys = tm.indexes().stream().filter(IndexMeta::isUnique).findFirst()
                                .map(IndexMeta::columns).orElse(List.of());
                            return dataCmp.compareTable(sc, tc, table, keys, TableDataStatus.CONSISTENT);
                        } catch (Exception e) { LOG.warning("数据比对异常: " + table + " " + e.getMessage()); return null; }
                    });
                    for (String table : dataTables) {
                        TableDataDiff d = results.get(table);
                        if (d != null) dataDiffs.add(d);
                    }
                }
            }
            List<String> allTables = new ArrayList<>();
            srcSnap.tables().forEach(t -> allTables.add(t.name()));
            tgtSnap.tables().forEach(t -> { if (!allTables.contains(t.name())) allTables.add(t.name()); });
            for (String t : allTables) {
                if (dataDiffs.stream().noneMatch(d -> d.tableName().equals(t))) {
                    TableStructureDiff sd = structMap.get(t);
                    String reason = null;
                    if (sd != null && sd.status() != TableStructureStatus.CONSISTENT) reason = "结构不一致";
                    else if (sd != null && sd.existsInSource() && sd.existsInTarget()) {
                        TableMeta tm = srcSnap.tables().stream().filter(x -> x.name().equals(t)).findFirst().orElse(null);
                        if (tm != null && !tm.hasPrimaryKeyOrUnique()) reason = "无主键/唯一键";
                    }
                    dataDiffs.add(new TableDataDiff(t, List.of(), TableDataStatus.SKIPPED, 0, 0, new ChunkStats(0,0,0), List.of(), reason));
                }
            }
            dataDiffs.sort(Comparator.comparing(TableDataDiff::tableName));

            long sConsistent = structResult.tableDiffs().stream().filter(d -> d.status() == TableStructureStatus.CONSISTENT).count();
            long sDifferent = structResult.tableDiffs().stream().filter(d -> d.status() != TableStructureStatus.CONSISTENT).count();
            long dConsistent = dataDiffs.stream().filter(d -> d.status() == TableDataStatus.CONSISTENT).count();
            long dDifferent = dataDiffs.stream().filter(d -> d.status() == TableDataStatus.DIFFERENT).count();
            long dSkipped = dataDiffs.stream().filter(d -> d.status() == TableDataStatus.SKIPPED).count();

            ReportModel report = new ReportModel(config.source().schema(), config.target().schema(),
                sConsistent, sDifferent, dConsistent, dDifferent, dSkipped, structResult, new DataDiffResult(dataDiffs));

            JsonReportSerializer.writeToFile(report, outputDir.resolve("result.json"));
            if (opts.output().html())
                Files.writeString(outputDir.resolve("report.html"), new HtmlReportRenderer().render(report, opts.maxDisplayRows()));
            if (opts.output().ddlScript()) {
                Map<String, TableMeta> srcMap = new HashMap<>();
                srcSnap.tables().forEach(t -> srcMap.put(t.name(), t));
                String ddl = new DdlScriptGenerator(config.target().schema()).generate(structResult.tableDiffs(), srcMap);
                ScriptWriter.write(outputDir, ddl, "", true, false);
            }
            if (opts.output().dmlScript()) {
                String dml = new DmlScriptGenerator(config.target().schema(), opts.syncDirection()).generate(dataDiffs);
                ScriptWriter.write(outputDir, "", dml, false, true);
            }
            LOG.info("比对完成，输出目录: " + outputDir);
            LOG.info(String.format("结构: 一致 %d, 差异 %d | 数据: 一致 %d, 差异 %d, 跳过 %d", sConsistent, sDifferent, dConsistent, dDifferent, dSkipped));
            return (sDifferent > 0 || dDifferent > 0) ? 1 : 0;
        }
    }
}
