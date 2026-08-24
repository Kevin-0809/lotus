# GaussDB 比对平台重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在保持现有 API 和比对能力的前提下，完成侧栏极简页面重构、后端日志与并发治理，以及元数据表查询友好化冗余字段改造。

**Architecture:** Web 层注入单例 `CompareService`，服务层通过受控的按表并行执行器完成数据比对；并发数与 JDBC 连接池统一限流。元数据子表保留外键，同时保存数据源和表上下文快照，并通过 `saveAll` 批量落库。静态页面拆分为 HTML、CSS、JS 三个文件，采用无卡片侧栏工作台。

**Tech Stack:** Java 17, Spring Boot 3.2, Spring Data JPA, HikariCP, PostgreSQL/openGauss JDBC, 原生 HTML/CSS/JavaScript, JUnit 5.

---

### Task 1: 并发执行器与日志边界

**Files:**
- Modify: `src/main/java/com/lotus/gausscmp/concurrency/TableTaskExecutor.java`
- Modify: `src/main/java/com/lotus/gausscmp/web/CompareService.java`
- Modify: `src/main/java/com/lotus/gausscmp/connection/DualDataSource.java`
- Modify: `src/main/java/com/lotus/gausscmp/web/GlobalExceptionHandler.java`
- Modify: `src/main/java/com/lotus/gausscmp/cli/CompareCommand.java`
- Test: `src/test/java/com/lotus/gausscmp/concurrency/TableTaskExecutorTest.java`

- [ ] 增加受控并发上限、任务耗时和失败日志；失败结果携带表名与异常，不再静默吞掉。
- [ ] 将 `CompareService` 改为 Spring `@Service`，在数据比对开始、每张表失败/完成、整体完成处记录不含密码和行数据的结构化日志。
- [ ] 使用 `LoggerFactory` 替代 `printStackTrace`，全局异常只返回安全错误信息。
- [ ] 将并发数统一限制为 `1..min(availableProcessors*2, 16)`，连接池使用 `parallelism + 1` 且不超过该上限。
- [ ] 补充并发边界和失败任务测试，运行 `mvn -q -Dtest=TableTaskExecutorTest test`。

### Task 2: 元数据冗余字段和批量保存

**Files:**
- Modify: `src/main/java/com/lotus/gausscmp/web/entity/MetaTable.java`
- Modify: `src/main/java/com/lotus/gausscmp/web/entity/MetaSequence.java`
- Modify: `src/main/java/com/lotus/gausscmp/web/entity/MetaColumn.java`
- Modify: `src/main/java/com/lotus/gausscmp/web/entity/MetaConstraint.java`
- Modify: `src/main/java/com/lotus/gausscmp/web/entity/MetaIndex.java`
- Modify: `src/main/java/com/lotus/gausscmp/web/entity/MetaPartition.java`
- Modify: `src/main/java/com/lotus/gausscmp/web/service/MetadataCollectService.java`
- Modify: `src/main/java/com/lotus/gausscmp/web/repository/Meta*.java`
- Test: `src/test/java/com/lotus/gausscmp/web/service/MetadataCollectServiceTest.java`

- [ ] 在表和序列实体增加 `connectionName`；在字段、约束、索引、分区实体增加 `connectionId`、`connectionName`、`schemaName`、`tableName`。
- [ ] 在实体 `@Table(indexes=...)` 中加入 `(connection_id, schema_name, table_name)` 组合索引及对象名索引。
- [ ] 引入不可变采集上下文，保存方法统一从上下文复制冗余字段和采集时间。
- [ ] `saveColumns/saveConstraints/saveIndexes/savePartitions` 构造实体列表后调用 `saveAll`，不改变删除旧数据后重建快照的语义。
- [ ] 为批量保存和冗余字段赋值增加单元测试；运行相关单测和 `mvn -q -DskipTests compile`。

### Task 3: Web 控制器与配置整理

**Files:**
- Modify: `src/main/java/com/lotus/gausscmp/web/CompareController.java`
- Modify: `src/main/java/com/lotus/gausscmp/config/OptionsConfig.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/lotus/gausscmp/config/ConfigLoaderTest.java`

- [ ] 注入 `CompareService`，移除请求内 `new CompareService()`。
- [ ] 提取并发参数规范化和 `CompareConfig` 构造逻辑，Web 默认并发使用受控 CPU 默认值。
- [ ] 为数据比对请求记录 compare type、数据源 ID、表过滤条件数量和 parallelism，不记录凭据。
- [ ] 增加日志格式和包级别配置，避免 Spring/Hibernate 噪声。
- [ ] 运行配置、控制器和服务单元测试。

### Task 4: 静态页面拆分与侧栏极简重构

**Files:**
- Modify: `src/main/resources/static/index.html`
- Create: `src/main/resources/static/app.css`
- Create: `src/main/resources/static/app.js`

- [ ] 保留所有现有元素 ID、API 地址和渲染函数契约，先将内联 CSS/JS 原样拆出。
- [ ] 改为固定侧栏 + 主工作区：导航、数据源、历史不使用卡片容器；使用细线、留白、表格分区表达层级。
- [ ] 统一黑白灰与蓝色行动色，状态使用小型文字/竖线提示，移除圆角卡片、胶囊分类按钮和嵌套卡片。
- [ ] 调整比对表单、结果标签页、元数据浏览、历史详情的响应式布局，移动端侧栏改为顶部横向导航。
- [ ] 使用图标按钮时增加 `title` 和 `aria-label`，保留现有中文文案和操作反馈。
- [ ] 启动 Spring Boot 或静态服务器，验证比对、数据源切换、元数据浏览、历史详情和脚本复制。

### Task 5: 回归验证

**Files:**
- Test: existing `src/test/java/**`

- [ ] 运行 `mvn -q test`，确认非容器单测全部通过。
- [ ] Docker 可用时运行 `mvn -q verify`，验证 PostgreSQL 元数据和多表并发数据比对。
- [ ] 检查 `git diff --check`、日志中无密码/JDBC 凭据、源码中无 `printStackTrace` 和空异常吞掉。
- [ ] 使用浏览器检查桌面和移动视口，确认页面无重叠、表格可横向滚动、执行按钮禁用状态正确。
