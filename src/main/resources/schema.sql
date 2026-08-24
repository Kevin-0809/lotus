
-- openGauss 平台库初始化脚本。
-- 脚本可重复执行，应用启动时由 Spring SQL 初始化执行。
-- 注意: lotus schema 需预先由 DBA 创建（应用账号通常无建 schema 权限）。
SET search_path TO lotus;

CREATE TABLE IF NOT EXISTS db_connection (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    url VARCHAR(500) NOT NULL,
    username VARCHAR(100) NOT NULL,
    password VARCHAR(200),
    schema_name VARCHAR(100) NOT NULL,
    remark VARCHAR(200),
    created_at TIMESTAMP NOT NULL,
    collected_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS compare_history (
    id BIGSERIAL PRIMARY KEY,
    source_connection_id BIGINT,
    source_connection_name VARCHAR(100),
    target_connection_id BIGINT,
    target_connection_name VARCHAR(100),
    source_schema VARCHAR(100),
    target_schema VARCHAR(100),
    options_json VARCHAR(4000),
    structure_consistent BIGINT NOT NULL DEFAULT 0,
    structure_different BIGINT NOT NULL DEFAULT 0,
    data_consistent BIGINT NOT NULL DEFAULT 0,
    data_different BIGINT NOT NULL DEFAULT 0,
    data_skipped BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(20),
    compare_type VARCHAR(10),
    error_msg VARCHAR(2000),
    report_json TEXT,
    ddl_script TEXT,
    dml_script TEXT,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS compare_table_config (
    id BIGSERIAL PRIMARY KEY,
    table_name VARCHAR(200) NOT NULL,
    table_type VARCHAR(20) NOT NULL CHECK (table_type IN ('PARAMETER', 'EXCLUDE')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    remark VARCHAR(500),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_compare_table_config_name_type UNIQUE (table_name, table_type)
);
CREATE INDEX IF NOT EXISTS idx_compare_table_config_type_enabled ON compare_table_config (table_type, enabled);
COMMENT ON TABLE lotus.compare_table_config IS '比对参数表与排除表配置';

CREATE TABLE IF NOT EXISTS meta_table (
    id BIGSERIAL PRIMARY KEY,
    connection_id BIGINT NOT NULL,
    connection_name VARCHAR(200) NOT NULL,
    schema_name VARCHAR(100) NOT NULL,
    table_name VARCHAR(200) NOT NULL,
    comment VARCHAR(1000),
    partitioned BOOLEAN NOT NULL DEFAULT FALSE,
    partition_strategy VARCHAR(1),
    partition_key VARCHAR(500),
    partition_count INTEGER,
    collected_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS meta_sequence (
    id BIGSERIAL PRIMARY KEY,
    connection_id BIGINT NOT NULL,
    connection_name VARCHAR(200) NOT NULL,
    schema_name VARCHAR(100) NOT NULL,
    sequence_name VARCHAR(200) NOT NULL,
    data_type VARCHAR(50),
    start_value BIGINT,
    increment_by BIGINT,
    min_value BIGINT,
    max_value BIGINT,
    cache_size BIGINT,
    cycle BOOLEAN NOT NULL DEFAULT FALSE,
    collected_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS meta_column (
    id BIGSERIAL PRIMARY KEY,
    table_id BIGINT NOT NULL,
    connection_id BIGINT NOT NULL,
    connection_name VARCHAR(200) NOT NULL,
    schema_name VARCHAR(100) NOT NULL,
    table_name VARCHAR(200) NOT NULL,
    column_name VARCHAR(200) NOT NULL,
    data_type VARCHAR(200) NOT NULL,
    nullable BOOLEAN NOT NULL DEFAULT FALSE,
    default_value VARCHAR(1000),
    comment VARCHAR(1000),
    ordinal INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS meta_constraint (
    id BIGSERIAL PRIMARY KEY,
    table_id BIGINT NOT NULL,
    connection_id BIGINT NOT NULL,
    connection_name VARCHAR(200) NOT NULL,
    schema_name VARCHAR(100) NOT NULL,
    table_name VARCHAR(200) NOT NULL,
    constraint_name VARCHAR(200) NOT NULL,
    type VARCHAR(1) NOT NULL,
    definition VARCHAR(2000),
    columns VARCHAR(500),
    ref_table VARCHAR(200)
);

CREATE TABLE IF NOT EXISTS meta_index (
    id BIGSERIAL PRIMARY KEY,
    table_id BIGINT NOT NULL,
    connection_id BIGINT NOT NULL,
    connection_name VARCHAR(200) NOT NULL,
    schema_name VARCHAR(100) NOT NULL,
    table_name VARCHAR(200) NOT NULL,
    index_name VARCHAR(200) NOT NULL,
    columns VARCHAR(500),
    is_unique BOOLEAN NOT NULL DEFAULT FALSE,
    is_partial BOOLEAN NOT NULL DEFAULT FALSE,
    where_clause VARCHAR(2000),
    definition VARCHAR(2000)
);

CREATE TABLE IF NOT EXISTS meta_partition (
    id BIGSERIAL PRIMARY KEY,
    table_id BIGINT NOT NULL,
    connection_id BIGINT NOT NULL,
    connection_name VARCHAR(200) NOT NULL,
    schema_name VARCHAR(100) NOT NULL,
    table_name VARCHAR(200) NOT NULL,
    partition_name VARCHAR(200) NOT NULL,
    parent_name VARCHAR(200),
    ordinal INTEGER NOT NULL DEFAULT 0,
    boundary_expr VARCHAR(2000),
    is_sub_partition BOOLEAN NOT NULL DEFAULT FALSE,
    tablespace VARCHAR(100),
    estimated_rows BIGINT
);

-- 表注释
COMMENT ON TABLE lotus.db_connection IS '数据源连接配置表';
COMMENT ON TABLE lotus.compare_history IS '比对历史记录表';
COMMENT ON TABLE lotus.meta_table IS '采集的表元数据';
COMMENT ON TABLE lotus.meta_sequence IS '采集的序列元数据';
COMMENT ON TABLE lotus.meta_column IS '采集的字段元数据';
COMMENT ON TABLE lotus.meta_constraint IS '采集的约束元数据';
COMMENT ON TABLE lotus.meta_index IS '采集的索引元数据';
COMMENT ON TABLE lotus.meta_partition IS '采集的分区元数据';

-- 字段注释
COMMENT ON COLUMN lotus.db_connection.id IS '主键ID';
COMMENT ON COLUMN lotus.db_connection.name IS '数据源名称';
COMMENT ON COLUMN lotus.db_connection.url IS 'JDBC连接URL';
COMMENT ON COLUMN lotus.db_connection.username IS '数据库用户名';
COMMENT ON COLUMN lotus.db_connection.password IS '数据库密码';
COMMENT ON COLUMN lotus.db_connection.schema_name IS 'Schema名称';
COMMENT ON COLUMN lotus.db_connection.remark IS '备注说明';
COMMENT ON COLUMN lotus.db_connection.created_at IS '创建时间';
COMMENT ON COLUMN lotus.db_connection.collected_at IS '最近元数据采集时间';

COMMENT ON COLUMN lotus.compare_history.id IS '主键ID';
COMMENT ON COLUMN lotus.compare_history.source_connection_id IS '源数据源ID';
COMMENT ON COLUMN lotus.compare_history.source_connection_name IS '源数据源名称';
COMMENT ON COLUMN lotus.compare_history.target_connection_id IS '目标数据源ID';
COMMENT ON COLUMN lotus.compare_history.target_connection_name IS '目标数据源名称';
COMMENT ON COLUMN lotus.compare_history.source_schema IS '源Schema';
COMMENT ON COLUMN lotus.compare_history.target_schema IS '目标Schema';
COMMENT ON COLUMN lotus.compare_history.options_json IS '比对选项JSON';
COMMENT ON COLUMN lotus.compare_history.structure_consistent IS '结构一致表数';
COMMENT ON COLUMN lotus.compare_history.structure_different IS '结构差异表数';
COMMENT ON COLUMN lotus.compare_history.data_consistent IS '数据一致表数';
COMMENT ON COLUMN lotus.compare_history.data_different IS '数据差异表数';
COMMENT ON COLUMN lotus.compare_history.data_skipped IS '数据跳过表数';
COMMENT ON COLUMN lotus.compare_history.status IS '比对状态(SUCCESS/FAILED)';
COMMENT ON COLUMN lotus.compare_history.compare_type IS '比对类型(STRUCTURE/DATA/BOTH)';
COMMENT ON COLUMN lotus.compare_history.error_msg IS '错误信息';
COMMENT ON COLUMN lotus.compare_history.report_json IS '比对报告JSON';
COMMENT ON COLUMN lotus.compare_history.ddl_script IS 'DDL同步脚本';
COMMENT ON COLUMN lotus.compare_history.dml_script IS 'DML同步脚本';
COMMENT ON COLUMN lotus.compare_history.duration_ms IS '比对耗时(毫秒)';
COMMENT ON COLUMN lotus.compare_history.created_at IS '创建时间';

COMMENT ON COLUMN lotus.meta_table.id IS '主键ID';
COMMENT ON COLUMN lotus.meta_table.connection_id IS '数据源ID';
COMMENT ON COLUMN lotus.meta_table.connection_name IS '数据源名称快照';
COMMENT ON COLUMN lotus.meta_table.schema_name IS 'Schema名称';
COMMENT ON COLUMN lotus.meta_table.table_name IS '表名';
COMMENT ON COLUMN lotus.meta_table.comment IS '表注释';
COMMENT ON COLUMN lotus.meta_table.partitioned IS '是否分区表(true=是,false=否)';
COMMENT ON COLUMN lotus.meta_table.partition_strategy IS '分区策略(r=范围/l=列表/h=哈希)';
COMMENT ON COLUMN lotus.meta_table.partition_key IS '分区键列名(逗号分隔)';
COMMENT ON COLUMN lotus.meta_table.partition_count IS '分区数量';
COMMENT ON COLUMN lotus.meta_table.collected_at IS '采集时间';

COMMENT ON COLUMN lotus.meta_sequence.id IS '主键ID';
COMMENT ON COLUMN lotus.meta_sequence.connection_id IS '数据源ID';
COMMENT ON COLUMN lotus.meta_sequence.connection_name IS '数据源名称快照';
COMMENT ON COLUMN lotus.meta_sequence.schema_name IS 'Schema名称';
COMMENT ON COLUMN lotus.meta_sequence.sequence_name IS '序列名';
COMMENT ON COLUMN lotus.meta_sequence.data_type IS '数据类型';
COMMENT ON COLUMN lotus.meta_sequence.start_value IS '起始值';
COMMENT ON COLUMN lotus.meta_sequence.increment_by IS '步长';
COMMENT ON COLUMN lotus.meta_sequence.min_value IS '最小值';
COMMENT ON COLUMN lotus.meta_sequence.max_value IS '最大值';
COMMENT ON COLUMN lotus.meta_sequence.cache_size IS '缓存大小';
COMMENT ON COLUMN lotus.meta_sequence.cycle IS '是否循环(true=是,false=否)';
COMMENT ON COLUMN lotus.meta_sequence.collected_at IS '采集时间';

COMMENT ON COLUMN lotus.meta_column.id IS '主键ID';
COMMENT ON COLUMN lotus.meta_column.table_id IS '关联表ID';
COMMENT ON COLUMN lotus.meta_column.connection_id IS '数据源ID快照';
COMMENT ON COLUMN lotus.meta_column.connection_name IS '数据源名称快照';
COMMENT ON COLUMN lotus.meta_column.schema_name IS 'Schema名称快照';
COMMENT ON COLUMN lotus.meta_column.table_name IS '表名快照';
COMMENT ON COLUMN lotus.meta_column.column_name IS '字段名';
COMMENT ON COLUMN lotus.meta_column.data_type IS '数据类型';
COMMENT ON COLUMN lotus.meta_column.nullable IS '是否允许NULL(true=允许,false=不允许)';
COMMENT ON COLUMN lotus.meta_column.default_value IS '默认值';
COMMENT ON COLUMN lotus.meta_column.comment IS '字段注释';
COMMENT ON COLUMN lotus.meta_column.ordinal IS '字段序号';

COMMENT ON COLUMN lotus.meta_constraint.id IS '主键ID';
COMMENT ON COLUMN lotus.meta_constraint.table_id IS '关联表ID';
COMMENT ON COLUMN lotus.meta_constraint.connection_id IS '数据源ID快照';
COMMENT ON COLUMN lotus.meta_constraint.connection_name IS '数据源名称快照';
COMMENT ON COLUMN lotus.meta_constraint.schema_name IS 'Schema名称快照';
COMMENT ON COLUMN lotus.meta_constraint.table_name IS '表名快照';
COMMENT ON COLUMN lotus.meta_constraint.constraint_name IS '约束名';
COMMENT ON COLUMN lotus.meta_constraint.type IS '约束类型(p=主键/u=唯一/f=外键/c=检查)';
COMMENT ON COLUMN lotus.meta_constraint.definition IS '约束定义';
COMMENT ON COLUMN lotus.meta_constraint.columns IS '约束列名(逗号分隔)';
COMMENT ON COLUMN lotus.meta_constraint.ref_table IS '外键引用表';

COMMENT ON COLUMN lotus.meta_index.id IS '主键ID';
COMMENT ON COLUMN lotus.meta_index.table_id IS '关联表ID';
COMMENT ON COLUMN lotus.meta_index.connection_id IS '数据源ID快照';
COMMENT ON COLUMN lotus.meta_index.connection_name IS '数据源名称快照';
COMMENT ON COLUMN lotus.meta_index.schema_name IS 'Schema名称快照';
COMMENT ON COLUMN lotus.meta_index.table_name IS '表名快照';
COMMENT ON COLUMN lotus.meta_index.index_name IS '索引名';
COMMENT ON COLUMN lotus.meta_index.columns IS '索引列名(逗号分隔)';
COMMENT ON COLUMN lotus.meta_index.is_unique IS '是否唯一索引(true=是,false=否)';
COMMENT ON COLUMN lotus.meta_index.is_partial IS '是否部分索引(true=是,false=否)';
COMMENT ON COLUMN lotus.meta_index.where_clause IS '部分索引WHERE条件';
COMMENT ON COLUMN lotus.meta_index.definition IS '索引完整定义';

COMMENT ON COLUMN lotus.meta_partition.id IS '主键ID';
COMMENT ON COLUMN lotus.meta_partition.table_id IS '关联表ID';
COMMENT ON COLUMN lotus.meta_partition.connection_id IS '数据源ID快照';
COMMENT ON COLUMN lotus.meta_partition.connection_name IS '数据源名称快照';
COMMENT ON COLUMN lotus.meta_partition.schema_name IS 'Schema名称快照';
COMMENT ON COLUMN lotus.meta_partition.table_name IS '表名快照';
COMMENT ON COLUMN lotus.meta_partition.partition_name IS '分区名';
COMMENT ON COLUMN lotus.meta_partition.parent_name IS '父分区名';
COMMENT ON COLUMN lotus.meta_partition.ordinal IS '分区内序号';
COMMENT ON COLUMN lotus.meta_partition.boundary_expr IS '分区边界表达式';
COMMENT ON COLUMN lotus.meta_partition.is_sub_partition IS '是否子分区(true=是,false=否)';
COMMENT ON COLUMN lotus.meta_partition.tablespace IS '表空间';
COMMENT ON COLUMN lotus.meta_partition.estimated_rows IS '估算行数';

-- 元数据查询索引（唯一约束防止并发采集产生重复行）
CREATE UNIQUE INDEX IF NOT EXISTS uk_meta_table_conn_table
    ON meta_table (connection_id, table_name);
CREATE UNIQUE INDEX IF NOT EXISTS uk_meta_sequence_conn_seq
    ON meta_sequence (connection_id, sequence_name);
CREATE UNIQUE INDEX IF NOT EXISTS uk_meta_column_table_col
    ON meta_column (table_id, column_name);
CREATE UNIQUE INDEX IF NOT EXISTS uk_meta_constraint_table_name
    ON meta_constraint (table_id, constraint_name);
CREATE UNIQUE INDEX IF NOT EXISTS uk_meta_index_table_name
    ON meta_index (table_id, index_name);
CREATE UNIQUE INDEX IF NOT EXISTS uk_meta_partition_table_name
    ON meta_partition (table_id, partition_name);
CREATE INDEX IF NOT EXISTS idx_meta_column_source
    ON meta_column (connection_id, schema_name, table_name, column_name);
CREATE INDEX IF NOT EXISTS idx_meta_constraint_source
    ON meta_constraint (connection_id, schema_name, table_name, constraint_name);
CREATE INDEX IF NOT EXISTS idx_meta_index_source
    ON meta_index (connection_id, schema_name, table_name, index_name);
CREATE INDEX IF NOT EXISTS idx_meta_partition_source
    ON meta_partition (connection_id, schema_name, table_name, partition_name);

-- 元数据快照：每次采集生成一条快照记录，同一次采集的所有元数据共用相同 snapshot_id 和 collected_at
CREATE TABLE IF NOT EXISTS meta_snapshot (
    id BIGSERIAL PRIMARY KEY,
    connection_id BIGINT NOT NULL,
    collected_at TIMESTAMP NOT NULL,
    table_count INTEGER NOT NULL DEFAULT 0,
    sequence_count INTEGER NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE lotus.meta_snapshot IS '元数据采集快照（同一快照内所有元数据时间点一致）';
COMMENT ON COLUMN lotus.meta_snapshot.id IS '快照ID';
COMMENT ON COLUMN lotus.meta_snapshot.connection_id IS '数据源ID';
COMMENT ON COLUMN lotus.meta_snapshot.collected_at IS '采集时间点（快照内所有元数据一致）';
COMMENT ON COLUMN lotus.meta_snapshot.table_count IS '表数量';
COMMENT ON COLUMN lotus.meta_snapshot.sequence_count IS '序列数量';
COMMENT ON COLUMN lotus.meta_snapshot.duration_ms IS '采集耗时(毫秒)';
COMMENT ON COLUMN lotus.meta_snapshot.created_at IS '快照记录创建时间';

CREATE INDEX IF NOT EXISTS idx_meta_snapshot_conn_time
    ON meta_snapshot (connection_id, collected_at DESC);

-- 当前元数据表补充快照列（已有环境平滑升级）
ALTER TABLE meta_table ADD COLUMN IF NOT EXISTS snapshot_id BIGINT;
ALTER TABLE meta_sequence ADD COLUMN IF NOT EXISTS snapshot_id BIGINT;
ALTER TABLE meta_column ADD COLUMN IF NOT EXISTS snapshot_id BIGINT;
ALTER TABLE meta_constraint ADD COLUMN IF NOT EXISTS snapshot_id BIGINT;
ALTER TABLE meta_index ADD COLUMN IF NOT EXISTS snapshot_id BIGINT;
ALTER TABLE meta_partition ADD COLUMN IF NOT EXISTS snapshot_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_meta_table_snapshot ON meta_table (snapshot_id);
CREATE INDEX IF NOT EXISTS idx_meta_sequence_snapshot ON meta_sequence (snapshot_id);
CREATE INDEX IF NOT EXISTS idx_meta_column_snapshot ON meta_column (snapshot_id);
CREATE INDEX IF NOT EXISTS idx_meta_constraint_snapshot ON meta_constraint (snapshot_id);
CREATE INDEX IF NOT EXISTS idx_meta_index_snapshot ON meta_index (snapshot_id);
CREATE INDEX IF NOT EXISTS idx_meta_partition_snapshot ON meta_partition (snapshot_id);

-- 历史备份表：主键为 (snapshot_id, id)，完整保留采集时刻的元数据
CREATE TABLE IF NOT EXISTS meta_table_history (
    snapshot_id BIGINT NOT NULL,
    id BIGINT NOT NULL,
    connection_id BIGINT NOT NULL,
    connection_name VARCHAR(200) NOT NULL,
    schema_name VARCHAR(100) NOT NULL,
    table_name VARCHAR(200) NOT NULL,
    comment VARCHAR(1000),
    partitioned BOOLEAN NOT NULL DEFAULT FALSE,
    partition_strategy VARCHAR(1),
    partition_key VARCHAR(500),
    partition_count INTEGER,
    collected_at TIMESTAMP NOT NULL,
    PRIMARY KEY (snapshot_id, id)
);
CREATE TABLE IF NOT EXISTS meta_sequence_history (
    snapshot_id BIGINT NOT NULL,
    id BIGINT NOT NULL,
    connection_id BIGINT NOT NULL,
    connection_name VARCHAR(200) NOT NULL,
    schema_name VARCHAR(100) NOT NULL,
    sequence_name VARCHAR(200) NOT NULL,
    data_type VARCHAR(50),
    start_value BIGINT,
    increment_by BIGINT,
    min_value BIGINT,
    max_value BIGINT,
    cache_size BIGINT,
    cycle BOOLEAN NOT NULL DEFAULT FALSE,
    collected_at TIMESTAMP NOT NULL,
    PRIMARY KEY (snapshot_id, id)
);
CREATE TABLE IF NOT EXISTS meta_column_history (
    snapshot_id BIGINT NOT NULL,
    id BIGINT NOT NULL,
    table_id BIGINT NOT NULL,
    connection_id BIGINT NOT NULL,
    connection_name VARCHAR(200) NOT NULL,
    schema_name VARCHAR(100) NOT NULL,
    table_name VARCHAR(200) NOT NULL,
    column_name VARCHAR(200) NOT NULL,
    data_type VARCHAR(200) NOT NULL,
    nullable BOOLEAN NOT NULL DEFAULT FALSE,
    default_value VARCHAR(1000),
    comment VARCHAR(1000),
    ordinal INTEGER NOT NULL,
    PRIMARY KEY (snapshot_id, id)
);
CREATE TABLE IF NOT EXISTS meta_constraint_history (
    snapshot_id BIGINT NOT NULL,
    id BIGINT NOT NULL,
    table_id BIGINT NOT NULL,
    connection_id BIGINT NOT NULL,
    connection_name VARCHAR(200) NOT NULL,
    schema_name VARCHAR(100) NOT NULL,
    table_name VARCHAR(200) NOT NULL,
    constraint_name VARCHAR(200) NOT NULL,
    type VARCHAR(1) NOT NULL,
    definition VARCHAR(2000),
    columns VARCHAR(500),
    ref_table VARCHAR(200),
    PRIMARY KEY (snapshot_id, id)
);
CREATE TABLE IF NOT EXISTS meta_index_history (
    snapshot_id BIGINT NOT NULL,
    id BIGINT NOT NULL,
    table_id BIGINT NOT NULL,
    connection_id BIGINT NOT NULL,
    connection_name VARCHAR(200) NOT NULL,
    schema_name VARCHAR(100) NOT NULL,
    table_name VARCHAR(200) NOT NULL,
    index_name VARCHAR(200) NOT NULL,
    columns VARCHAR(500),
    is_unique BOOLEAN NOT NULL DEFAULT FALSE,
    is_partial BOOLEAN NOT NULL DEFAULT FALSE,
    where_clause VARCHAR(2000),
    definition VARCHAR(2000),
    PRIMARY KEY (snapshot_id, id)
);
CREATE TABLE IF NOT EXISTS meta_partition_history (
    snapshot_id BIGINT NOT NULL,
    id BIGINT NOT NULL,
    table_id BIGINT NOT NULL,
    connection_id BIGINT NOT NULL,
    connection_name VARCHAR(200) NOT NULL,
    schema_name VARCHAR(100) NOT NULL,
    table_name VARCHAR(200) NOT NULL,
    partition_name VARCHAR(200) NOT NULL,
    parent_name VARCHAR(200),
    ordinal INTEGER NOT NULL DEFAULT 0,
    boundary_expr VARCHAR(2000),
    is_sub_partition BOOLEAN NOT NULL DEFAULT FALSE,
    tablespace VARCHAR(100),
    estimated_rows BIGINT,
    PRIMARY KEY (snapshot_id, id)
);

COMMENT ON TABLE lotus.meta_table_history IS '表元数据历史快照';
COMMENT ON TABLE lotus.meta_sequence_history IS '序列元数据历史快照';
COMMENT ON TABLE lotus.meta_column_history IS '字段元数据历史快照';
COMMENT ON TABLE lotus.meta_constraint_history IS '约束元数据历史快照';
COMMENT ON TABLE lotus.meta_index_history IS '索引元数据历史快照';
COMMENT ON TABLE lotus.meta_partition_history IS '分区元数据历史快照';
COMMENT ON COLUMN lotus.meta_table_history.snapshot_id IS '快照ID';
COMMENT ON COLUMN lotus.meta_sequence_history.snapshot_id IS '快照ID';
COMMENT ON COLUMN lotus.meta_column_history.snapshot_id IS '快照ID';
COMMENT ON COLUMN lotus.meta_constraint_history.snapshot_id IS '快照ID';
COMMENT ON COLUMN lotus.meta_index_history.snapshot_id IS '快照ID';
COMMENT ON COLUMN lotus.meta_partition_history.snapshot_id IS '快照ID';

CREATE INDEX IF NOT EXISTS idx_meta_table_history_conn_table
    ON meta_table_history (connection_id, table_name, snapshot_id DESC);
CREATE INDEX IF NOT EXISTS idx_meta_column_history_table_col
    ON meta_column_history (snapshot_id, table_id, column_name);
CREATE INDEX IF NOT EXISTS idx_meta_constraint_history_table
    ON meta_constraint_history (snapshot_id, table_id);
CREATE INDEX IF NOT EXISTS idx_meta_index_history_table
    ON meta_index_history (snapshot_id, table_id);
CREATE INDEX IF NOT EXISTS idx_meta_partition_history_table
    ON meta_partition_history (snapshot_id, table_id);
CREATE INDEX IF NOT EXISTS idx_meta_sequence_history_conn_seq
    ON meta_sequence_history (connection_id, sequence_name, snapshot_id DESC);

-- 定时比对任务配置：每天在 daily_time 指定时间自动 采集元数据→结构比对→邮件通知
CREATE TABLE IF NOT EXISTS compare_schedule (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    source_connection_id BIGINT NOT NULL,
    target_connection_id BIGINT NOT NULL,
    daily_time VARCHAR(5) NOT NULL DEFAULT '08:00',
    compare_type VARCHAR(10) NOT NULL DEFAULT 'STRUCTURE',
    include_tables VARCHAR(2000),
    exclude_tables VARCHAR(2000),
    notify_emails VARCHAR(2000),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_run_at TIMESTAMP,
    last_status VARCHAR(20),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE lotus.compare_schedule IS '定时比对任务配置';
COMMENT ON COLUMN lotus.compare_schedule.daily_time IS '每日执行时间(HH:mm)';
COMMENT ON COLUMN lotus.compare_schedule.compare_type IS '比对类型(STRUCTURE/BOTH)';
COMMENT ON COLUMN lotus.compare_schedule.notify_emails IS '通知邮箱(逗号分隔)';
COMMENT ON COLUMN lotus.compare_schedule.enabled IS '是否启用';

CREATE TABLE IF NOT EXISTS compare_schedule_run (
    id BIGSERIAL PRIMARY KEY,
    schedule_id BIGINT NOT NULL,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    status VARCHAR(20) NOT NULL,
    collect_source_ms BIGINT,
    collect_target_ms BIGINT,
    compare_ms BIGINT,
    notify_ms BIGINT,
    total_ms BIGINT,
    structure_consistent BIGINT,
    structure_different BIGINT,
    data_consistent BIGINT,
    data_different BIGINT,
    data_skipped BIGINT,
    error_msg VARCHAR(4000)
);
COMMENT ON TABLE lotus.compare_schedule_run IS '定时比对执行记录（含各阶段耗时）';
COMMENT ON COLUMN lotus.compare_schedule_run.collect_source_ms IS '源端元数据采集耗时(毫秒)';
COMMENT ON COLUMN lotus.compare_schedule_run.collect_target_ms IS '目标端元数据采集耗时(毫秒)';
COMMENT ON COLUMN lotus.compare_schedule_run.compare_ms IS '比对耗时(毫秒)';
COMMENT ON COLUMN lotus.compare_schedule_run.notify_ms IS '邮件通知耗时(毫秒)';
COMMENT ON COLUMN lotus.compare_schedule_run.total_ms IS '总耗时(毫秒)';

CREATE INDEX IF NOT EXISTS idx_compare_schedule_enabled ON compare_schedule (enabled);
CREATE INDEX IF NOT EXISTS idx_compare_schedule_run_sched ON compare_schedule_run (schedule_id, started_at DESC);

-- 比对历史和元数据明细查询索引
CREATE INDEX IF NOT EXISTS idx_compare_history_created_at
    ON compare_history (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_compare_history_source_connection
    ON compare_history (source_connection_id);
CREATE INDEX IF NOT EXISTS idx_compare_history_target_connection
    ON compare_history (target_connection_id);
CREATE INDEX IF NOT EXISTS idx_meta_column_table
    ON meta_column (table_id, ordinal);
CREATE INDEX IF NOT EXISTS idx_meta_constraint_table
    ON meta_constraint (table_id, constraint_name);
CREATE INDEX IF NOT EXISTS idx_meta_index_table
    ON meta_index (table_id, index_name);
CREATE INDEX IF NOT EXISTS idx_meta_partition_table
    ON meta_partition (table_id, ordinal);
