package com.lotus.gausscmp.web;

import com.lotus.gausscmp.web.entity.*;
import com.lotus.gausscmp.web.repository.*;
import com.lotus.gausscmp.web.service.MetadataCollectService;
import com.lotus.gausscmp.web.service.MetadataHistoryService;
import com.lotus.gausscmp.web.service.MetadataStoreService;
import com.lotus.gausscmp.metadata.SchemaSnapshot;
import com.lotus.gausscmp.metadata.TableMeta;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/metadata")
@CrossOrigin
public class MetadataController {

    private final MetadataCollectService collectService;
    private final MetadataStoreService storeService;
    private final MetadataHistoryService historyService;
    private final ConnectionRepository connectionRepo;
    private final MetaTableRepository tableRepo;
    private final MetaColumnRepository columnRepo;
    private final MetaConstraintRepository constraintRepo;
    private final MetaIndexRepository indexRepo;
    private final MetaSequenceRepository sequenceRepo;
    private final MetaPartitionRepository partitionRepo;
    private final com.lotus.gausscmp.web.service.ProgressTracker progressTracker;

    public MetadataController(MetadataCollectService collectService,
                              MetadataStoreService storeService,
                              MetadataHistoryService historyService,
                              ConnectionRepository connectionRepo,
                              MetaTableRepository tableRepo,
                              MetaColumnRepository columnRepo,
                              MetaConstraintRepository constraintRepo,
                              MetaIndexRepository indexRepo,
                              MetaSequenceRepository sequenceRepo,
                              MetaPartitionRepository partitionRepo,
                              com.lotus.gausscmp.web.service.ProgressTracker progressTracker) {
        this.collectService = collectService;
        this.storeService = storeService;
        this.historyService = historyService;
        this.connectionRepo = connectionRepo;
        this.tableRepo = tableRepo;
        this.columnRepo = columnRepo;
        this.constraintRepo = constraintRepo;
        this.indexRepo = indexRepo;
        this.sequenceRepo = sequenceRepo;
        this.partitionRepo = partitionRepo;
        this.progressTracker = progressTracker;
    }

    @PostMapping("/collect/{connId}")
    public Map<String, Object> collect(@PathVariable Long connId,
                                       @RequestBody(required = false) Map<String, String> body) throws Exception {
        String progressId = null;
        if (body != null && body.get("progressId") != null && !body.get("progressId").isBlank()) {
            progressId = body.get("progressId");
            progressTracker.register(progressId, "连接数据库");
        }
        var result = collectService.collect(connId, progressId);
        return Map.of(
            "success", true,
            "tableCount", result.tableCount,
            "sequenceCount", result.sequenceCount,
            "durationMs", result.durationMs
        );
    }

    @GetMapping("/{connId}/overview")
    public Map<String, Object> overview(@PathVariable Long connId) {
        DbConnection conn = connectionRepo.findById(connId).orElseThrow();
        List<MetaTable> tables = tableRepo.findByConnectionIdOrderByTableNameAsc(connId);
        List<MetaSequence> seqs = sequenceRepo.findByConnectionIdOrderBySequenceNameAsc(connId);
        return Map.of(
            "connectionId", connId,
            "connectionName", conn.getName(),
            "schema", conn.getSchema(),
            "collectedAt", conn.getCollectedAt() != null ? conn.getCollectedAt().toString() : null,
            "tableCount", tables.size(),
            "sequenceCount", seqs.size(),
            "partitionedTableCount", tables.stream().filter(MetaTable::isPartitioned).count()
        );
    }

    @GetMapping("/{connId}/tables")
    public Map<String, Object> tables(@PathVariable Long connId,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size,
                                      @RequestParam(required = false) String search) {
        List<MetaTable> all = tableRepo.findByConnectionIdOrderByTableNameAsc(connId);
        String kw = search == null ? "" : search.trim().toLowerCase();
        List<MetaTable> filtered = kw.isEmpty() ? all
            : all.stream().filter(t -> t.getTableName().toLowerCase().contains(kw)).toList();
        int total = filtered.size();
        int pageSize = Math.max(1, Math.min(size, 200));
        int totalPages = Math.max(1, (total + pageSize - 1) / pageSize);
        int pageNo = Math.min(Math.max(1, page), totalPages);
        List<MetaTable> pageItems = filtered.stream()
            .skip((long) (pageNo - 1) * pageSize)
            .limit(pageSize)
            .toList();

        List<Map<String, Object>> items = new ArrayList<>();
        for (MetaTable t : pageItems) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("tableName", t.getTableName());
            m.put("comment", t.getComment());
            m.put("partitioned", t.isPartitioned());
            m.put("partitionStrategy", t.getPartitionStrategy());
            m.put("partitionKey", t.getPartitionKey());
            m.put("columnCount", columnRepo.findByTableIdOrderByOrdinalAsc(t.getId()).size());
            m.put("constraintCount", constraintRepo.findByTableId(t.getId()).size());
            m.put("indexCount", indexRepo.findByTableId(t.getId()).size());
            m.put("partitionCount", partitionRepo.findByTableIdOrderByOrdinalAsc(t.getId()).size());
            items.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total);
        result.put("page", pageNo);
        result.put("pageSize", pageSize);
        result.put("totalPages", totalPages);
        return result;
    }

    @GetMapping("/{connId}/tables/{tableName}")
    public Map<String, Object> tableDetail(@PathVariable Long connId, @PathVariable String tableName) {
        List<MetaTable> tables = tableRepo.findByConnectionIdAndTableName(connId, tableName);
        if (tables.isEmpty()) throw new NoSuchElementException("表不存在: " + tableName);
        MetaTable t = tables.get(0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tableName", t.getTableName());
        m.put("comment", t.getComment());
        m.put("partitioned", t.isPartitioned());
        m.put("partitionStrategy", t.getPartitionStrategy());
        m.put("partitionKey", t.getPartitionKey());

        List<Map<String, Object>> columns = new ArrayList<>();
        for (MetaColumn c : columnRepo.findByTableIdOrderByOrdinalAsc(t.getId())) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("name", c.getColumnName());
            cm.put("dataType", c.getDataType());
            cm.put("nullable", c.isNullable());
            cm.put("defaultValue", c.getDefaultValue());
            cm.put("comment", c.getComment());
            cm.put("ordinal", c.getOrdinal());
            columns.add(cm);
        }
        m.put("columns", columns);

        List<Map<String, Object>> constraints = new ArrayList<>();
        for (MetaConstraint c : constraintRepo.findByTableId(t.getId())) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("name", c.getConstraintName());
            cm.put("type", c.getType());
            cm.put("definition", c.getDefinition());
            cm.put("columns", c.getColumns());
            cm.put("refTable", c.getRefTable());
            constraints.add(cm);
        }
        m.put("constraints", constraints);

        List<Map<String, Object>> indexes = new ArrayList<>();
        for (MetaIndex i : indexRepo.findByTableId(t.getId())) {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("name", i.getIndexName());
            im.put("columns", i.getColumns());
            im.put("unique", i.isUnique());
            im.put("partial", i.isPartial());
            im.put("whereClause", i.getWhereClause());
            im.put("definition", i.getDefinition());
            indexes.add(im);
        }
        m.put("indexes", indexes);

        List<Map<String, Object>> partitions = new ArrayList<>();
        for (MetaPartition p : partitionRepo.findByTableIdOrderByOrdinalAsc(t.getId())) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.getPartitionName());
            pm.put("parentName", p.getParentName());
            pm.put("ordinal", p.getOrdinal());
            pm.put("boundaryExpr", p.getBoundaryExpr());
            pm.put("subPartition", p.isSubPartition());
            pm.put("tablespace", p.getTablespace());
            pm.put("estimatedRows", p.getEstimatedRows());
            partitions.add(pm);
        }
        m.put("partitions", partitions);
        return m;
    }

    @GetMapping("/{connId}/sequences")
    public List<Map<String, Object>> sequences(@PathVariable Long connId) {
        List<MetaSequence> seqs = sequenceRepo.findByConnectionIdOrderBySequenceNameAsc(connId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (MetaSequence s : seqs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.getSequenceName());
            m.put("dataType", s.getDataType());
            m.put("startValue", s.getStartValue());
            m.put("incrementBy", s.getIncrementBy());
            m.put("minValue", s.getMinValue());
            m.put("maxValue", s.getMaxValue());
            m.put("cacheSize", s.getCacheSize());
            m.put("cycle", s.isCycle());
            result.add(m);
        }
        return result;
    }

    @GetMapping("/{connId}/snapshot")
    public Map<String, Object> snapshot(@PathVariable Long connId) {
        SchemaSnapshot snap = storeService.loadSnapshot(connId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaName", snap.schemaName());
        m.put("tableCount", snap.tables().size());
        m.put("sequenceCount", snap.sequences() != null ? snap.sequences().size() : 0);
        List<String> tableNames = new ArrayList<>();
        for (TableMeta t : snap.tables()) tableNames.add(t.name());
        m.put("tables", tableNames);
        return m;
    }

    /* ===== 历史快照查询 ===== */

    /** 快照列表（最新在前，is_current 标记当前版本） */
    @GetMapping("/{connId}/snapshots")
    public List<Map<String, Object>> snapshots(@PathVariable Long connId) {
        return historyService.listSnapshots(connId);
    }

    /** 表历史链：不传 columnName 返回表级版本；传 columnName 返回该字段的逐快照定义 */
    @GetMapping("/{connId}/tables/{tableName}/history")
    public List<Map<String, Object>> tableHistory(@PathVariable Long connId,
                                                  @PathVariable String tableName,
                                                  @RequestParam(required = false) String columnName) {
        if (columnName != null && !columnName.isBlank()) {
            return historyService.columnHistory(connId, tableName, columnName.trim());
        }
        return historyService.tableHistory(connId, tableName);
    }

    /** 某快照下某表的完整明细 */
    @GetMapping("/{connId}/snapshots/{snapshotId}/tables/{tableName}")
    public Map<String, Object> snapshotTable(@PathVariable Long connId,
                                             @PathVariable long snapshotId,
                                             @PathVariable String tableName) {
        return historyService.snapshotTableDetail(connId, snapshotId, tableName);
    }

    /** 两个快照间的表结构差异 */
    @GetMapping("/{connId}/history-diff")
    public Map<String, Object> historyDiff(@PathVariable Long connId,
                                           @RequestParam long oldSnapshotId,
                                           @RequestParam long newSnapshotId,
                                           @RequestParam String tableName) {
        return historyService.diffSnapshots(connId, oldSnapshotId, newSnapshotId, tableName);
    }
}
