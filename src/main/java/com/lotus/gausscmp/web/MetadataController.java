package com.lotus.gausscmp.web;

import com.lotus.gausscmp.web.entity.*;
import com.lotus.gausscmp.web.repository.*;
import com.lotus.gausscmp.web.service.MetadataCollectService;
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
    private final ConnectionRepository connectionRepo;
    private final MetaTableRepository tableRepo;
    private final MetaColumnRepository columnRepo;
    private final MetaConstraintRepository constraintRepo;
    private final MetaIndexRepository indexRepo;
    private final MetaSequenceRepository sequenceRepo;
    private final MetaPartitionRepository partitionRepo;

    public MetadataController(MetadataCollectService collectService,
                              MetadataStoreService storeService,
                              ConnectionRepository connectionRepo,
                              MetaTableRepository tableRepo,
                              MetaColumnRepository columnRepo,
                              MetaConstraintRepository constraintRepo,
                              MetaIndexRepository indexRepo,
                              MetaSequenceRepository sequenceRepo,
                              MetaPartitionRepository partitionRepo) {
        this.collectService = collectService;
        this.storeService = storeService;
        this.connectionRepo = connectionRepo;
        this.tableRepo = tableRepo;
        this.columnRepo = columnRepo;
        this.constraintRepo = constraintRepo;
        this.indexRepo = indexRepo;
        this.sequenceRepo = sequenceRepo;
        this.partitionRepo = partitionRepo;
    }

    @PostMapping("/collect/{connId}")
    public Map<String, Object> collect(@PathVariable Long connId) throws Exception {
        var result = collectService.collect(connId);
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
    public List<Map<String, Object>> tables(@PathVariable Long connId) {
        List<MetaTable> tables = tableRepo.findByConnectionIdOrderByTableNameAsc(connId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (MetaTable t : tables) {
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
            result.add(m);
        }
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
}
