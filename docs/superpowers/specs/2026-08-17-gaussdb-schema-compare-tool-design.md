# GaussDB Schema 数据结构与数据比对工具 — 设计文档

- 日期：2026-08-17
- 状态：已确认（待生成实施计划）
- 作者：Codex（brainstorming 协作产出）

## 1. 背景与目标

### 1.1 核心场景
GaussDB → GaussDB 迁移/同步校验：源库与目标库均为 GaussDB（openGauss 内核，集中式部署，PostgreSQL 协议兼容），在迁移或同步完成后，校验指定 schema 的数据结构与数据是否一致。

### 1.2 范围
- **结构比对**：表/列、约束（主键/唯一/外键/检查）、索引（普通/唯一/部分/表达式）。
- **数据比对**：表行数据，按主键或唯一键分块校验和 + 不一致块下钻逐行。
- **不包含**（本期）：分区表的分区策略/键/分区定义差异比对——分区父表按普通表处理，数据跨分区整体比对；视图/函数/存储过程/触发器/序列。

### 1.3 成功标准
- 能准确识别结构与数据差异，并定位到具体对象/行。
- 生成可执行的同步 DDL/DML 脚本，执行后重新比对应一致。
- 纯 CLI，YAML 配置 + 环境变量，便于集成到迁移/CI 流程。
- 中小规模（<100 表 / 单表 <1000 万行 / <100GB）下稳定运行。

## 2. 总体架构与模块划分

### 2.1 技术栈
- Java 17 + Maven
- JDBC：openGauss 驱动 `org.opengauss:opengauss-jdbc`（PG 协议兼容），URL 形如 `jdbc:opengauss://host:port/db`
- HikariCP 连接池
- picocli（CLI）
- Jackson（YAML/JSON）
- HTML：轻量字符串模板（无重依赖）
- 测试：JUnit 5 + AssertJ + Testcontainers（openGauss 镜像）

### 2.2 包结构（`com.lotus.gausscmp`）
```
config/        配置加载（YAML + ${ENV} 占位符插值）与校验
connection/    HikariCP 双数据源（source/target），只读
metadata/      元数据抽取：SchemaSnapshot = 表/列/约束/索引
               readers/ 按对象类型从系统目录读取
compare/       比对引擎
   structure/  StructureComparator + diff 模型
   data/       DataComparator / Chunker / ChecksumCalculator / DrillDownComparator
   diff/       StructureDiff、DataDiff、DiffType 枚举
sync/          DdlScriptGenerator、DmlScriptGenerator、ScriptWriter
report/        ReportModel、HtmlReportRenderer、JsonReportSerializer
concurrency/   TableTaskExecutor（有界线程池 + CompletableFuture）、ResultAggregator
cli/           Main(picocli)、CompareCommand
```

### 2.3 进程流程
加载配置 → 初始化双连接池（只读）→ 抽取两端元数据快照 → 结构比对（产出 StructureDiff，标记结构不一致表）→ 数据比对（仅对结构一致且有主键/唯一键的表，分块校验和 + 下钻）→ 聚合 ReportModel → 生成 JSON/HTML/DDL/DML → 关池，按结果退出（0=一致 / 1=有差异 / 2=出错）。

### 2.4 架构方案
选定 **方案 A：单进程 + 可配置表级并行**。一个 JVM 进程，JDBC 连两端；元数据驱动；表级并行度可配（默认 1=顺序，可调高），有界线程池 + 连接池；按表聚合结果保证报告顺序。中小规模下顺序模式已足够，可配置并行给未来留扩展空间。

### 2.5 关键边界
抽取、比对、输出三阶段解耦，各自单一职责。比对引擎只产出 `diff` 模型对象，不直接碰 IO；输出层消费 `diff` 模型生成报告与脚本，便于独立测试。

## 3. 配置与连接管理

### 3.1 配置文件（YAML，默认 `compare.yaml`，`-c` 可覆盖）
```yaml
source:
  host: 10.0.0.1
  port: 5432
  database: prod
  username: ro_user
  password: ${SOURCE_DB_PASSWORD}   # 环境变量占位符，未设置则报错
  schema: app_schema
  readOnly: true                     # 强制 SET TRANSACTION READ ONLY

target:
  host: 10.0.0.2
  port: 5432
  database: prod
  username: ro_user
  password: ${TARGET_DB_PASSWORD}
  schema: app_schema
  readOnly: true

options:
  parallelism: 1                     # 表级并行度，默认顺序
  chunkSize: 5000                    # 数据比对每块行数
  drillDown: true                    # 校验和不一致时下钻逐行
  checksumFunction: md5              # md5|sha256
  tableFilter:                       # 可选，正则；为空则比对 schema 下全部表
    include: [".*"]
    exclude: ["^tmp_.*", "^bak_.*"]
  output:
    dir: ./report                    # 输出目录
    html: true
    ddlScript: true
    dmlScript: true
```

### 3.2 环境变量插值
`${VAR}` 由 `ConfigLoader` 用 `System.getenv()` 解析。未设置的非空敏感字段（password）→ 启动报错退出码 2。非敏感字段可给默认值 `${VAR:-5432}`。

### 3.3 连接管理（`connection/DualDataSource`）
- HikariCP 双池，`source`/`target` 各一个；只读账号 + 连接级 `SET TRANSACTION READ ONLY`（若 DB 拒绝设置则记 WARN 继续，不阻断）。
- 连接池参数：`maximumPoolSize = max(2, parallelism+1)`，`readOnly=true`，`connectionTimeout=30s`，`idleTimeout=10min`。
- 关闭：Runtime shutdown hook 兜底关池；正常流程在比对完成后主动 `close()`。

### 3.4 只读约束
工具不执行任何 DDL/DML 于源/目标库；DDL/DML 仅作为脚本写入输出目录，由人工审核后执行。

## 4. 元数据抽取

### 4.1 目标
从两端 schema 抽取 `SchemaSnapshot`，作为比对输入。抽取是只读、幂等的。

### 4.2 核心模型
```
SchemaSnapshot
  └─ schemaName: String
  └─ tables: List<TableMeta>          // 仅普通表 + 分区父表（当普通表处理）

TableMeta
  ├─ name: String
  ├─ comment: String?
  ├─ columns: List<ColumnMeta>
  ├─ constraints: List<ConstraintMeta>
  ├─ indexes: List<IndexMeta>
  ├─ primaryKey: Optional<ConstraintMeta>   // 便捷引用，数据比对键来源
  ├─ hasPrimaryKeyOrUnique: boolean         // 决定是否参与数据比对
  └─ partitioned: boolean                   // 仅标记，不抽取分区细节

ColumnMeta
  ├─ name, dataType(规范化), nullable, defaultValue, comment, ordinal

ConstraintMeta
  ├─ name, type(PRIMARY|UNIQUE|FOREIGN|CHECK), definition, columns[]
  └─ (CHECK 存 normalized 表达式文本；FOREIGN 存 references 表/列)

IndexMeta
  ├─ name, tableName, columns[], isUnique, isPartial, whereClause?, definition
```

### 4.3 抽取 SQL 来源（集中式 openGauss，按 PG 系统目录）
- 表：`pg_class`/`pg_namespace`/`pg_description`，`relkind='r'`（普通表）+ 分区父表；排除分区子表（`pg_class.relispartition`）。
- 列：`pg_attribute` + `pg_attrdef` + `format_type(atttypid, atttypmod)` 规范化类型。
- 约束：`pg_constraint`（`contype` 区分 p/u/c/f），`pg_get_constraintdef()` 取定义文本。
- 索引：`pg_indexes` + `pg_index`（`indisunique`、`indpred` 部分索引条件）。
- 注释：`pg_description`/`obj_description()`。

### 4.4 类型规范化（`metadata/TypeNormalizer`）
统一 `int4`→`integer`、`varchar(10)`→`character varying(10)`、`timestamp without time zone` 保留全称；处理 `numeric(p,s)` 精度、`varchar` 无长度默认、数组类型 `int[]`→`integer[]`。规范化规则可配（`typeAlias` 映射表），默认覆盖常见 openGauss 类型。

### 4.5 表过滤
抽取阶段按 `tableFilter.include/exclude`（正则）过滤，减少比对范围。

### 4.6 实现要点
- `metadata/MetadataReader` 接口 + `OpenGaussMetadataReader` 实现；后续异构源可扩展。
- 抽取失败的单表：记录 `ExtractionError`，不中断整体流程；该表在比对阶段标记 `EXTRACTION_FAILED`，跳过结构 + 数据比对。
- 抽取结果可序列化为 JSON 快照（`--export-snapshot`），支持离线比对扩展（当前不实现离线模式，模型预留）。

## 5. 结构比对引擎

### 5.1 输入输出
输入：两端 `SchemaSnapshot`。输出：`StructureDiffResult`（每表一个 `TableStructureDiff`）。

### 5.2 比对流程（`StructureComparator`）
1. **表集合对齐**（按表名）：
   - 仅源有 → `TABLE_MISSING_IN_TARGET`
   - 仅目标有 → `TABLE_EXTRA_IN_TARGET`
   - 两端都有 → 进入逐项比对
2. **逐表比对**（顺序：表注释 → 列 → 约束 → 索引）：
   - 列：按列名对齐。仅源有 → `COLUMN_MISSING_IN_TARGET`；仅目标有 → `COLUMN_EXTRA_IN_TARGET`；两端都有 → 比对 `dataType`(规范化后)、`nullable`、`defaultValue`(normalized)、`comment`；任一不同 → `COLUMN_MISMATCH`，记录字段级差异。
   - 约束：按约束名对齐（openGauss 约束名 schema 内唯一）。缺失/多余 → `CONSTRAINT_MISSING/EXTRA`；两端都有 → 比对 `type` + `definition`(normalized)；不同 → `CONSTRAINT_MISMATCH`。
   - 索引：按索引名对齐。缺失/多余 → `INDEX_MISSING/EXTRA`；两端都有 → 比对 `columns`、`isUnique`、`isPartial`、`whereClause`、`definition`(normalized)；不同 → `INDEX_MISMATCH`。
3. **default/expression 规范化**（`metadata/DefinitionNormalizer`）：
   - 默认值：去空白、统一引号、`now()`↔`current_timestamp` 等常见等价映射（可配 `defaultValueAlias`）。
   - CHECK/索引定义：去多余空白、统一大小写关键字、去括号冗余空格。
   - 目的：消除表述差异，只报真实语义差异。

### 5.3 DiffType 枚举（结构部分）
```
TABLE_MISSING_IN_TARGET, TABLE_EXTRA_IN_TARGET,
COLUMN_MISSING_IN_TARGET, COLUMN_EXTRA_IN_TARGET, COLUMN_MISMATCH,
CONSTRAINT_MISSING_IN_TARGET, CONSTRAINT_EXTRA_IN_TARGET, CONSTRAINT_MISMATCH,
INDEX_MISSING_IN_TARGET, INDEX_EXTRA_IN_TARGET, INDEX_MISMATCH,
TABLE_COMMENT_MISMATCH
```

### 5.4 TableStructureDiff 结构
```
TableStructureDiff
  ├─ tableName: String
  ├─ existsInSource/Target: boolean
  ├─ status: CONSISTENT | DIFFERENT | EXTRACTION_FAILED
  ├─ columnDiffs: List<ColumnDiff>
  ├─ constraintDiffs: List<ConstraintDiff>
  ├─ indexDiffs: List<IndexDiff>
  └─ commentDiff: Optional<StringDiff>
```

### 5.5 与数据比对的衔接
`status != CONSISTENT` 的表，数据比对阶段跳过并在报告中标注"结构不一致，数据未比对"。`EXTRACTION_FAILED` 同理跳过。

### 5.6 并发
结构比对本身顺序执行（元数据已在内存，开销低）；并发留给数据比对阶段。

## 6. 数据比对引擎

### 6.1 输入输出
输入：结构比对标记为 `CONSISTENT` 且 `hasPrimaryKeyOrUnique=true` 的表。输出：`DataDiffResult`（每表一个 `TableDataDiff`）。

### 6.2 比对键确定
- 优先用主键列；无主键则取第一个唯一约束/唯一索引的列。
- 无主键且无唯一约束 → 跳过，标记 `NO_COMPARABLE_KEY`（沿用已确认策略）。
- 比对键列名记入 `TableDataDiff.keyColumns`。

### 6.3 阶段一：分块校验和

#### 分块（`data/Chunker`）
按 `chunkSize`（默认 5000）对主键排序后分块：`SELECT pk1,pk2 FROM t ORDER BY pk1,pk2 LIMIT ? OFFSET ?`。默认用 OFFSET 方案（中小规模足够）；`--chunk-strategy=range` 可切键范围分块模式。

#### 校验和计算（`ChecksumCalculator`）
每块在各自数据库内计算聚合校验和，只回传标量，减少网络传输：
```sql
SELECT count(*) AS row_cnt,
       md5(string_agg(md5(t::text), ',' ORDER BY pk1,pk2)) AS chk
FROM (SELECT * FROM t WHERE pk1,pk2 IN <块范围> ORDER BY pk1,pk2) t
```
- `t::text` 把整行规范化为文本再 md5，保证列顺序与类型表述一致。
- `string_agg` 按 pk 排序保证顺序无关。
- `checksumFunction` 可配 md5/sha256。
- 行计数 + 校验和双重比对：count 不等直接判差异块，count 等但 chk 不等也判差异。

#### 块比对（源块 vs 目标块）
- 块数量不同 → 表行数不同，整表标记差异，进入下钻。
- 逐块比 `row_cnt` + `chk`：一致 → `CONSISTENT_CHUNK`；不一致 → 标记差异块，进入下钻。

### 6.4 阶段二：下钻逐行（`DrillDownComparator`）
仅对差异块执行：
1. 拉取该块两端全量行（`SELECT * FROM t WHERE pk IN <块范围> ORDER BY pk`），在 Java 侧构造 `Map<pkTuple, RowData>`。
2. 按 pk 对齐：
   - 仅源有 → `ROW_MISSING_IN_TARGET`
   - 仅目标有 → `ROW_EXTRA_IN_TARGET`
   - 两端都有 → 逐列比 `value::text`（规范化后）；不同 → `ROW_MISMATCH`，记录差异列。
3. 行级差异聚合到 `TableDataDiff.rowDiffs`。

### 6.5 同步方向
默认源为基准、目标同步至源；生成的 DML 即"让目标=源"。`--sync-direction=source-to-target`（默认）/`target-to-source` 可配。

### 6.6 DiffType 枚举（数据部分）
```
NO_COMPARABLE_KEY, TABLE_ROW_COUNT_MISMATCH,
CHUNK_MISMATCH, ROW_MISSING_IN_TARGET, ROW_EXTRA_IN_TARGET, ROW_MISMATCH
```

### 6.7 TableDataDiff 结构
```
TableDataDiff
  ├─ tableName, keyColumns[]
  ├─ status: CONSISTENT | DIFFERENT | SKIPPED(无键/结构不一致)
  ├─ sourceRowCount, targetRowCount
  ├─ chunkStats: {total, consistent, mismatched}
  ├─ rowDiffs: List<RowDiff>   // 仅差异块下钻结果
  └─ skippedReason: String?
```

### 6.8 并发
`TableTaskExecutor` 用 `parallelism` 个工作线程并发处理多表数据比对；每表内部顺序分块。有界线程池 + `CompletableFuture`；连接池 `maximumPoolSize = parallelism+1` 保证每表两端各一连接。结果按表名排序归并。

### 6.9 性能与安全
- 大结果集用 JDBC `fetchSize`（默认 1000）流式读取，避免 OOM。
- 下钻只拉差异块（默认 ≤5000 行），内存可控。
- 全程只读 SELECT，不锁表（不使用 `FOR SHARE/UPDATE`）。

## 7. 输出与报告生成

### 7.1 输出目录结构（默认 `./report/<timestamp>/`）
```
report/20260817-153012/
  ├─ result.json              # 机器可读完整结果（驱动 HTML 与脚本生成）
  ├─ report.html              # 人可读 HTML 报告
  ├─ ddl_sync.sql             # 结构同步脚本
  ├─ dml_sync.sql             # 数据同步脚本
  └─ run.log                  # 运行日志
```

### 7.2 HTML 报告（`report/HtmlReportRenderer`）
单文件，内嵌 CSS/JS，无外部依赖。
- **摘要区**：源/目标信息、schema、比对时间、耗时；结构一致/差异表数、数据一致/差异/跳过表数；通过率。
- **结构差异页签**：按表分组，展示 `TableStructureDiff`；列/约束/索引差异用表格列出差类型 + 详情；支持按"仅结构不一致"过滤。
- **数据差异页签**：按表展示 `TableDataDiff`；行计数对比、块统计、差异行明细（前 N 行，默认 1000，超出截断并提示）；支持按表/差异类型过滤。
- **跳过表清单**：列出结构不一致、无主键、抽取失败的表及原因。
- 交互：页签切换 + 简单过滤（纯 JS，无框架），差异项高亮。

差异行截断：HTML 中每表最多展示 1000 条差异行（`--max-display-rows` 可配），完整差异在 `result.json` 与 `dml_sync.sql` 中不截断。

### 7.3 结构同步 DDL（`sync/DdlScriptGenerator`）
生成原则：以源为基准，生成让目标结构 = 源的脚本。
- `TABLE_MISSING_IN_TARGET` → `CREATE TABLE ...`（含列、约束、注释；索引单独 `CREATE INDEX`）。
- `TABLE_EXTRA_IN_TARGET` → 注释为 `-- DROP TABLE target.t;`（默认不自动删，需人工启用 `--include-drop`）。
- `COLUMN_MISSING_IN_TARGET` → `ALTER TABLE t ADD COLUMN ...`。
- `COLUMN_EXTRA_IN_TARGET` → `-- ALTER TABLE t DROP COLUMN ...`（注释，默认不执行）。
- `COLUMN_MISMATCH` → 按差异字段生成 `ALTER TABLE t ALTER COLUMN ... TYPE/SET NOT NULL/SET DEFAULT/DROP DEFAULT`，注释更新用 `COMMENT ON COLUMN`。
- 约束差异 → `ALTER TABLE ADD/DROP CONSTRAINT`。
- 索引差异 → `CREATE INDEX` / `DROP INDEX`。
- 每条 DDL 前加注释说明差异类型与源端值；脚本顶部 `SET search_path`；按表名排序，表内按 列→约束→索引 顺序。

事务包装：每个对象的 DDL 独立，不自动包大事务（避免部分失败难定位）；脚本头部注明"请人工审核后执行"。

### 7.4 数据同步 DML（`sync/DmlScriptGenerator`）
生成原则：以源为基准，让目标数据 = 源。
- `ROW_MISSING_IN_TARGET` → `INSERT INTO t (...) VALUES (...)`。
- `ROW_EXTRA_IN_TARGET` → `DELETE FROM t WHERE pk=...`。
- `ROW_MISMATCH` → `UPDATE t SET col=... WHERE pk=...`（仅更新差异列）。
- 按 pk 顺序排序，同表内 INSERT/UPDATE/DELETE 分组。
- 值用 JDBC 参数化文本表示（字符串转义、NULL 显式 `NULL`、二进制用 `decode('...','hex')`），保证脚本可直接执行。
- 脚本头部 `SET search_path` + 警告注释；不截断（完整输出所有差异行）。

### 7.5 result.json（`report/JsonReportSerializer`）
完整 `ReportModel` 序列化，含配置快照、两端快照摘要、全部 `StructureDiffResult` + `DataDiffResult`；作为机器可读权威结果，供后续集成或二次分析。

### 7.6 退出码
- `0`：全部一致。
- `1`：存在差异（含结构或数据差异）。
- `2`：配置/连接/抽取等错误，未能完成比对。

## 8. 错误处理与日志

### 8.1 错误分级与策略
| 类别 | 处理 | 退出码影响 |
|------|------|-----------|
| 配置错误（缺字段/密码未设/格式错） | 启动即失败，打印明确提示 | 2 |
| 连接失败（任一端不可达/认证失败） | 启动即失败 | 2 |
| 单表抽取失败 | 记录 `ExtractionError`，该表标记 `EXTRACTION_FAILED` 跳过，继续其余表 | 不阻断，全部失败则 2 |
| 单表结构比对异常 | 标记 `COMPARISON_ERROR` 跳过，继续 | 不阻断 |
| 单表数据比对异常（SQL 错误/超时） | 标记 `DATA_COMPARISON_ERROR`，已产出部分差异保留，继续其余表 | 不阻断 |
| 块校验和计算失败 | 该表数据标记 `SKIPPED`+原因，不中断 | 不阻断 |
| 下钻 OOM/超行 | 差异块截断，标记 `DRILLDOWN_TRUNCATED` | 不阻断 |
| 输出写失败（磁盘满/权限） | 终止，打印路径 | 2 |

核心原则：单表失败不阻断整体；致命错误（配置/连接/输出）立即终止。最终退出码优先级 2 > 1 > 0。

### 8.2 日志（`run.log` + 控制台）
- SLF4J + java.util.logging（无额外依赖）；级别：INFO 默认，`--verbose` 开 DEBUG。
- 内容：启动配置（脱敏密码）、连接建立、各表抽取/结构比对/数据比对进度与耗时、块级校验和差异（DEBUG）、单表失败堆栈（WARN）、最终摘要。
- 密码脱敏：日志与 `result.json` 中连接信息一律 `***`。

### 8.3 超时控制
- 连接超时 30s（HikariCP `connectionTimeout`）。
- 单表数据比对 `--table-timeout`（默认 0=不限）；超时该表标记 `TIMEOUT` 跳过。

## 9. 测试策略

### 9.1 单元测试（JUnit 5 + AssertJ）
- `metadata/TypeNormalizer`：覆盖 int4→integer、varchar 无长度、numeric(p,s)、数组、timestamp 等等价归一。
- `metadata/DefinitionNormalizer`：默认值/CHECK/索引定义的空白、大小写、等价映射。
- `compare/structure/StructureComparator`：用构造的 `SchemaSnapshot` 对比对，覆盖各 DiffType（缺失/多余/不匹配/一致）。
- `compare/data/Chunker`：分块边界、块数量、OFFSET 逻辑。
- `compare/data/DrillDownComparator`：用内存 Map 模拟两端行集，覆盖 MISSING/EXTRA/MISMATCH。
- `sync/DdlScriptGenerator`、`sync/DmlScriptGenerator`：给定 diff 模型，断言生成脚本内容。
- `report/HtmlReportRenderer`：断言 HTML 含关键摘要、差异项、截断提示。
- `config/ConfigLoader`：YAML 解析、`${ENV}` 插值、缺失变量报错、默认值语法。

### 9.2 集成测试（Testcontainers，openGauss 镜像）
启动两个 openGauss 容器作源/目标；建 schema + 真实 DDL/DML。场景覆盖：
1. 结构 + 数据完全一致 → 退出码 0。
2. 结构差异（缺表/缺列/类型不匹配/约束/索引差异）→ 结构报告 + DDL 脚本可执行回灌目标使其一致。
3. 数据差异（缺行/多行/行不匹配）→ DML 脚本可执行回灌目标使其一致。
4. 无主键表 → 标记跳过。
5. 分区父表当普通表 → 数据跨分区比对正常。
6. 并行度 >1 → 结果与顺序一致，无连接泄漏。

关键断言：DDL/DML 脚本执行后，重新比对退出码应为 0（端到端验证脚本正确性）。连接池用 HikariCP `metrics` 断言无泄漏。

### 9.3 不测
openGauss 驱动本身、HikariCP 内部、picocli 解析（信任框架）。

### 9.4 测试数据规模
集成测试用小表（百级行），重点验证逻辑而非性能。

## 10. 范围与未决事项

### 10.1 本期不包含
- 分区表的分区策略/键/分区定义差异比对（分区父表按普通表处理）。
- 视图/函数/存储过程/触发器/序列的结构比对。
- 异构库（MySQL/Oracle 等）源端支持（接口预留 `MetadataReader` 扩展点）。
- 离线比对模式（模型预留 `--export-snapshot`，不实现）。
- Web UI / REST API。

### 10.2 未来可扩展
- 分区表分区信息比对（新增 `PARTITION_*` DiffType 与分区元数据抽取）。
- 异构源适配器。
- 离线快照比对。
- 超大规模下的范围分块与采样降级。
