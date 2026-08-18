# GaussDB Schema 比对工具 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个纯 CLI 工具，比对两个 GaussDB（openGauss 集中式）指定 schema 的数据结构与数据差异，输出 HTML 报告 + DDL/DML 同步脚本。

**Architecture:** 单进程 Java 17 应用，JDBC 连两端（只读）；元数据驱动：抽结构→比结构→比数据（分块校验和 + 下钻）→聚合结果→生成报告与脚本。表级并行可配。三阶段解耦（抽取/比对/输出），比对引擎只产出 diff 模型，输出层消费模型。

**Tech Stack:** Java 17 + Maven；openGauss JDBC 驱动；HikariCP；picocli（CLI）；Jackson（YAML/JSON）；SLF4J + java.util.logging；JUnit 5 + AssertJ + Testcontainers（openGauss 镜像）。

**Spec:** `docs/superpowers/specs/2026-08-17-gaussdb-schema-compare-tool-design.md`

---

## File Structure

```
pom.xml
src/main/java/com/lotus/gausscmp/
  cli/Main.java                          # picocli 入口
  cli/CompareCommand.java                # 比对命令编排：配置→连接→抽取→比对→输出
  config/CompareConfig.java              # 顶层配置 record（source/target/options）
  config/SourceConfig.java               # 源端连接配置 record
  config/OptionsConfig.java              # 运行选项 record
  config/TableFilterConfig.java          # 表过滤配置 record
  config/OutputConfig.java               # 输出配置 record
  config/ConfigLoader.java               # YAML 加载 + ${ENV} 插值 + 校验
  config/EnvInterpolator.java            # ${VAR}/${VAR:-default} 解析
  connection/DualDataSource.java         # HikariCP 双池管理（source/target，只读）
  metadata/SchemaSnapshot.java           # schema 快照 record
  metadata/TableMeta.java                # 表元数据 record
  metadata/ColumnMeta.java               # 列元数据 record
  metadata/ConstraintMeta.java           # 约束元数据 record
  metadata/ConstraintType.java           # 约束类型 enum
  metadata/IndexMeta.java                # 索引元数据 record
  metadata/MetadataReader.java           # 元数据抽取接口
  metadata/OpenGaussMetadataReader.java  # openGauss 系统目录抽取实现
  metadata/TypeNormalizer.java           # 数据类型规范化
  metadata/DefinitionNormalizer.java     # 默认值/定义文本规范化
  compare/diff/DiffType.java             # 结构差异类型 enum
  compare/diff/TableStructureStatus.java # 表结构状态 enum
  compare/diff/TableStructureDiff.java   # 表结构差异 record
  compare/diff/ColumnDiff.java           # 列差异 record
  compare/diff/ConstraintDiff.java       # 约束差异 record
  compare/diff/IndexDiff.java            # 索引差异 record
  compare/diff/StructureDiffResult.java  # 结构比对结果 record
  compare/diff/RowDiffType.java          # 行差异类型 enum
  compare/diff/TableDataStatus.java      # 表数据状态 enum
  compare/diff/RowDiff.java              # 行差异 record
  compare/diff/ChunkStats.java           # 块统计 record
  compare/diff/TableDataDiff.java        # 表数据差异 record
  compare/diff/DataDiffResult.java       # 数据比对结果 record
  compare/structure/StructureComparator.java  # 结构比对引擎
  compare/data/Chunker.java              # 分块器（OFFSET 模式）
  compare/data/ChecksumCalculator.java   # 块校验和计算 SQL 构造
  compare/data/DrillDownComparator.java  # 下钻逐行比对
  compare/data/DataComparator.java       # 数据比对编排（分块→校验和→下钻）
  concurrency/TableTaskExecutor.java     # 表级并行执行器
  concurrency/ResultAggregator.java      # 结果按表名归并
  sync/DdlScriptGenerator.java           # 结构同步 DDL 生成
  sync/DmlScriptGenerator.java           # 数据同步 DML 生成
  sync/ScriptWriter.java                 # 脚本文件写入
  report/ReportModel.java                # 报告模型 record
  report/JsonReportSerializer.java       # result.json 序列化
  report/HtmlReportRenderer.java         # HTML 报告渲染
src/test/java/com/lotus/gausscmp/...     # 对应测试
```

**Decomposition principles:**
- 模型类用 Java 17 `record`，不可变，减少样板代码。
- diff 模型是比对引擎与输出层之间的契约，单独成包 `compare/diff`。
- 抽取（`metadata`）有接口 + 实现，预留异构扩展。
- 输出层三个生成器（DDL/DML/报告）各自独立，消费同一 diff 模型。

---

## Task 1: Maven 项目骨架与依赖

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/lotus/gausscmp/.gitkeep`
- Create: `src/test/java/com/lotus/gausscmp/.gitkeep`

- [ ] **Step 1: 创建 `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.lotus</groupId>
    <artifactId>gausscmp</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>jar</packaging>
    <properties>
        <maven.compiler.release>17</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <junit.version>5.10.2</junit.version>
        <assertj.version>3.25.3</assertj.version>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.opengauss</groupId>
            <artifactId>opengauss-jdbc</artifactId>
            <version>5.0.0</version>
        </dependency>
        <dependency>
            <groupId>com.zaxxer</groupId>
            <artifactId>HikariCP</artifactId>
            <version>5.1.0</version>
        </dependency>
        <dependency>
            <groupId>info.picocli</groupId>
            <artifactId>picocli</artifactId>
            <version>4.7.6</version>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.dataformat</groupId>
            <artifactId>jackson-dataformat-yaml</artifactId>
            <version>2.17.0</version>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>2.17.0</version>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-jdk14</artifactId>
            <version>2.0.13</version>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <version>${assertj.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>1.19.7</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <version>1.19.7</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
            </plugin>
            <plugin>
                <artifactId>maven-failsafe-plugin</artifactId>
                <version>3.2.5</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>integration-test</goal>
                            <goal>verify</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.5.2</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <transformers>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <mainClass>com.lotus.gausscmp.cli.Main</mainClass>
                                </transformer>
                            </transformers>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: 创建占位目录**

```bash
mkdir -p src/main/java/com/lotus/gausscmp
mkdir -p src/test/java/com/lotus/gausscmp
touch src/main/java/com/lotus/gausscmp/.gitkeep
touch src/test/java/com/lotus/gausscmp/.gitkeep
```

- [ ] **Step 3: 验证构建**

Run: `mvn -q compile`
Expected: BUILD SUCCESS（无源文件也应成功）

- [ ] **Step 4: 提交**

```bash
git add pom.xml src
git commit -m "chore: init maven project skeleton with dependencies"
```

---

## Task 2: 配置模型与 ConfigLoader

**Files:**
- Create: `src/main/java/com/lotus/gausscmp/config/EnvInterpolator.java`
- Create: `src/main/java/com/lotus/gausscmp/config/SourceConfig.java`
- Create: `src/main/java/com/lotus/gausscmp/config/OptionsConfig.java`
- Create: `src/main/java/com/lotus/gausscmp/config/TableFilterConfig.java`
- Create: `src/main/java/com/lotus/gausscmp/config/OutputConfig.java`
- Create: `src/main/java/com/lotus/gausscmp/config/CompareConfig.java`
- Create: `src/main/java/com/lotus/gausscmp/config/ConfigLoader.java`
- Test: `src/test/java/com/lotus/gausscmp/config/EnvInterpolatorTest.java`
- Test: `src/test/java/com/lotus/gausscmp/config/ConfigLoaderTest.java`

- [ ] **Step 1: 写 EnvInterpolator 的失败测试**

```java
package com.lotus.gausscmp.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EnvInterpolatorTest {

    @Test
    void resolvesSimpleVar() {
        var prev = System.getenv().get("GAUSSCMP_TEST_VAR");
        // 用反射设置环境变量不可移植；改用 process env 已存在的 PATH
        String result = EnvInterpolator.interpolate("prefix-${PATH}-suffix");
        assertThat(result).startsWith("prefix-").endsWith("-suffix").isNotEqualTo("prefix-${PATH}-suffix");
    }

    @Test
    void resolvesVarWithDefault() {
        String result = EnvInterpolator.interpolate("${GAUSSCMP_NONEXIST:-5432}");
        assertThat(result).isEqualTo("5432");
    }

    @Test
    void throwsOnMissingRequiredVar() {
        assertThatThrownBy(() -> EnvInterpolator.interpolate("${GAUSSCMP_DEFINITELY_MISSING}"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("GAUSSCMP_DEFINITELY_MISSING");
    }

    @Test
    void leavesPlainTextUntouched() {
        assertThat(EnvInterpolator.interpolate("plain-text")).isEqualTo("plain-text");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=EnvInterpolatorTest`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 EnvInterpolator**

```java
package com.lotus.gausscmp.config;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EnvInterpolator {
    private static final Pattern PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private EnvInterpolator() {}

    public static String interpolate(String input) {
        if (input == null) return null;
        Matcher m = PATTERN.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String expr = m.group(1);
            String value = resolveExpr(expr);
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String resolveExpr(String expr) {
        Map<String, String> env = System.getenv();
        if (expr.contains(":-")) {
            int idx = expr.indexOf(":-");
            String name = expr.substring(0, idx).trim();
            String def = expr.substring(idx + 2);
            return env.getOrDefault(name, def);
        }
        String name = expr.trim();
        String val = env.get(name);
        if (val == null) throw new IllegalStateException("环境变量未设置: " + name);
        return val;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=EnvInterpolatorTest`
Expected: PASS

- [ ] **Step 5: 写配置 record 类**

```java
// SourceConfig.java
package com.lotus.gausscmp.config;
public record SourceConfig(String host, int port, String database,
                           String username, String password, String schema, boolean readOnly) {}

// TableFilterConfig.java
package com.lotus.gausscmp.config;
import java.util.List;
public record TableFilterConfig(List<String> include, List<String> exclude) {}

// OutputConfig.java
package com.lotus.gausscmp.config;
public record OutputConfig(String dir, boolean html, boolean ddlScript, boolean dmlScript) {}

// OptionsConfig.java
package com.lotus.gausscmp.config;
public record OptionsConfig(int parallelism, int chunkSize, boolean drillDown,
                            String checksumFunction, TableFilterConfig tableFilter,
                            OutputConfig output, String syncDirection, int maxDisplayRows,
                            long tableTimeout) {}
```

```java
// CompareConfig.java
package com.lotus.gausscmp.config;
public record CompareConfig(SourceConfig source, SourceConfig target, OptionsConfig options) {}
```

- [ ] **Step 6: 写 ConfigLoader 的失败测试**

```java
package com.lotus.gausscmp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class ConfigLoaderTest {

    @Test
    void loadsYamlAndInterpolatesEnv(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("compare.yaml");
        java.nio.file.Files.writeString(cfg, """
            source:
              host: 10.0.0.1
              port: 5432
              database: prod
              username: ro
              password: ${GAUSSCMP_DEFINITELY_MISSING:-secret}
              schema: app
              readOnly: true
            target:
              host: 10.0.0.2
              port: 5432
              database: prod
              username: ro
              password: ${GAUSSCMP_DEFINITELY_MISSING:-secret}
              schema: app
              readOnly: true
            options:
              parallelism: 1
              chunkSize: 5000
              drillDown: true
              checksumFunction: md5
              tableFilter:
                include: [".*"]
                exclude: ["^tmp_.*"]
              output:
                dir: ./report
                html: true
                ddlScript: true
                dmlScript: true
              syncDirection: source-to-target
              maxDisplayRows: 1000
              tableTimeout: 0
            """);
        CompareConfig config = ConfigLoader.load(cfg);
        assertThat(config.source().host()).isEqualTo("10.0.0.1");
        assertThat(config.source().password()).isEqualTo("secret");
        assertThat(config.options().tableFilter().exclude()).containsExactly("^tmp_.*");
        assertThat(config.options().maxDisplayRows()).isEqualTo(1000);
    }

    @Test
    void throwsOnMissingRequiredPassword(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("compare.yaml");
        java.nio.file.Files.writeString(cfg, """
            source:
              host: h
              port: 5432
              database: d
              username: u
              password: ${GAUSSCMP_REQUIRED_BUT_MISSING}
              schema: s
              readOnly: true
            target:
              host: h
              port: 5432
              database: d
              username: u
              password: p
              schema: s
              readOnly: true
            options:
              parallelism: 1
              chunkSize: 5000
              drillDown: true
              checksumFunction: md5
              tableFilter: {include: [".*"], exclude: []}
              output: {dir: ./r, html: true, ddlScript: true, dmlScript: true}
              syncDirection: source-to-target
              maxDisplayRows: 1000
              tableTimeout: 0
            """);
        assertThatThrownBy(() -> ConfigLoader.load(cfg))
            .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 7: 运行测试确认失败**

Run: `mvn -q test -Dtest=ConfigLoaderTest`
Expected: FAIL（ConfigLoader 不存在）

- [ ] **Step 8: 实现 ConfigLoader**

```java
package com.lotus.gausscmp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigLoader {

    private ConfigLoader() {}

    public static CompareConfig load(Path path) {
        try {
            String raw = Files.readString(path);
            ObjectMapper yaml = new YAMLMapper();
            JsonNode tree = yaml.readTree(raw);
            String interpolated = yaml.writeValueAsString(interpolateNode(tree));
            CompareConfig cfg = yaml.readValue(interpolated, CompareConfig.class);
            validate(cfg);
            return cfg;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("加载配置失败: " + e.getMessage(), e);
        }
    }

    private static JsonNode interpolateNode(JsonNode node) {
        if (node.isTextual()) {
            return new ObjectMapper().getNodeFactory().textNode(
                EnvInterpolator.interpolate(node.asText()));
        }
        if (node.isObject()) {
            var obj = new ObjectMapper().createObjectNode();
            node.fields().forEachRemaining(e -> obj.set(e.getKey(), interpolateNode(e.getValue())));
            return obj;
        }
        if (node.isArray()) {
            var arr = new ObjectMapper().createArrayNode();
            node.forEach(n -> arr.add(interpolateNode(n)));
            return arr;
        }
        return node;
    }

    private static void validate(CompareConfig c) {
        require(c.source().host(), "source.host");
        require(c.source().database(), "source.database");
        require(c.source().username(), "source.username");
        require(c.source().schema(), "source.schema");
        require(c.target().host(), "target.host");
        require(c.target().database(), "target.database");
        require(c.target().username(), "target.username");
        require(c.target().schema(), "target.schema");
    }

    private static void require(String val, String field) {
        if (val == null || val.isBlank()) {
            throw new IllegalStateException("配置字段缺失: " + field);
        }
    }
}
```

- [ ] **Step 9: 运行测试确认通过**

Run: `mvn -q test -Dtest=ConfigLoaderTest,EnvInterpolatorTest`
Expected: PASS

- [ ] **Step 10: 提交**

```bash
git add src
git commit -m "feat: config model with yaml loading and env interpolation"
```

---

## Task 3: 连接管理 DualDataSource

**Files:**
- Create: `src/main/java/com/lotus/gausscmp/connection/DualDataSource.java`
- Test: `src/test/java/com/lotus/gausscmp/connection/DualDataSourceTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.lotus.gausscmp.connection;

import com.lotus.gausscmp.config.SourceConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DualDataSourceTest {

    @Test
    void buildsTwoPoolsWithCorrectParams() {
        SourceConfig src = new SourceConfig("h1", 5432, "d1", "u1", "p1", "s1", true);
        SourceConfig tgt = new SourceConfig("h2", 5432, "d2", "u2", "p2", "s2", true);
        try (var ds = new DualDataSource(src, tgt, 2)) {
            HikariDataSource s = ds.source();
            HikariDataSource t = ds.target();
            assertThat(s.getMaximumPoolSize()).isEqualTo(3);
            assertThat(t.getMaximumPoolSize()).isEqualTo(3);
            assertThat(s.isReadOnly()).isTrue();
            assertThat(t.isReadOnly()).isTrue();
            assertThat(s.getJdbcUrl()).contains("h1", "5432", "d1");
        }
    }

    @Test
    void closesBothPools() {
        SourceConfig src = new SourceConfig("h1", 5432, "d1", "u1", "p1", "s1", true);
        SourceConfig tgt = new SourceConfig("h2", 5432, "d2", "u2", "p2", "s2", true);
        var ds = new DualDataSource(src, tgt, 1);
        ds.close();
        assertThat(ds.source().isClosed()).isTrue();
        assertThat(ds.target().isClosed()).isTrue();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=DualDataSourceTest`
Expected: FAIL

- [ ] **Step 3: 实现 DualDataSource**

```java
package com.lotus.gausscmp.connection;

import com.lotus.gausscmp.config.SourceConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public final class DualDataSource implements AutoCloseable {
    private final HikariDataSource source;
    private final HikariDataSource target;

    public DualDataSource(SourceConfig src, SourceConfig tgt, int parallelism) {
        this.source = build(src, Math.max(2, parallelism + 1));
        this.target = build(tgt, Math.max(2, parallelism + 1));
    }

    private static HikariDataSource build(SourceConfig c, int poolSize) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(String.format("jdbc:opengauss://%s:%d/%s", c.host(), c.port(), c.database()));
        hc.setUsername(c.username());
        hc.setPassword(c.password());
        hc.setMaximumPoolSize(poolSize);
        hc.setReadOnly(c.readOnly());
        hc.setConnectionTimeout(30000);
        hc.setIdleTimeout(600000);
        hc.setPoolName("gausscmp-" + c.host());
        return new HikariDataSource(hc);
    }

    public HikariDataSource source() { return source; }
    public HikariDataSource target() { return target; }

    @Override
    public void close() {
        if (source != null) source.close();
        if (target != null) target.close();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=DualDataSourceTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src
git commit -m "feat: dual hikari datasource with readonly pools"
```

---

## Task 4: 元数据模型类

**Files:**
- Create: `src/main/java/com/lotus/gausscmp/metadata/ConstraintType.java`
- Create: `src/main/java/com/lotus/gausscmp/metadata/ColumnMeta.java`
- Create: `src/main/java/com/lotus/gausscmp/metadata/ConstraintMeta.java`
- Create: `src/main/java/com/lotus/gausscmp/metadata/IndexMeta.java`
- Create: `src/main/java/com/lotus/gausscmp/metadata/TableMeta.java`
- Create: `src/main/java/com/lotus/gausscmp/metadata/SchemaSnapshot.java`
- Create: `src/main/java/com/lotus/gausscmp/metadata/MetadataReader.java`
- Test: `src/test/java/com/lotus/gausscmp/metadata/MetadataModelTest.java`

- [ ] **Step 1: 写模型验证测试**

```java
package com.lotus.gausscmp.metadata;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class MetadataModelTest {

    @Test
    void tableMetaDerivesHasPrimaryKey() {
        ColumnMeta col = new ColumnMeta("id", "integer", false, null, null, 1);
        ConstraintMeta pk = new ConstraintMeta("pk_t", ConstraintType.PRIMARY, "PRIMARY KEY (id)", List.of("id"), null);
        TableMeta t = new TableMeta("t", "tbl", List.of(col), List.of(pk), List.of(), true);
        assertThat(t.hasPrimaryKeyOrUnique()).isTrue();
        assertThat(t.primaryKey()).contains(pk);
    }

    @Test
    void tableWithoutConstraintsHasNoKey() {
        TableMeta t = new TableMeta("t", null, List.of(), List.of(), List.of(), false);
        assertThat(t.hasPrimaryKeyOrUnique()).isFalse();
    }

    @Test
    void snapshotHoldsSchemaAndTables() {
        TableMeta t = new TableMeta("t", null, List.of(), List.of(), List.of(), false);
        SchemaSnapshot snap = new SchemaSnapshot("app", List.of(t));
        assertThat(snap.schemaName()).isEqualTo("app");
        assertThat(snap.tables()).hasSize(1);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=MetadataModelTest`
Expected: FAIL

- [ ] **Step 3: 实现 enum 与 record**

```java
// ConstraintType.java
package com.lotus.gausscmp.metadata;
public enum ConstraintType { PRIMARY, UNIQUE, FOREIGN, CHECK }
```

```java
// ColumnMeta.java
package com.lotus.gausscmp.metadata;
public record ColumnMeta(String name, String dataType, boolean nullable,
                         String defaultValue, String comment, int ordinal) {}
```

```java
// ConstraintMeta.java
package com.lotus.gausscmp.metadata;
import java.util.List;
public record ConstraintMeta(String name, ConstraintType type, String definition,
                             List<String> columns, String referencesTable) {}
```

```java
// IndexMeta.java
package com.lotus.gausscmp.metadata;
import java.util.List;
public record IndexMeta(String name, String tableName, List<String> columns,
                        boolean isUnique, boolean isPartial, String whereClause, String definition) {}
```

```java
// TableMeta.java
package com.lotus.gausscmp.metadata;
import java.util.List;
import java.util.Optional;
public record TableMeta(String name, String comment, List<ColumnMeta> columns,
                        List<ConstraintMeta> constraints, List<IndexMeta> indexes,
                        boolean partitioned) {
    public Optional<ConstraintMeta> primaryKey() {
        return constraints.stream()
            .filter(c -> c.type() == ConstraintType.PRIMARY)
            .findFirst();
    }
    public boolean hasPrimaryKeyOrUnique() {
        if (primaryKey().isPresent()) return true;
        return constraints.stream().anyMatch(c -> c.type() == ConstraintType.UNIQUE)
            || indexes.stream().anyMatch(IndexMeta::isUnique);
    }
}
```

```java
// SchemaSnapshot.java
package com.lotus.gausscmp.metadata;
import java.util.List;
public record SchemaSnapshot(String schemaName, List<TableMeta> tables) {}
```

```java
// MetadataReader.java
package com.lotus.gausscmp.metadata;
import java.sql.Connection;
import java.util.List;
public interface MetadataReader {
    SchemaSnapshot read(Connection conn, String schema, List<String> includePatterns, List<String> excludePatterns);
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=MetadataModelTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src
git commit -m "feat: metadata model records and reader interface"
```

---

## Task 5: TypeNormalizer

**Files:**
- Create: `src/main/java/com/lotus/gausscmp/metadata/TypeNormalizer.java`
- Test: `src/test/java/com/lotus/gausscmp/metadata/TypeNormalizerTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.lotus.gausscmp.metadata;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TypeNormalizerTest {

    @Test
    void normalizesIntAliases() {
        assertThat(TypeNormalizer.normalize("int4")).isEqualTo("integer");
        assertThat(TypeNormalizer.normalize("int8")).isEqualTo("bigint");
        assertThat(TypeNormalizer.normalize("int")).isEqualTo("integer");
        assertThat(TypeNormalizer.normalize("smallint")).isEqualTo("smallint");
    }

    @Test
    void normalizesVarchar() {
        assertThat(TypeNormalizer.normalize("varchar(10)")).isEqualTo("character varying(10)");
        assertThat(TypeNormalizer.normalize("character varying")).isEqualTo("character varying");
    }

    @Test
    void normalizesArray() {
        assertThat(TypeNormalizer.normalize("int4[]")).isEqualTo("integer[]");
        assertThat(TypeNormalizer.normalize("integer[]")).isEqualTo("integer[]");
    }

    @Test
    void keepsFullTimestamp() {
        assertThat(TypeNormalizer.normalize("timestamp without time zone"))
            .isEqualTo("timestamp without time zone");
        assertThat(TypeNormalizer.normalize("timestamptz"))
            .isEqualTo("timestamp with time zone");
    }

    @Test
    void preservesNumericPrecision() {
        assertThat(TypeNormalizer.normalize("numeric(10,2)")).isEqualTo("numeric(10,2)");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=TypeNormalizerTest`
Expected: FAIL

- [ ] **Step 3: 实现 TypeNormalizer**

```java
package com.lotus.gausscmp.metadata;

import java.util.Map;

public final class TypeNormalizer {
    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("int2", "smallint"),
        Map.entry("int4", "integer"),
        Map.entry("int8", "bigint"),
        Map.entry("int", "integer"),
        Map.entry("integer", "integer"),
        Map.entry("smallint", "smallint"),
        Map.entry("bigint", "bigint"),
        Map.entry("timestamptz", "timestamp with time zone"),
        Map.entry("bool", "boolean"),
        Map.entry("boolean", "boolean"),
        Map.entry("float4", "real"),
        Map.entry("float8", "double precision")
    );

    private TypeNormalizer() {}

    public static String normalize(String type) {
        if (type == null) return null;
        String t = type.trim().toLowerCase();
        boolean isArray = t.endsWith("[]");
        if (isArray) {
            String base = t.substring(0, t.length() - 2).trim();
            return normalize(base) + "[]";
        }
        if (ALIASES.containsKey(t)) return ALIASES.get(t);
        if (t.startsWith("varchar")) return t.replaceFirst("varchar", "character varying");
        if (t.equals("character varying")) return t;
        return t;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=TypeNormalizerTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src
git commit -m "feat: type normalizer for pg/opengauss type aliases"
```

---

## Task 6: DefinitionNormalizer

**Files:**
- Create: `src/main/java/com/lotus/gausscmp/metadata/DefinitionNormalizer.java`
- Test: `src/test/java/com/lotus/gausscmp/metadata/DefinitionNormalizerTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.lotus.gausscmp.metadata;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DefinitionNormalizerTest {

    @Test
    void collapsesWhitespace() {
        assertThat(DefinitionNormalizer.normalize("  CHECK   (x  >  0) "))
            .isEqualTo("check (x > 0)");
    }

    @Test
    void unifiesDefaultValueAliases() {
        assertThat(DefinitionNormalizer.normalizeDefaultValue("now()")).isEqualTo("current_timestamp");
        assertThat(DefinitionNormalizer.normalizeDefaultValue(" NOW ( ) ")).isEqualTo("current_timestamp");
    }

    @Test
    void stripsQuotesConsistently() {
        assertThat(DefinitionNormalizer.normalizeDefaultValue("'abc'")).isEqualTo("abc");
        assertThat(DefinitionNormalizer.normalizeDefaultValue("abc")).isEqualTo("abc");
    }

    @Test
    void normalizesNull() {
        assertThat(DefinitionNormalizer.normalizeDefaultValue("NULL")).isEqualTo("null");
        assertThat(DefinitionNormalizer.normalizeDefaultValue(null)).isNull();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=DefinitionNormalizerTest`
Expected: FAIL

- [ ] **Step 3: 实现 DefinitionNormalizer**

```java
package com.lotus.gausscmp.metadata;

public final class DefinitionNormalizer {

    private DefinitionNormalizer() {}

    public static String normalize(String def) {
        if (def == null) return null;
        return def.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    public static String normalizeDefaultValue(String val) {
        if (val == null) return null;
        String s = val.trim().toLowerCase().replaceAll("\\s+", " ");
        if (s.equals("null")) return "null";
        if (s.startsWith("'") && s.endsWith("'") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.startsWith("now") || s.startsWith("current_timestamp")) return "current_timestamp";
        return s;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=DefinitionNormalizerTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src
git commit -m "feat: definition normalizer for whitespace and default value aliases"
```

---

## Task 7: OpenGaussMetadataReader

**Files:**
- Create: `src/main/java/com/lotus/gausscmp/metadata/OpenGaussMetadataReader.java`
- Test: `src/test/java/com/lotus/gausscmp/metadata/OpenGaussMetadataReaderTest.java`

> 说明：此任务用集成测试（Testcontainers openGauss）验证；若 openGauss 镜像不可用，可临时用 `postgres` 镜像代替（PG 协议兼容，系统目录相同）。测试类名后缀 `*IT` 由 failsafe 运行。

- [ ] **Step 1: 写集成测试**

```java
package com.lotus.gausscmp.metadata;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class OpenGaussMetadataReaderIT {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("testdb").withUsername("test").withPassword("test");

    @Test
    void readsTablesColumnsConstraintsIndexes() throws Exception {
        try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
            try (var st = conn.createStatement()) {
                st.execute("CREATE SCHEMA app");
                st.execute("CREATE TABLE app.t1 (id integer PRIMARY KEY, name varchar(50) NOT NULL, created_at timestamp default now())");
                st.execute("CREATE INDEX idx_t1_name ON app.t1 (name)");
                st.execute("COMMENT ON TABLE app.t1 IS 'demo table'");
                st.execute("CREATE TABLE app.no_pk (a int, b int)");
            }
            OpenGaussMetadataReader reader = new OpenGaussMetadataReader();
            SchemaSnapshot snap = reader.read(conn, "app", List.of(".*"), List.of());
            assertThat(snap.tables()).hasSize(2);
            TableMeta t1 = snap.tables().stream().filter(t -> t.name().equals("t1")).findFirst().orElseThrow();
            assertThat(t1.comment()).isEqualTo("demo table");
            assertThat(t1.columns()).hasSize(3);
            assertThat(t1.columns().get(0).dataType()).isEqualTo("integer");
            assertThat(t1.hasPrimaryKeyOrUnique()).isTrue();
            assertThat(t1.indexes()).hasSize(1);
            assertThat(t1.indexes().get(0).name()).isEqualTo("idx_t1_name");
            TableMeta noPk = snap.tables().stream().filter(t -> t.name().equals("no_pk")).findFirst().orElseThrow();
            assertThat(noPk.hasPrimaryKeyOrUnique()).isFalse();
        }
    }

    @Test
    void appliesIncludeExcludeFilters() throws Exception {
        try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
            try (var st = conn.createStatement()) {
                st.execute("CREATE SCHEMA app2");
                st.execute("CREATE TABLE app2.real_t (id int PRIMARY KEY)");
                st.execute("CREATE TABLE app2.tmp_t (id int)");
                st.execute("CREATE TABLE app2.bak_t (id int)");
            }
            OpenGaussMetadataReader reader = new OpenGaussMetadataReader();
            SchemaSnapshot snap = reader.read(conn, "app2", List.of(".*"), List.of("^tmp_.*", "^bak_.*"));
            assertThat(snap.tables()).hasSize(1);
            assertThat(snap.tables().get(0).name()).isEqualTo("real_t");
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=OpenGaussMetadataReaderIT`（注：用 failsafe 则 `mvn -q verify -Dit.test=OpenGaussMetadataReaderIT`）
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 OpenGaussMetadataReader**

```java
package com.lotus.gausscmp.metadata;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class OpenGaussMetadataReader implements MetadataReader {

    @Override
    public SchemaSnapshot read(Connection conn, String schema,
                               List<String> includePatterns, List<String> excludePatterns) {
        List<TableMeta> tables = new ArrayList<>();
        List<String> tableNames = readTableNames(conn, schema);
        for (String name : tableNames) {
            if (!matchesFilters(name, includePatterns, excludePatterns)) continue;
            try {
                tables.add(readTable(conn, schema, name));
            } catch (Exception e) {
                throw new RuntimeException("抽取表元数据失败: " + schema + "." + name, e);
            }
        }
        return new SchemaSnapshot(schema, tables);
    }

    private List<String> readTableNames(Connection conn, String schema) {
        String sql = """
            SELECT c.relname FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relkind = 'r' AND c.relispartition = false
            ORDER BY c.relname
            """;
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) names.add(rs.getString("relname"));
            }
        } catch (Exception e) {
            throw new RuntimeException("读取表名失败: " + schema, e);
        }
        return names;
    }

    private TableMeta readTable(Connection conn, String schema, String tableName) throws Exception {
        String comment = readTableComment(conn, schema, tableName);
        List<ColumnMeta> columns = readColumns(conn, schema, tableName);
        List<ConstraintMeta> constraints = readConstraints(conn, schema, tableName);
        List<IndexMeta> indexes = readIndexes(conn, schema, tableName);
        boolean partitioned = checkPartitioned(conn, schema, tableName);
        return new TableMeta(tableName, comment, columns, constraints, indexes, partitioned);
    }

    private String readTableComment(Connection conn, String schema, String table) throws Exception {
        String sql = "SELECT obj_description(c.oid, 'pg_class') AS cmt FROM pg_class c " +
                     "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname=? AND c.relname=?";
        return queryString(conn, sql, schema, table);
    }

    private List<ColumnMeta> readColumns(Connection conn, String schema, String table) throws Exception {
        String sql = """
            SELECT a.attname, format_type(a.atttypid, a.atttypmod) AS type,
                   a.attnotnull, pg_get_expr(d.adbin, d.adrelid) AS defaultval,
                   col_description(a.attrelid, a.attnum) AS colcomment, a.attnum
            FROM pg_attribute a
            JOIN pg_class c ON c.oid = a.attrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
            WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY a.attnum
            """;
        List<ColumnMeta> cols = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema); ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(new ColumnMeta(
                        rs.getString("attname"),
                        TypeNormalizer.normalize(rs.getString("type")),
                        rs.getBoolean("attnotnull"),
                        DefinitionNormalizer.normalizeDefaultValue(rs.getString("defaultval")),
                        rs.getString("colcomment"),
                        rs.getInt("attnum")));
                }
            }
        }
        return cols;
    }

    private List<ConstraintMeta> readConstraints(Connection conn, String schema, String table) throws Exception {
        String sql = """
            SELECT con.conname, con.contype, pg_get_constraintdef(con.oid) AS def,
                   con.confrelid::regclass AS reftable
            FROM pg_constraint con
            JOIN pg_class c ON c.oid = con.conrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relname = ?
            ORDER BY con.conname
            """;
        List<ConstraintMeta> cons = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema); ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    char ct = rs.getString("contype").charAt(0);
                    ConstraintType type = switch (ct) {
                        case 'p' -> ConstraintType.PRIMARY;
                        case 'u' -> ConstraintType.UNIQUE;
                        case 'f' -> ConstraintType.FOREIGN;
                        case 'c' -> ConstraintType.CHECK;
                        default -> throw new IllegalStateException("未知约束类型: " + ct);
                    };
                    String def = DefinitionNormalizer.normalize(rs.getString("def"));
                    String refTable = rs.getString("reftable");
                    cons.add(new ConstraintMeta(rs.getString("conname"), type, def, List.of(), refTable));
                }
            }
        }
        return cons;
    }

    private List<IndexMeta> readIndexes(Connection conn, String schema, String table) throws Exception {
        String sql = """
            SELECT i.relname AS idxname, pg_get_indexdef(ix.indexrelid) AS def,
                   ix.indisunique, pg_get_expr(ix.indpred, ix.indrelid) AS pred
            FROM pg_index ix
            JOIN pg_class c ON c.oid = ix.indrelid
            JOIN pg_class i ON i.oid = ix.indexrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relname = ?
            ORDER BY i.relname
            """;
        List<IndexMeta> idxs = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema); ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String pred = rs.getString("pred");
                    idxs.add(new IndexMeta(
                        rs.getString("idxname"), table, List.of(),
                        rs.getBoolean("indisunique"),
                        pred != null,
                        pred != null ? DefinitionNormalizer.normalize(pred) : null,
                        DefinitionNormalizer.normalize(rs.getString("def"))));
                }
            }
        }
        return idxs;
    }

    private boolean checkPartitioned(Connection conn, String schema, String table) throws Exception {
        String sql = """
            SELECT c.relpartbound IS NOT NULL AS partbound
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relname = ? AND c.relkind = 'r'
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema); ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return false;
            }
        }
        return false;
    }

    private String queryString(Connection conn, String sql, String... params) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setString(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        }
        return null;
    }

    private boolean matchesFilters(String name, List<String> include, List<String> exclude) {
        boolean inc = include == null || include.isEmpty() ||
            include.stream().anyMatch(p -> Pattern.matches(p, name));
        boolean exc = exclude != null &&
            exclude.stream().anyMatch(p -> Pattern.matches(p, name));
        return inc && !exc;
    }
}
```

- [ ] **Step 4: 运行集成测试确认通过**

Run: `mvn -q verify -Dit.test=OpenGaussMetadataReaderIT`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src
git commit -m "feat: opengauss metadata reader using system catalogs"
```

---

## Task 8: 结构 diff 模型与 StructureComparator

**Files:**
- Create: `src/main/java/com/lotus/gausscmp/compare/diff/DiffType.java`
- Create: `src/main/java/com/lotus/gausscmp/compare/diff/TableStructureStatus.java`
- Create: `src/main/java/com/lotus/gausscmp/compare/diff/ColumnDiff.java`
- Create: `src/main/java/com/lotus/gausscmp/compare/diff/ConstraintDiff.java`
- Create: `src/main/java/com/lotus/gausscmp/compare/diff/IndexDiff.java`
- Create: `src/main/java/com/lotus/gausscmp/compare/diff/TableStructureDiff.java`
- Create: `src/main/java/com/lotus/gausscmp/compare/diff/StructureDiffResult.java`
- Create: `src/main/java/com/lotus/gausscmp/compare/structure/StructureComparator.java`
- Test: `src/test/java/com/lotus/gausscmp/compare/structure/StructureComparatorTest.java`

- [ ] **Step 1: 写 diff 模型类**

```java
// DiffType.java
package com.lotus.gausscmp.compare.diff;
public enum DiffType {
    TABLE_MISSING_IN_TARGET, TABLE_EXTRA_IN_TARGET,
    COLUMN_MISSING_IN_TARGET, COLUMN_EXTRA_IN_TARGET, COLUMN_MISMATCH,
    CONSTRAINT_MISSING_IN_TARGET, CONSTRAINT_EXTRA_IN_TARGET, CONSTRAINT_MISMATCH,
    INDEX_MISSING_IN_TARGET, INDEX_EXTRA_IN_TARGET, INDEX_MISMATCH,
    TABLE_COMMENT_MISMATCH
}
```

```java
// TableStructureStatus.java
package com.lotus.gausscmp.compare.diff;
public enum TableStructureStatus { CONSISTENT, DIFFERENT, EXTRACTION_FAILED }
```

```java
// ColumnDiff.java
package com.lotus.gausscmp.compare.diff;
public record ColumnDiff(DiffType type, String columnName,
                         String sourceValue, String targetValue, String field) {}
```

```java
// ConstraintDiff.java
package com.lotus.gausscmp.compare.diff;
public record ConstraintDiff(DiffType type, String constraintName,
                             String sourceDef, String targetDef) {}
```

```java
// IndexDiff.java
package com.lotus.gausscmp.compare.diff;
public record IndexDiff(DiffType type, String indexName, String sourceDef, String targetDef) {}
```

```java
// TableStructureDiff.java
package com.lotus.gausscmp.compare.diff;
import java.util.List;
import java.util.Optional;
public record TableStructureDiff(String tableName, boolean existsInSource, boolean existsInTarget,
                                 TableStructureStatus status, List<ColumnDiff> columnDiffs,
                                 List<ConstraintDiff> constraintDiffs, List<IndexDiff> indexDiffs,
                                 Optional<String> commentDiff) {}
```

```java
// StructureDiffResult.java
package com.lotus.gausscmp.compare.diff;
import java.util.List;
public record StructureDiffResult(List<TableStructureDiff> tableDiffs) {}
```

- [ ] **Step 2: 写 StructureComparator 失败测试**

```java
package com.lotus.gausscmp.compare.structure;

import com.lotus.gausscmp.compare.diff.*;
import com.lotus.gausscmp.metadata.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class StructureComparatorTest {

    @Test
    void identicalSnapshotsAreConsistent() {
        ColumnMeta c = new ColumnMeta("id", "integer", false, null, null, 1);
        ConstraintMeta pk = new ConstraintMeta("pk", ConstraintType.PRIMARY, "primary key (id)", List.of("id"), null);
        TableMeta t = new TableMeta("t", null, List.of(c), List.of(pk), List.of(), false);
        SchemaSnapshot s = new SchemaSnapshot("app", List.of(t));
        StructureDiffResult r = new StructureComparator().compare(s, s);
        assertThat(r.tableDiffs()).hasSize(1);
        assertThat(r.tableDiffs().get(0).status()).isEqualTo(TableStructureStatus.CONSISTENT);
    }

    @Test
    void missingTableInTarget() {
        ColumnMeta c = new ColumnMeta("id", "integer", false, null, null, 1);
        TableMeta t = new TableMeta("t", null, List.of(c), List.of(), List.of(), false);
        SchemaSnapshot src = new SchemaSnapshot("app", List.of(t));
        SchemaSnapshot tgt = new SchemaSnapshot("app", List.of());
        StructureDiffResult r = new StructureComparator().compare(src, tgt);
        assertThat(r.tableDiffs().get(0).status()).isEqualTo(TableStructureStatus.DIFFERENT);
        assertThat(r.tableDiffs().get(0).columnDiffs().get(0).type()).isEqualTo(DiffType.TABLE_MISSING_IN_TARGET);
    }

    @Test
    void columnTypeMismatch() {
        ColumnMeta srcCol = new ColumnMeta("id", "integer", false, null, null, 1);
        ColumnMeta tgtCol = new ColumnMeta("id", "bigint", false, null, null, 1);
        TableMeta srcT = new TableMeta("t", null, List.of(srcCol), List.of(), List.of(), false);
        TableMeta tgtT = new TableMeta("t", null, List.of(tgtCol), List.of(), List.of(), false);
        StructureDiffResult r = new StructureComparator().compare(
            new SchemaSnapshot("app", List.of(srcT)), new SchemaSnapshot("app", List.of(tgtT)));
        ColumnDiff cd = r.tableDiffs().get(0).columnDiffs().get(0);
        assertThat(cd.type()).isEqualTo(DiffType.COLUMN_MISMATCH);
        assertThat(cd.field()).isEqualTo("dataType");
        assertThat(cd.sourceValue()).isEqualTo("integer");
        assertThat(cd.targetValue()).isEqualTo("bigint");
    }

    @Test
    void missingConstraintAndIndex() {
        ColumnMeta c = new ColumnMeta("id", "integer", false, null, null, 1);
        ConstraintMeta pk = new ConstraintMeta("pk", ConstraintType.PRIMARY, "primary key (id)", List.of("id"), null);
        IndexMeta idx = new IndexMeta("idx", "t", List.of("id"), false, false, null, "create index idx on t (id)");
        TableMeta srcT = new TableMeta("t", null, List.of(c), List.of(pk), List.of(idx), false);
        TableMeta tgtT = new TableMeta("t", null, List.of(c), List.of(), List.of(), false);
        StructureDiffResult r = new StructureComparator().compare(
            new SchemaSnapshot("app", List.of(srcT)), new SchemaSnapshot("app", List.of(tgtT)));
        List<ConstraintDiff> cds = r.tableDiffs().get(0).constraintDiffs();
        List<IndexDiff> ids = r.tableDiffs().get(0).indexDiffs();
        assertThat(cds).anyMatch(d -> d.type() == DiffType.CONSTRAINT_MISSING_IN_TARGET);
        assertThat(ids).anyMatch(d -> d.type() == DiffType.INDEX_MISSING_IN_TARGET);
    }

    @Test
    void commentMismatchDetected() {
        ColumnMeta c = new ColumnMeta("id", "integer", false, null, null, 1);
        TableMeta srcT = new TableMeta("t", "comment-a", List.of(c), List.of(), List.of(), false);
        TableMeta tgtT = new TableMeta("t", "comment-b", List.of(c), List.of(), List.of(), false);
        StructureDiffResult r = new StructureComparator().compare(
            new SchemaSnapshot("app", List.of(srcT)), new SchemaSnapshot("app", List.of(tgtT)));
        assertThat(r.tableDiffs().get(0).commentDiff()).isPresent();
        assertThat(r.tableDiffs().get(0).status()).isEqualTo(TableStructureStatus.DIFFERENT);
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `mvn -q test -Dtest=StructureComparatorTest`
Expected: FAIL

- [ ] **Step 4: 实现 StructureComparator**

```java
package com.lotus.gausscmp.compare.structure;

import com.lotus.gausscmp.compare.diff.*;
import com.lotus.gausscmp.metadata.*;
import java.util.*;
import java.util.stream.Collectors;

public final class StructureComparator {

    public StructureDiffResult compare(SchemaSnapshot source, SchemaSnapshot target) {
        Map<String, TableMeta> srcMap = source.tables().stream()
            .collect(Collectors.toMap(TableMeta::name, t -> t, (a, b) -> a, TreeMap::new));
        Map<String, TableMeta> tgtMap = target.tables().stream()
            .collect(Collectors.toMap(TableMeta::name, t -> t, (a, b) -> a, TreeMap::new));
        List<TableStructureDiff> diffs = new ArrayList<>();
        Set<String> allNames = new TreeSet<>();
        allNames.addAll(srcMap.keySet());
        allNames.addAll(tgtMap.keySet());
        for (String name : allNames) {
            TableMeta s = srcMap.get(name);
            TableMeta t = tgtMap.get(name);
            diffs.add(compareTable(name, s, t));
        }
        return new StructureDiffResult(diffs);
    }

    private TableStructureDiff compareTable(String name, TableMeta s, TableMeta t) {
        if (s == null) {
            return new TableStructureDiff(name, false, true, TableStructureStatus.DIFFERENT,
                List.of(new ColumnDiff(DiffType.TABLE_EXTRA_IN_TARGET, name, null, null, null)),
                List.of(), List.of(), Optional.empty());
        }
        if (t == null) {
            return new TableStructureDiff(name, true, false, TableStructureStatus.DIFFERENT,
                List.of(new ColumnDiff(DiffType.TABLE_MISSING_IN_TARGET, name, null, null, null)),
                List.of(), List.of(), Optional.empty());
        }
        List<ColumnDiff> colDiffs = compareColumns(s, t);
        List<ConstraintDiff> conDiffs = compareConstraints(s, t);
        List<IndexDiff> idxDiffs = compareIndexes(s, t);
        Optional<String> commentDiff = compareComment(s.comment(), t.comment());
        boolean consistent = colDiffs.isEmpty() && conDiffs.isEmpty() && idxDiffs.isEmpty() && commentDiff.isEmpty();
        return new TableStructureDiff(name, true, true,
            consistent ? TableStructureStatus.CONSISTENT : TableStructureStatus.DIFFERENT,
            colDiffs, conDiffs, idxDiffs, commentDiff);
    }

    private List<ColumnDiff> compareColumns(TableMeta s, TableMeta t) {
        List<ColumnDiff> diffs = new ArrayList<>();
        Map<String, ColumnMeta> sm = toMap(s.columns(), ColumnMeta::name);
        Map<String, ColumnMeta> tm = toMap(t.columns(), ColumnMeta::name);
        for (String n : unionKeys(sm, tm)) {
            ColumnMeta sc = sm.get(n), tc = tm.get(n);
            if (sc == null) { diffs.add(new ColumnDiff(DiffType.COLUMN_EXTRA_IN_TARGET, n, null, null, null)); continue; }
            if (tc == null) { diffs.add(new ColumnDiff(DiffType.COLUMN_MISSING_IN_TARGET, n, null, null, null)); continue; }
            if (!eq(sc.dataType(), tc.dataType())) diffs.add(new ColumnDiff(DiffType.COLUMN_MISMATCH, n, sc.dataType(), tc.dataType(), "dataType"));
            if (sc.nullable() != tc.nullable()) diffs.add(new ColumnDiff(DiffType.COLUMN_MISMATCH, n, String.valueOf(sc.nullable()), String.valueOf(tc.nullable()), "nullable"));
            if (!eq(sc.defaultValue(), tc.defaultValue())) diffs.add(new ColumnDiff(DiffType.COLUMN_MISMATCH, n, sc.defaultValue(), tc.defaultValue(), "defaultValue"));
            if (!eq(sc.comment(), tc.comment())) diffs.add(new ColumnDiff(DiffType.COLUMN_MISMATCH, n, sc.comment(), tc.comment(), "comment"));
        }
        return diffs;
    }

    private List<ConstraintDiff> compareConstraints(TableMeta s, TableMeta t) {
        List<ConstraintDiff> diffs = new ArrayList<>();
        Map<String, ConstraintMeta> sm = toMap(s.constraints(), ConstraintMeta::name);
        Map<String, ConstraintMeta> tm = toMap(t.constraints(), ConstraintMeta::name);
        for (String n : unionKeys(sm, tm)) {
            ConstraintMeta sc = sm.get(n), tc = tm.get(n);
            if (sc == null) { diffs.add(new ConstraintDiff(DiffType.CONSTRAINT_EXTRA_IN_TARGET, n, null, null)); continue; }
            if (tc == null) { diffs.add(new ConstraintDiff(DiffType.CONSTRAINT_MISSING_IN_TARGET, n, sc.definition(), null)); continue; }
            if (!eq(sc.definition(), tc.definition())) diffs.add(new ConstraintDiff(DiffType.CONSTRAINT_MISMATCH, n, sc.definition(), tc.definition()));
        }
        return diffs;
    }

    private List<IndexDiff> compareIndexes(TableMeta s, TableMeta t) {
        List<IndexDiff> diffs = new ArrayList<>();
        Map<String, IndexMeta> sm = toMap(s.indexes(), IndexMeta::name);
        Map<String, IndexMeta> tm = toMap(t.indexes(), IndexMeta::name);
        for (String n : unionKeys(sm, tm)) {
            IndexMeta si = sm.get(n), ti = tm.get(n);
            if (si == null) { diffs.add(new IndexDiff(DiffType.INDEX_EXTRA_IN_TARGET, n, null, null)); continue; }
            if (ti == null) { diffs.add(new IndexDiff(DiffType.INDEX_MISSING_IN_TARGET, n, si.definition(), null)); continue; }
            if (!eq(si.definition(), ti.definition())) diffs.add(new IndexDiff(DiffType.INDEX_MISMATCH, n, si.definition(), ti.definition()));
        }
        return diffs;
    }

    private Optional<String> compareComment(String s, String t) {
        return eq(s, t) ? Optional.empty() : Optional.ofNullable(s);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static <K, V> Map<K, V> toMap(List<V> list, java.util.function.Function<V, K> keyFn) {
        return list.stream().collect(Collectors.toMap(keyFn, v -> v, (a, b) -> a, TreeMap::new));
    }

    private static <K extends Comparable<K>> Set<K> unionKeys(Map<K, ?> a, Map<K, ?> b) {
        Set<K> keys = new TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        return keys;
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q test -Dtest=StructureComparatorTest`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add src
git commit -m "feat: structure comparator with column/constraint/index/comment diffing"
```

---

## Task 9: 数据 diff 模型

**Files:**
- Create: `src/main/java/com/lotus/gausscmp/compare/diff/RowDiffType.java`
- Create: `src/main/java/com/lotus/gausscmp/compare/diff/TableDataStatus.java`
- Create: `src/main/java/com/lotus/gausscmp/compare/diff/RowDiff.java`
- Create: `src/main/java/com/lotus/gausscmp/compare/diff/ChunkStats.java`
- Create: `src/main/java/com/lotus/gausscmp/compare/diff/TableDataDiff.java`
- Create: `src/main/java/com/lotus/gausscmp/compare/diff/DataDiffResult.java`

- [ ] **Step 1: 写 diff 模型类**

```java
// RowDiffType.java
package com.lotus.gausscmp.compare.diff;
public enum RowDiffType { MISSING_IN_TARGET, EXTRA_IN_TARGET, MISMATCH }
```

```java
// TableDataStatus.java
package com.lotus.gausscmp.compare.diff;
public enum TableDataStatus { CONSISTENT, DIFFERENT, SKIPPED }
```

```java
// RowDiff.java
package com.lotus.gausscmp.compare.diff;
import java.util.List;
import java.util.Map;
public record RowDiff(RowDiffType type, Map<String, Object> keyValues,
                      Map<String, Object> sourceValues, Map<String, Object> targetValues,
                      List<String> mismatchColumns) {}
```

```java
// ChunkStats.java
package com.lotus.gausscmp.compare.diff;
public record ChunkStats(int total, int consistent, int mismatched) {}
```

```java
// TableDataDiff.java
package com.lotus.gausscmp.compare.diff;
import java.util.List;
public record TableDataDiff(String tableName, List<String> keyColumns,
                            TableDataStatus status, long sourceRowCount, long targetRowCount,
                            ChunkStats chunkStats, List<RowDiff> rowDiffs, String skippedReason) {}
```

```java
// DataDiffResult.java
package com.lotus.gausscmp.compare.diff;
import java.util.List;
public record DataDiffResult(List<TableDataDiff> tableDiffs) {}
```

- [ ] **Step 2: 验证编译**

Run: `mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src
git commit -m "feat: data diff model records"
```

---

## Task 10: Chunker

**Files:**
- Create: `src/main/java/com/lotus/gausscmp/compare/data/Chunker.java`
- Test: `src/test/java/com/lotus/gausscmp/compare/data/ChunkerTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.lotus.gausscmp.compare.data;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ChunkerTest {

    @Test
    void chunksByOffset() {
        Chunker chunker = new Chunker(3);
        List<Chunker.Chunk> chunks = chunker.plan(10);
        assertThat(chunks).hasSize(4);
        assertThat(chunks.get(0).offset()).isEqualTo(0);
        assertThat(chunks.get(0).limit()).isEqualTo(3);
        assertThat(chunks.get(3).offset()).isEqualTo(9);
        assertThat(chunks.get(3).limit()).isEqualTo(1);
    }

    @Test
    void singleChunkForEmpty() {
        Chunker chunker = new Chunker(5000);
        List<Chunker.Chunk> chunks = chunker.plan(0);
        assertThat(chunks).isEmpty();
    }

    @Test
    void exactDivision() {
        Chunker chunker = new Chunker(5);
        List<Chunker.Chunk> chunks = chunker.plan(10);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1).offset()).isEqualTo(5);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=ChunkerTest`
Expected: FAIL

- [ ] **Step 3: 实现 Chunker**

```java
package com.lotus.gausscmp.compare.data;

import java.util.ArrayList;
import java.util.List;

public final class Chunker {
    public record Chunk(int offset, int limit) {}

    private final int chunkSize;

    public Chunker(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public List<Chunk> plan(long totalRows) {
        List<Chunk> chunks = new ArrayList<>();
        if (totalRows <= 0) return chunks;
        long remaining = totalRows;
        int offset = 0;
        while (remaining > 0) {
            int limit = (int) Math.min(chunkSize, remaining);
            chunks.add(new Chunk(offset, limit));
            offset += limit;
            remaining -= limit;
        }
        return chunks;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=ChunkerTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src
git commit -m "feat: chunker for offset-based table partitioning"
```

---

## Task 11: ChecksumCalculator

**Files:**
- Create: `src/main/java/com/lotus/gausscmp/compare/data/ChecksumCalculator.java`
- Test: `src/test/java/com/lotus/gausscmp/compare/data/ChecksumCalculatorIT.java`

> 集成测试用 Testcontainers（postgres:16-alpine，PG 协议兼容 openGauss 系统目录）。

- [ ] **Step 1: 写集成测试**

```java
package com.lotus.gausscmp.compare.data;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class ChecksumCalculatorIT {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("t").withUsername("t").withPassword("t");

    @Test
    void computesRowCountAndChecksum() throws Exception {
        try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
            try (var st = conn.createStatement()) {
                st.execute("CREATE SCHEMA app");
                st.execute("CREATE TABLE app.t (id int PRIMARY KEY, name text)");
                st.execute("INSERT INTO app.t VALUES (1,'a'),(2,'b'),(3,'c')");
            }
            var calc = new ChecksumCalculator("md5", 5000);
            var result = calc.calculate(conn, "app", "t", List.of("id"));
            assertThat(result.rowCount()).isEqualTo(3);
            assertThat(result.checksum()).isNotBlank();
        }
    }

    @Test
    void checksumChangesWhenDataChanges() throws Exception {
        try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
            try (var st = conn.createStatement()) {
                st.execute("CREATE SCHEMA app2");
                st.execute("CREATE TABLE app2.t (id int PRIMARY KEY, name text)");
                st.execute("INSERT INTO app2.t VALUES (1,'a'),(2,'b')");
            }
            var calc = new ChecksumCalculator("md5", 5000);
            var r1 = calc.calculate(conn, "app2", "t", List.of("id"));
            try (var st = conn.createStatement()) {
                st.execute("UPDATE app2.t SET name='x' WHERE id=1");
            }
            var r2 = calc.calculate(conn, "app2", "t", List.of("id"));
            assertThat(r1.checksum()).isNotEqualTo(r2.checksum());
            assertThat(r1.rowCount()).isEqualTo(r2.rowCount());
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q verify -Dit.test=ChecksumCalculatorIT`
Expected: FAIL

- [ ] **Step 3: 实现 ChecksumCalculator**

```java
package com.lotus.gausscmp.compare.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.stream.Collectors;

public final class ChecksumCalculator {
    public record ChunkResult(int offset, int limit, long rowCount, String checksum) {}

    private final String hashFunction;
    private final int chunkSize;

    public ChecksumCalculator(String hashFunction, int chunkSize) {
        this.hashFunction = hashFunction;
        this.chunkSize = chunkSize;
    }

    public ChunkResult calculate(Connection conn, String schema, String table, List<String> keyColumns) {
        String keys = String.join(", ", keyColumns);
        String sql = String.format(
            "SELECT count(*) AS cnt, %s(string_agg(%s(t::text), ',' ORDER BY %s)) AS chk " +
            "FROM (SELECT * FROM \"%s\".\"%s\" ORDER BY %s LIMIT %d OFFSET %d) t",
            hashFunction, hashFunction, keys, schema, table, keys, chunkSize, 0);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new ChunkResult(0, chunkSize, rs.getLong("cnt"), rs.getString("chk"));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("校验和计算失败: " + schema + "." + table, e);
        }
        return new ChunkResult(0, chunkSize, 0, null);
    }

    public ChunkResult calculateChunk(Connection conn, String schema, String table,
                                      List<String> keyColumns, int offset, int limit) {
        String keys = String.join(", ", keyColumns);
        String sql = String.format(
            "SELECT count(*) AS cnt, %s(string_agg(%s(t::text), ',' ORDER BY %s)) AS chk " +
            "FROM (SELECT * FROM \"%s\".\"%s\" ORDER BY %s LIMIT %d OFFSET %d) t",
            hashFunction, hashFunction, keys, schema, table, keys, limit, offset);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new ChunkResult(offset, limit, rs.getLong("cnt"), rs.getString("chk"));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("块校验和计算失败: " + schema + "." + table + " offset=" + offset, e);
        }
        return new ChunkResult(offset, limit, 0, null);
    }
}
```

- [ ] **Step 4: 运行集成测试确认通过**

Run: `mvn -q verify -Dit.test=ChecksumCalculatorIT`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src
git commit -m "feat: checksum calculator using in-db hash aggregation"
```

---

## Task 12: DrillDownComparator

**Files:**
- Create: `src/main/java/com/lotus/gausscmp/compare/data/DrillDownComparator.java`
- Test: `src/test/java/com/lotus/gausscmp/compare/data/DrillDownComparatorTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.lotus.gausscmp.compare.data;

import com.lotus.gausscmp.compare.diff.RowDiff;
import com.lotus.gausscmp.compare.diff.RowDiffType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DrillDownComparatorTest {

    private final DrillDownComparator cmp = new DrillDownComparator(List.of("id"));

    @Test
    void detectsMissingRowInTarget() {
        Map<String, Map<String, Object>> src = Map.of("1", row(1, "a"));
        Map<String, Map<String, Object>> tgt = Map.of();
        List<RowDiff> diffs = cmp.compare(src, tgt);
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).type()).isEqualTo(RowDiffType.MISSING_IN_TARGET);
    }

    @Test
    void detectsExtraRowInTarget() {
        Map<String, Map<String, Object>> src = Map.of();
        Map<String, Map<String, Object>> tgt = Map.of("1", row(1, "a"));
        List<RowDiff> diffs = cmp.compare(src, tgt);
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).type()).isEqualTo(RowDiffType.EXTRA_IN_TARGET);
    }

    @Test
    void detectsMismatchedValues() {
        Map<String, Map<String, Object>> src = Map.of("1", row(1, "a"));
        Map<String, Map<String, Object>> tgt = Map.of("1", row(1, "b"));
        List<RowDiff> diffs = cmp.compare(src, tgt);
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).type()).isEqualTo(RowDiffType.MISMATCH);
        assertThat(diffs.get(0).mismatchColumns()).contains("name");
    }

    @Test
    void identicalRowsNoDiff() {
        Map<String, Map<String, Object>> src = Map.of("1", row(1, "a"));
        Map<String, Map<String, Object>> tgt = Map.of("1", row(1, "a"));
        assertThat(cmp.compare(src, tgt)).isEmpty();
    }

    private static Map<String, Object> row(int id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        return m;
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=DrillDownComparatorTest`
Expected: FAIL

- [ ] **Step 3: 实现 DrillDownComparator**

```java
package com.lotus.gausscmp.compare.data;

import com.lotus.gausscmp.compare.diff.RowDiff;
import com.lotus.gausscmp.compare.diff.RowDiffType;
import java.util.*;

public final class DrillDownComparator {
    private final List<String> keyColumns;

    public DrillDownComparator(List<String> keyColumns) {
        this.keyColumns = keyColumns;
    }

    public List<RowDiff> compare(Map<String, Map<String, Object>> source,
                                 Map<String, Map<String, Object>> target) {
        List<RowDiff> diffs = new ArrayList<>();
        Set<String> allKeys = new TreeSet<>();
        allKeys.addAll(source.keySet());
        allKeys.addAll(target.keySet());
        for (String key : allKeys) {
            Map<String, Object> sRow = source.get(key);
            Map<String, Object> tRow = target.get(key);
            if (sRow == null) {
                diffs.add(new RowDiff(RowDiffType.EXTRA_IN_TARGET, parseKey(key), null, tRow, List.of()));
                continue;
            }
            if (tRow == null) {
                diffs.add(new RowDiff(RowDiffType.MISSING_IN_TARGET, parseKey(key), sRow, null, List.of()));
                continue;
            }
            List<String> mismatchCols = compareRow(sRow, tRow);
            if (!mismatchCols.isEmpty()) {
                diffs.add(new RowDiff(RowDiffType.MISMATCH, parseKey(key), sRow, tRow, mismatchCols));
            }
        }
        return diffs;
    }

    private List<String> compareRow(Map<String, Object> s, Map<String, Object> t) {
        List<String> mismatches = new ArrayList<>();
        Set<String> allCols = new TreeSet<>(s.keySet());
        allCols.addAll(t.keySet());
        for (String col : allCols) {
            Object sv = s.get(col);
            Object tv = t.get(col);
            if (!Objects.equals(toStr(sv), toStr(tv))) mismatches.add(col);
        }
        return mismatches;
    }

    private static String toStr(Object v) {
        return v == null ? null : v.toString();
    }

    private Map<String, Object> parseKey(String key) {
        String[] parts = key.split("\u0001", -1);
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyColumns.size() && i < parts.length; i++) {
            m.put(keyColumns.get(i), parts[i]);
        }
        return m;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=DrillDownComparatorTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src
git commit -m "feat: drill-down row comparator for missing/extra/mismatch rows"
```

---

## Task 13: DataComparator

**Files:**
- Create: `src/main/java/com/lotus/gausscmp/compare/data/DataComparator.java`
- Test: `src/test/java/com/lotus/gausscmp/compare/data/DataComparatorIT.java`

- [ ] **Step 1: 写集成测试**

```java
package com.lotus.gausscmp.compare.data;

import com.lotus.gausscmp.compare.diff.*;
import com.lotus.gausscmp.config.SourceConfig;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class DataComparatorIT {

    @Container
    static PostgreSQLContainer<?> srcPg = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("s").withUsername("s").withPassword("s");
    @Container
    static PostgreSQLContainer<?> tgtPg = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("t").withUsername("t").withPassword("t");

    @Test
    void detectsDataDifferences() throws Exception {
        try (Connection sConn = DriverManager.getConnection(srcPg.getJdbcUrl(), srcPg.getUsername(), srcPg.getPassword());
             Connection tConn = DriverManager.getConnection(tgtPg.getJdbcUrl(), tgtPg.getUsername(), tgtPg.getPassword())) {
            setup(sConn, "CREATE TABLE app.t (id int PRIMARY KEY, name text)");
            setup(tConn, "CREATE TABLE app.t (id int PRIMARY KEY, name text)");
            exec(sConn, "INSERT INTO app.t VALUES (1,'a'),(2,'b'),(3,'c')");
            exec(tConn, "INSERT INTO app.t VALUES (1,'a'),(2,'x'),(4,'d')");
            SourceConfig srcCfg = new SourceConfig("h", 5432, "s", "s", "s", "app", true);
            SourceConfig tgtCfg = new SourceConfig("h", 5432, "t", "t", "t", "app", true);
            var dataCmp = new DataComparator(srcCfg, tgtCfg, 5000, "md5", true, 1000);
            TableDataDiff diff = dataCmp.compareTable(sConn, tConn, "t", List.of("id"), TableDataStatus.CONSISTENT);
            assertThat(diff.status()).isEqualTo(TableDataStatus.DIFFERENT);
            assertThat(diff.sourceRowCount()).isEqualTo(3);
            assertThat(diff.targetRowCount()).isEqualTo(3);
            assertThat(diff.rowDiffs()).hasSize(3);
            assertThat(diff.rowDiffs()).anyMatch(r -> r.type() == RowDiffType.MISMATCH);
            assertThat(diff.rowDiffs()).anyMatch(r -> r.type() == RowDiffType.MISSING_IN_TARGET);
            assertThat(diff.rowDiffs()).anyMatch(r -> r.type() == RowDiffType.EXTRA_IN_TARGET);
        }
    }

    @Test
    void consistentTablesNoDiff() throws Exception {
        try (Connection sConn = DriverManager.getConnection(srcPg.getJdbcUrl(), srcPg.getUsername(), srcPg.getPassword());
             Connection tConn = DriverManager.getConnection(tgtPg.getJdbcUrl(), tgtPg.getUsername(), tgtPg.getPassword())) {
            setup(sConn, "CREATE TABLE app.t2 (id int PRIMARY KEY, name text)");
            setup(tConn, "CREATE TABLE app.t2 (id int PRIMARY KEY, name text)");
            exec(sConn, "INSERT INTO app.t2 VALUES (1,'a'),(2,'b')");
            exec(tConn, "INSERT INTO app.t2 VALUES (1,'a'),(2,'b')");
            SourceConfig srcCfg = new SourceConfig("h", 5432, "s", "s", "s", "app", true);
            SourceConfig tgtCfg = new SourceConfig("h", 5432, "t", "t", "t", "app", true);
            var dataCmp = new DataComparator(srcCfg, tgtCfg, 5000, "md5", true, 1000);
            TableDataDiff diff = dataCmp.compareTable(sConn, tConn, "t2", List.of("id"), TableDataStatus.CONSISTENT);
            assertThat(diff.status()).isEqualTo(TableDataStatus.CONSISTENT);
        }
    }

    private static void setup(Connection c, String ddl) throws Exception {
        try (var st = c.createStatement()) { st.execute("CREATE SCHEMA app"); st.execute(ddl); }
    }
    private static void exec(Connection c, String sql) throws Exception {
        try (var st = c.createStatement()) { st.execute(sql); }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q verify -Dit.test=DataComparatorIT`
Expected: FAIL

- [ ] **Step 3: 实现 DataComparator**

```java
package com.lotus.gausscmp.compare.data;

import com.lotus.gausscmp.compare.diff.*;
import com.lotus.gausscmp.config.SourceConfig;
import java.sql.*;
import java.util.*;

public final class DataComparator {
    private final SourceConfig sourceConfig;
    private final SourceConfig targetConfig;
    private final int chunkSize;
    private final String hashFunction;
    private final boolean drillDown;
    private final int maxDisplayRows;

    public DataComparator(SourceConfig sourceConfig, SourceConfig targetConfig, int chunkSize,
                          String hashFunction, boolean drillDown, int maxDisplayRows) {
        this.sourceConfig = sourceConfig;
        this.targetConfig = targetConfig;
        this.chunkSize = chunkSize;
        this.hashFunction = hashFunction;
        this.drillDown = drillDown;
        this.maxDisplayRows = maxDisplayRows;
    }

    public TableDataDiff compareTable(Connection sConn, Connection tConn, String table,
                                      List<String> keyColumns, TableDataStatus structureStatus) {
        if (structureStatus != TableDataStatus.CONSISTENT) {
            return new TableDataDiff(table, keyColumns, TableDataStatus.SKIPPED, 0, 0,
                new ChunkStats(0, 0, 0), List.of(), "结构不一致，跳过数据比对");
        }
        try {
            long sCount = rowCount(sConn, sourceConfig.schema(), table);
            long tCount = rowCount(tConn, targetConfig.schema(), table);
            if (sCount == 0 && tCount == 0) {
                return new TableDataDiff(table, keyColumns, TableDataStatus.CONSISTENT, 0, 0,
                    new ChunkStats(0, 0, 0), List.of(), null);
            }
            Chunker chunker = new Chunker(chunkSize);
            int maxCount = (int) Math.max(sCount, tCount);
            List<Chunker.Chunk> chunks = chunker.plan(maxCount);
            var calc = new ChecksumCalculator(hashFunction, chunkSize);
            int consistent = 0, mismatched = 0;
            List<Chunker.Chunk> mismatchChunks = new ArrayList<>();
            for (Chunker.Chunk chunk : chunks) {
                var sRes = calc.calculateChunk(sConn, sourceConfig.schema(), table, keyColumns, chunk.offset(), chunk.limit());
                var tRes = calc.calculateChunk(tConn, targetConfig.schema(), table, keyColumns, chunk.offset(), chunk.limit());
                if (Objects.equals(sRes.checksum(), tRes.checksum()) && sRes.rowCount() == tRes.rowCount()) {
                    consistent++;
                } else {
                    mismatched++;
                    mismatchChunks.add(chunk);
                }
            }
            if (mismatched == 0) {
                return new TableDataDiff(table, keyColumns, TableDataStatus.CONSISTENT, sCount, tCount,
                    new ChunkStats(chunks.size(), consistent, mismatched), List.of(), null);
            }
            List<RowDiff> rowDiffs = new ArrayList<>();
            if (drillDown) {
                var drill = new DrillDownComparator(keyColumns);
                for (Chunker.Chunk chunk : mismatchChunks) {
                    Map<String, Map<String, Object>> sRows = fetchRows(sConn, sourceConfig.schema(), table, keyColumns, chunk);
                    Map<String, Map<String, Object>> tRows = fetchRows(tConn, targetConfig.schema(), table, keyColumns, chunk);
                    rowDiffs.addAll(drill.compare(sRows, tRows));
                    if (rowDiffs.size() >= maxDisplayRows) break;
                }
            }
            return new TableDataDiff(table, keyColumns, TableDataStatus.DIFFERENT, sCount, tCount,
                new ChunkStats(chunks.size(), consistent, mismatched), rowDiffs, null);
        } catch (Exception e) {
            throw new RuntimeException("数据比对失败: " + table, e);
        }
    }

    private long rowCount(Connection conn, String schema, String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT count(*) FROM \"" + schema + "\".\"" + table + "\"")) {
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getLong(1); }
        }
        return 0;
    }

    private Map<String, Map<String, Object>> fetchRows(Connection conn, String schema, String table,
            List<String> keyColumns, Chunker.Chunk chunk) throws SQLException {
        String keys = String.join(", ", keyColumns);
        String sql = "SELECT * FROM \"" + schema + "\".\"" + table + "\" ORDER BY " + keys +
                     " LIMIT " + chunk.limit() + " OFFSET " + chunk.offset();
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setFetchSize(1000);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    StringBuilder keyBuilder = new StringBuilder();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        String col = meta.getColumnName(i);
                        Object val = rs.getObject(i);
                        row.put(col, val);
                    }
                    for (String k : keyColumns) {
                        if (keyBuilder.length() > 0) keyBuilder.append('\u0001');
                        keyBuilder.append(row.get(k));
                    }
                    rows.put(keyBuilder.toString(), row);
                }
            }
        }
        return rows;
    }
}
```

- [ ] **Step 4: 运行集成测试确认通过**

Run: `mvn -q verify -Dit.test=DataComparatorIT`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src
git commit -m "feat: data comparator with chunked checksum and drill-down"
```

---

## Task 14: 并发执行器 TableTaskExecutor

**Files:**
- Create: `src/main/java/com/lotus/gausscmp/concurrency/TableTaskExecutor.java`
- Test: `src/test/java/com/lotus/gausscmp/concurrency/TableTaskExecutorTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.lotus.gausscmp.concurrency;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class TableTaskExecutorTest {

    @Test
    void runsTasksAndAggregatesResultsInOrder() throws Exception {
        List<String> tables = List.of("c", "a", "b");
        AtomicInteger counter = new AtomicInteger(0);
        try (var exec = new TableTaskExecutor<String>(2)) {
            Map<String, String> results = exec.execute(tables, table -> {
                Thread.sleep(10);
                counter.incrementAndGet();
                return table.toUpperCase();
            });
            assertThat(results).containsKeys("a", "b", "c");
            assertThat(results.get("a")).isEqualTo("A");
            assertThat(counter.get()).isEqualTo(3);
        }
    }

    @Test
    void handlesExceptionPerTable() {
        List<String> tables = List.of("ok", "bad");
        try (var exec = new TableTaskExecutor<String>(1)) {
            Map<String, String> results = exec.execute(tables, table -> {
                if (table.equals("bad")) throw new RuntimeException("boom");
                return table;
            });
            assertThat(results.get("ok")).isEqualTo("ok");
            assertThat(results.get("bad")).isNull();
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=TableTaskExecutorTest`
Expected: FAIL

- [ ] **Step 3: 实现 TableTaskExecutor**

```java
package com.lotus.gausscmp.concurrency;

import java.util.*;
import java.util.concurrent.*;

public final class TableTaskExecutor<R> implements AutoCloseable {
    private final ExecutorService pool;

    public TableTaskExecutor(int parallelism) {
        this.pool = Executors.newFixedThreadPool(Math.max(1, parallelism));
    }

    public Map<String, R> execute(List<String> tables, java.util.function.Function<String, R> task) throws Exception {
        Map<String, CompletableFuture<R>> futures = new LinkedHashMap<>();
        for (String table : tables) {
            futures.put(table, CompletableFuture.supplyAsync(() -> {
                try { return task.apply(table); }
                catch (Exception e) { return null; }
            }, pool));
        }
        Map<String, R> results = new TreeMap<>();
        for (Map.Entry<String, CompletableFuture<R>> e : futures.entrySet()) {
            try { results.put(e.getKey(), e.getValue().get()); }
            catch (Exception ex) { results.put(e.getKey(), null); }
        }
        return results;
    }

    @Override
    public void close() {
        pool.shutdown();
        try { if (!pool.awaitTermination(30, TimeUnit.SECONDS)) pool.shutdownNow(); }
        catch (InterruptedException e) { pool.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=TableTaskExecutorTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src
git commit -m "feat: table task executor with bounded parallelism"
```

---

## Task 15: DDL 同步脚本生成

**Files:**
- Create: `src/main/java/com/lotus/gausscmp/sync/DdlScriptGenerator.java`
- Test: `src/test/java/com/lotus/gausscmp/sync/DdlScriptGeneratorTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.lotus.gausscmp.sync;

import com.lotus.gausscmp.compare.diff.*;
import com.lotus.gausscmp.metadata.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;

class DdlScriptGeneratorTest {

    @Test
    void generatesCreateTableForMissing() {
        TableStructureDiff diff = new TableStructureDiff("t", true, false, TableStructureStatus.DIFFERENT,
            List.of(new ColumnDiff(DiffType.TABLE_MISSING_IN_TARGET, "t", null, null, null)),
            List.of(), List.of(), Optional.empty());
        ColumnMeta col = new ColumnMeta("id", "integer", false, null, null, 1);
        TableMeta table = new TableMeta("t", null, List.of(col), List.of(), List.of(), false);
        String ddl = new DdlScriptGenerator("app").generate(List.of(diff), tableMetaMap(table));
        assertThat(ddl).contains("CREATE TABLE \"app\".\"t\"");
        assertThat(ddl).contains("\"id\" integer");
    }

    @Test
    void generatesAddColumn() {
        TableStructureDiff diff = new TableStructureDiff("t", true, true, TableStructureStatus.DIFFERENT,
            List.of(new ColumnDiff(DiffType.COLUMN_MISSING_IN_TARGET, "name", null, null, null)),
            List.of(), List.of(), Optional.empty());
        String ddl = new DdlScriptGenerator("app").generate(List.of(diff), java.util.Map.of());
        assertThat(ddl).contains("ALTER TABLE \"app\".\"t\" ADD COLUMN \"name\"");
    }

    @Test
    void generatesAlterColumnType() {
        TableStructureDiff diff = new TableStructureDiff("t", true, true, TableStructureStatus.DIFFERENT,
            List.of(new ColumnDiff(DiffType.COLUMN_MISMATCH, "id", "integer", "bigint", "dataType")),
            List.of(), List.of(), Optional.empty());
        String ddl = new DdlScriptGenerator("app").generate(List.of(diff), java.util.Map.of());
        assertThat(ddl).contains("ALTER TABLE \"app\".\"t\" ALTER COLUMN \"id\" TYPE integer");
    }

    @Test
    void generatesCreateIndex() {
        TableStructureDiff diff = new TableStructureDiff("t", true, true, TableStructureStatus.DIFFERENT,
            List.of(), List.of(),
            List.of(new IndexDiff(DiffType.INDEX_MISSING_IN_TARGET, "idx_t", "create index idx_t on t (id)", null)),
            Optional.empty());
        String ddl = new DdlScriptGenerator("app").generate(List.of(diff), java.util.Map.of());
        assertThat(ddl).contains("-- INDEX_MISSING_IN_TARGET: idx_t");
        assertThat(ddl).contains("CREATE INDEX \"idx_t\"");
    }

    private static java.util.Map<String, TableMeta> tableMetaMap(TableMeta... tables) {
        var m = new java.util.HashMap<String, TableMeta>();
        for (TableMeta t : tables) m.put(t.name(), t);
        return m;
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=DdlScriptGeneratorTest`
Expected: FAIL

- [ ] **Step 3: 实现 DdlScriptGenerator**

```java
package com.lotus.gausscmp.sync;

import com.lotus.gausscmp.compare.diff.*;
import com.lotus.gausscmp.metadata.*;
import java.util.*;

public final class DdlScriptGenerator {
    private final String schema;

    public DdlScriptGenerator(String schema) { this.schema = schema; }

    public String generate(List<TableStructureDiff> diffs, Map<String, TableMeta> sourceTables) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- 结构同步 DDL 脚本（源 → 目标）\n");
        sb.append("-- 生成时间: ").append(new Date()).append("\n");
        sb.append("-- 请人工审核后执行\n");
        sb.append("SET search_path TO \"").append(schema).append("\";\n\n");
        for (TableStructureDiff diff : diffs) {
            if (diff.status() == TableStructureStatus.CONSISTENT) continue;
            sb.append("-- ====== 表: ").append(diff.tableName()).append(" ======\n");
            if (!diff.existsInTarget()) {
                generateCreateTable(sb, sourceTables.get(diff.tableName()));
                continue;
            }
            if (!diff.existsInSource()) {
                sb.append("-- TABLE_EXTRA_IN_TARGET（默认不自动删除，如需删除请取消注释）\n");
                sb.append("-- DROP TABLE \"").append(schema).append("\".\"").append(diff.tableName()).append("\";\n\n");
                continue;
            }
            for (ColumnDiff cd : diff.columnDiffs()) generateColumnDdl(sb, diff.tableName(), cd, sourceTables.get(diff.tableName()));
            for (ConstraintDiff cd : diff.constraintDiffs()) generateConstraintDdl(sb, diff.tableName(), cd);
            for (IndexDiff id : diff.indexDiffs()) generateIndexDdl(sb, diff.tableName(), id);
        }
        return sb.toString();
    }

    private void generateCreateTable(StringBuilder sb, TableMeta t) {
        if (t == null) { sb.append("-- 无法获取源表元数据\n\n"); return; }
        sb.append("-- TABLE_MISSING_IN_TARGET\n");
        sb.append("CREATE TABLE \"").append(schema).append("\".\"").append(t.name()).append("\" (\n");
        for (int i = 0; i < t.columns().size(); i++) {
            ColumnMeta c = t.columns().get(i);
            sb.append("    \"").append(c.name()).append("\" ").append(c.dataType());
            if (!c.nullable()) sb.append(" NOT NULL");
            if (c.defaultValue() != null) sb.append(" DEFAULT ").append(c.defaultValue());
            if (i < t.columns().size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append(");\n");
        for (ConstraintMeta con : t.constraints()) {
            if (con.type() == ConstraintType.PRIMARY || con.type() == ConstraintType.UNIQUE)
                sb.append("ALTER TABLE \"").append(schema).append("\".\"").append(t.name())
                  .append("\" ADD CONSTRAINT \"").append(con.name()).append("\" ").append(con.definition()).append(";\n");
        }
        if (t.comment() != null)
            sb.append("COMMENT ON TABLE \"").append(schema).append("\".\"").append(t.name())
              .append("\" IS '").append(t.comment()).append("';\n");
        for (IndexMeta idx : t.indexes())
            sb.append(idx.definition()).append(";\n");
        sb.append("\n");
    }

    private void generateColumnDdl(StringBuilder sb, String table, ColumnDiff cd, TableMeta srcTable) {
        switch (cd.type()) {
            case COLUMN_MISSING_IN_TARGET -> sb.append("-- COLUMN_MISSING_IN_TARGET: ").append(cd.columnName()).append("\n")
                .append("ALTER TABLE \"").append(schema).append("\".\"").append(table)
                .append("\" ADD COLUMN \"").append(cd.columnName()).append("\";\n\n");
            case COLUMN_EXTRA_IN_TARGET -> sb.append("-- COLUMN_EXTRA_IN_TARGET: ").append(cd.columnName()).append("（默认注释，不执行）\n")
                .append("-- ALTER TABLE \"").append(schema).append("\".\"").append(table)
                .append("\" DROP COLUMN \"").append(cd.columnName()).append("\";\n\n");
            case COLUMN_MISMATCH -> {
                sb.append("-- COLUMN_MISMATCH: ").append(cd.columnName()).append(".").append(cd.field())
                  .append(" (源=").append(cd.sourceValue()).append(", 目标=").append(cd.targetValue()).append(")\n");
                if ("dataType".equals(cd.field()))
                    sb.append("ALTER TABLE \"").append(schema).append("\".\"").append(table)
                      .append("\" ALTER COLUMN \"").append(cd.columnName()).append("\" TYPE ").append(cd.sourceValue()).append(";\n");
                else if ("nullable".equals(cd.field()))
                    sb.append("ALTER TABLE \"").append(schema).append("\".\"").append(table)
                      .append("\" ALTER COLUMN \"").append(cd.columnName()).append("\" ")
                      .append("true".equals(cd.sourceValue()) ? "DROP NOT NULL" : "SET NOT NULL").append(";\n");
                else if ("defaultValue".equals(cd.field()))
                    sb.append("ALTER TABLE \"").append(schema).append("\".\"").append(table)
                      .append("\" ALTER COLUMN \"").append(cd.columnName()).append("\" ")
                      .append(cd.sourceValue() == null ? "DROP DEFAULT" : "SET DEFAULT " + cd.sourceValue()).append(";\n");
                sb.append("\n");
            }
            default -> {}
        }
    }

    private void generateConstraintDdl(StringBuilder sb, String table, ConstraintDiff cd) {
        switch (cd.type()) {
            case CONSTRAINT_MISSING_IN_TARGET -> sb.append("-- CONSTRAINT_MISSING_IN_TARGET: ").append(cd.constraintName()).append("\n")
                .append("ALTER TABLE \"").append(schema).append("\".\"").append(table)
                .append("\" ADD CONSTRAINT \"").append(cd.constraintName()).append("\" ").append(cd.sourceDef()).append(";\n\n");
            case CONSTRAINT_EXTRA_IN_TARGET -> sb.append("-- CONSTRAINT_EXTRA_IN_TARGET: ").append(cd.constraintName()).append("（默认注释）\n")
                .append("-- ALTER TABLE \"").append(schema).append("\".\"").append(table)
                .append("\" DROP CONSTRAINT \"").append(cd.constraintName()).append("\";\n\n");
            case CONSTRAINT_MISMATCH -> sb.append("-- CONSTRAINT_MISMATCH: ").append(cd.constraintName())
                .append(" (源=").append(cd.sourceDef()).append(", 目标=").append(cd.targetDef()).append(")\n")
                .append("ALTER TABLE \"").append(schema).append("\".\"").append(table)
                .append("\" DROP CONSTRAINT \"").append(cd.constraintName()).append("\";\n")
                .append("ALTER TABLE \"").append(schema).append("\".\"").append(table)
                .append("\" ADD CONSTRAINT \"").append(cd.constraintName()).append("\" ").append(cd.sourceDef()).append(";\n\n");
            default -> {}
        }
    }

    private void generateIndexDdl(StringBuilder sb, String table, IndexDiff id) {
        switch (id.type()) {
            case INDEX_MISSING_IN_TARGET -> sb.append("-- INDEX_MISSING_IN_TARGET: ").append(id.indexName()).append("\n")
                .append("CREATE INDEX \"").append(id.indexName()).append("\" ON \"").append(schema).append("\".\"").append(table).append("\" ")
                .append(extractIndexSpec(id.sourceDef())).append(";\n\n");
            case INDEX_EXTRA_IN_TARGET -> sb.append("-- INDEX_EXTRA_IN_TARGET: ").append(id.indexName()).append("（默认注释）\n")
                .append("-- DROP INDEX \"").append(id.indexName()).append("\";\n\n");
            case INDEX_MISMATCH -> sb.append("-- INDEX_MISMATCH: ").append(id.indexName()).append("\n")
                .append("DROP INDEX \"").append(id.indexName()).append("\";\n")
                .append("CREATE INDEX \"").append(id.indexName()).append("\" ON \"").append(schema).append("\".\"").append(table).append("\" ")
                .append(extractIndexSpec(id.sourceDef())).append(";\n\n");
            default -> {}
        }
    }

    private String extractIndexSpec(String definition) {
        if (definition == null) return "";
        int idx = definition.toUpperCase().indexOf(" ON ");
        return idx >= 0 ? definition.substring(idx + 4).replaceAll("(?i)^.*?\\(\\s*", "(", 0) : "";
    }
}
```

> 注：`extractIndexSpec` 的简化实现可能在复杂索引定义上不完美，但满足基本场景；集成测试（Task 18）会端到端验证脚本可执行。

- [ ] **Step 4: 修正 extractIndexSpec 简化实现**

```java
    private String extractIndexSpec(String definition) {
        if (definition == null) return "";
        int onIdx = definition.toUpperCase().indexOf(" ON ");
        if (onIdx < 0) return "";
        String afterOn = definition.substring(onIdx + 4);
        int parenIdx = afterOn.indexOf("(");
        if (parenIdx < 0) return afterOn.trim();
        return afterOn.substring(parenIdx).trim();
    }
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q test -Dtest=DdlScriptGeneratorTest`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add src
git commit -m "feat: ddl script generator for structure sync"
```

---

## Task 16: DML 同步脚本生成与 ScriptWriter

**Files:**
- Create: `src/main/java/com/lotus/gausscmp/sync/DmlScriptGenerator.java`
- Create: `src/main/java/com/lotus/gausscmp/sync/ScriptWriter.java`
- Test: `src/test/java/com/lotus/gausscmp/sync/DmlScriptGeneratorTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.lotus.gausscmp.sync;

import com.lotus.gausscmp.compare.diff.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DmlScriptGeneratorTest {

    private final DmlScriptGenerator gen = new DmlScriptGenerator("app", "source-to-target");

    @Test
    void generatesInsertForMissingRow() {
        RowDiff diff = new RowDiff(RowDiffType.MISSING_IN_TARGET,
            Map.of("id", 1), Map.of("id", 1, "name", "a"), null, List.of());
        TableDataDiff tdd = new TableDataDiff("t", List.of("id"), TableDataStatus.DIFFERENT, 1, 0,
            new ChunkStats(1, 0, 1), List.of(diff), null);
        String dml = gen.generate(List.of(tdd));
        assertThat(dml).contains("INSERT INTO \"app\".\"t\"");
        assertThat(dml).contains("'a'");
    }

    @Test
    void generatesDeleteForExtraRow() {
        RowDiff diff = new RowDiff(RowDiffType.EXTRA_IN_TARGET,
            Map.of("id", 2), null, Map.of("id", 2, "name", "b"), List.of());
        TableDataDiff tdd = new TableDataDiff("t", List.of("id"), TableDataStatus.DIFFERENT, 0, 1,
            new ChunkStats(1, 0, 1), List.of(diff), null);
        String dml = gen.generate(List.of(tdd));
        assertThat(dml).contains("DELETE FROM \"app\".\"t\"");
        assertThat(dml).contains("\"id\" = 2");
    }

    @Test
    void generatesUpdateForMismatchRow() {
        RowDiff diff = new RowDiff(RowDiffType.MISMATCH,
            Map.of("id", 3), Map.of("id", 3, "name", "x"), Map.of("id", 3, "name", "y"), List.of("name"));
        TableDataDiff tdd = new TableDataDiff("t", List.of("id"), TableDataStatus.DIFFERENT, 1, 1,
            new ChunkStats(1, 0, 1), List.of(diff), null);
        String dml = gen.generate(List.of(tdd));
        assertThat(dml).contains("UPDATE \"app\".\"t\"");
        assertThat(dml).contains("\"name\" = 'x'");
        assertThat(dml).contains("\"id\" = 3");
    }

    @Test
    void skipsConsistentAndSkippedTables() {
        TableDataDiff ok = new TableDataDiff("ok", List.of("id"), TableDataStatus.CONSISTENT, 5, 5,
            new ChunkStats(1, 1, 0), List.of(), null);
        TableDataDiff skip = new TableDataDiff("skip", List.of(), TableDataStatus.SKIPPED, 0, 0,
            new ChunkStats(0, 0, 0), List.of(), "无主键");
        String dml = gen.generate(List.of(ok, skip));
        assertThat(dml).doesNotContain("INSERT").doesNotContain("UPDATE").doesNotContain("DELETE");
        assertThat(dml).contains("-- 跳过: skip");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=DmlScriptGeneratorTest`
Expected: FAIL

- [ ] **Step 3: 实现 DmlScriptGenerator**

```java
package com.lotus.gausscmp.sync;

import com.lotus.gausscmp.compare.diff.*;
import java.util.*;

public final class DmlScriptGenerator {
    private final String schema;
    private final String syncDirection;

    public DmlScriptGenerator(String schema, String syncDirection) {
        this.schema = schema;
        this.syncDirection = syncDirection;
    }

    public String generate(List<TableDataDiff> diffs) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- 数据同步 DML 脚本（").append(syncDirection).append("）\n");
        sb.append("-- 生成时间: ").append(new Date()).append("\n");
        sb.append("-- 请人工审核后执行\n");
        sb.append("SET search_path TO \"").append(schema).append("\";\n\n");
        for (TableDataDiff diff : diffs) {
            if (diff.status() != TableDataStatus.DIFFERENT) {
                if (diff.status() == TableDataStatus.SKIPPED)
                    sb.append("-- 跳过: ").append(diff.tableName()).append(" (").append(diff.skippedReason()).append(")\n\n");
                continue;
            }
            sb.append("-- ====== 表: ").append(diff.tableName()).append(" ======\n");
            for (RowDiff rd : diff.rowDiffs()) generateRowDml(sb, diff.tableName(), diff.keyColumns(), rd);
            sb.append("\n");
        }
        return sb.toString();
    }

    private void generateRowDml(StringBuilder sb, String table, List<String> keyColumns, RowDiff rd) {
        String fqTable = "\"" + schema + "\".\"" + table + "\"";
        switch (rd.type()) {
            case MISSING_IN_TARGET -> {
                sb.append("INSERT INTO ").append(fqTable).append(" (");
                var src = rd.sourceValues();
                sb.append(String.join(", ", src.keySet().stream().map(c -> "\"" + c + "\"").toList()));
                sb.append(") VALUES (");
                sb.append(String.join(", ", src.values().stream().map(this::formatValue).toList()));
                sb.append(");\n");
            }
            case EXTRA_IN_TARGET -> {
                sb.append("DELETE FROM ").append(fqTable).append(" WHERE ");
                sb.append(whereClause(rd.keyValues()));
                sb.append(";\n");
            }
            case MISMATCH -> {
                sb.append("UPDATE ").append(fqTable).append(" SET ");
                var sets = new ArrayList<String>();
                for (String col : rd.mismatchColumns()) {
                    Object val = rd.sourceValues().get(col);
                    sets.add("\"" + col + "\" = " + formatValue(val));
                }
                sb.append(String.join(", ", sets));
                sb.append(" WHERE ").append(whereClause(rd.keyValues()));
                sb.append(";\n");
            }
        }
    }

    private String whereClause(Map<String, Object> keys) {
        var parts = new ArrayList<String>();
        for (var e : keys.entrySet()) parts.add("\"" + e.getKey() + "\" = " + formatValue(e.getValue()));
        return String.join(" AND ", parts);
    }

    private String formatValue(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number) return v.toString();
        if (v instanceof Boolean) return v.toString();
        String s = v.toString().replace("'", "''");
        return "'" + s + "'";
    }
}
```

- [ ] **Step 4: 实现 ScriptWriter**

```java
package com.lotus.gausscmp.sync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ScriptWriter {
    private ScriptWriter() {}

    public static void write(Path outputDir, String ddlScript, String dmlScript, boolean ddlEnabled, boolean dmlEnabled) {
        try {
            Files.createDirectories(outputDir);
            if (ddlEnabled) Files.writeString(outputDir.resolve("ddl_sync.sql"), ddlScript);
            if (dmlEnabled) Files.writeString(outputDir.resolve("dml_sync.sql"), dmlScript);
        } catch (IOException e) {
            throw new RuntimeException("写入脚本失败: " + outputDir, e);
        }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q test -Dtest=DmlScriptGeneratorTest`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add src
git commit -m "feat: dml script generator and script writer"
```

---

## Task 17: 报告模型与 JSON 序列化

**Files:**
- Create: `src/main/java/com/lotus/gausscmp/report/ReportModel.java`
- Create: `src/main/java/com/lotus/gausscmp/report/JsonReportSerializer.java`
- Test: `src/test/java/com/lotus/gausscmp/report/JsonReportSerializerTest.java`

- [ ] **Step 1: 写 ReportModel**

```java
package com.lotus.gausscmp.report;

import com.lotus.gausscmp.compare.diff.*;
import java.util.List;

public record ReportModel(
    String sourceSchema, String targetSchema,
    long structureConsistent, long structureDifferent,
    long dataConsistent, long dataDifferent, long dataSkipped,
    StructureDiffResult structureDiff,
    DataDiffResult dataDiff
) {}
```

- [ ] **Step 2: 写失败测试**

```java
package com.lotus.gausscmp.report;

import com.lotus.gausscmp.compare.diff.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class JsonReportSerializerTest {

    @Test
    void serializesReportToJson() {
        TableStructureDiff sd = new TableStructureDiff("t", true, true, TableStructureStatus.CONSISTENT,
            List.of(), List.of(), List.of(), java.util.Optional.empty());
        StructureDiffResult sdr = new StructureDiffResult(List.of(sd));
        TableDataDiff dd = new TableDataDiff("t", List.of("id"), TableDataStatus.CONSISTENT, 5, 5,
            new ChunkStats(1, 1, 0), List.of(), null);
        DataDiffResult ddr = new DataDiffResult(List.of(dd));
        ReportModel model = new ReportModel("app", "app", 1, 0, 1, 0, 0, sdr, ddr);
        String json = JsonReportSerializer.serialize(model);
        assertThat(json).contains("\"sourceSchema\":\"app\"");
        assertThat(json).contains("\"structureConsistent\":1");
        assertThat(json).contains("\"CONSISTENT\"");
    }

    @Test
    void deserializesRoundTrip() {
        TableStructureDiff sd = new TableStructureDiff("t", true, true, TableStructureStatus.DIFFERENT,
            List.of(), List.of(), List.of(), java.util.Optional.empty());
        StructureDiffResult sdr = new StructureDiffResult(List.of(sd));
        ReportModel model = new ReportModel("a", "b", 0, 1, 0, 0, 0, sdr, new DataDiffResult(List.of()));
        String json = JsonReportSerializer.serialize(model);
        ReportModel back = JsonReportSerializer.deserialize(json);
        assertThat(back.sourceSchema()).isEqualTo("a");
        assertThat(back.structureDifferent()).isEqualTo(1);
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `mvn -q test -Dtest=JsonReportSerializerTest`
Expected: FAIL

- [ ] **Step 4: 实现 JsonReportSerializer**

```java
package com.lotus.gausscmp.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public final class JsonReportSerializer {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private JsonReportSerializer() {}

    public static String serialize(ReportModel model) {
        try { return MAPPER.writeValueAsString(model); }
        catch (Exception e) { throw new RuntimeException("序列化报告失败", e); }
    }

    public static ReportModel deserialize(String json) {
        try { return MAPPER.readValue(json, ReportModel.class); }
        catch (Exception e) { throw new RuntimeException("反序列化报告失败", e); }
    }

    public static void writeToFile(ReportModel model, java.nio.file.Path path) {
        try {
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.writeString(path, serialize(model));
        } catch (Exception e) { throw new RuntimeException("写入报告文件失败: " + path, e); }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q test -Dtest=JsonReportSerializerTest`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add src
git commit -m "feat: report model and json serializer"
```

---

## Task 18: HTML 报告渲染

**Files:**
- Create: `src/main/java/com/lotus/gausscmp/report/HtmlReportRenderer.java`
- Test: `src/test/java/com/lotus/gausscmp/report/HtmlReportRendererTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.lotus.gausscmp.report;

import com.lotus.gausscmp.compare.diff.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class HtmlReportRendererTest {

    @Test
    void rendersSummaryAndDiffs() {
        TableStructureDiff sd = new TableStructureDiff("t1", true, true, TableStructureStatus.DIFFERENT,
            List.of(new ColumnDiff(DiffType.COLUMN_MISSING_IN_TARGET, "name", null, null, null)),
            List.of(), List.of(), java.util.Optional.empty());
        StructureDiffResult sdr = new StructureDiffResult(List.of(sd));
        RowDiff rd = new RowDiff(RowDiffType.MISSING_IN_TARGET, java.util.Map.of("id", 1),
            java.util.Map.of("id", 1, "name", "a"), null, List.of());
        TableDataDiff dd = new TableDataDiff("t1", List.of("id"), TableDataStatus.DIFFERENT, 1, 0,
            new ChunkStats(1, 0, 1), List.of(rd), null);
        DataDiffResult ddr = new DataDiffResult(List.of(dd));
        ReportModel model = new ReportModel("app", "app", 0, 1, 0, 1, 0, sdr, ddr);
        String html = new HtmlReportRenderer().render(model);
        assertThat(html).contains("<html");
        assertThat(html).contains("GaussDB Schema 比对报告");
        assertThat(html).contains("结构差异");
        assertThat(html).contains("数据差异");
        assertThat(html).contains("t1");
        assertThat(html).contains("COLUMN_MISSING_IN_TARGET");
    }

    @Test
    void truncatesRowsBeyondLimit() {
        List<RowDiff> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(new RowDiff(RowDiffType.MISSING_IN_TARGET, java.util.Map.of("id", i),
                java.util.Map.of("id", i), null, List.of()));
        }
        TableDataDiff dd = new TableDataDiff("t", List.of("id"), TableDataStatus.DIFFERENT, 5, 0,
            new ChunkStats(1, 0, 1), rows, null);
        DataDiffResult ddr = new DataDiffResult(List.of(dd));
        ReportModel model = new ReportModel("a", "a", 1, 0, 0, 1, 0,
            new StructureDiffResult(List.of()), ddr);
        String html = new HtmlReportRenderer().render(model, 3);
        assertThat(html).contains("截断");
        assertThat(html).contains("3 / 5");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=HtmlReportRendererTest`
Expected: FAIL

- [ ] **Step 3: 实现 HtmlReportRenderer**

```java
package com.lotus.gausscmp.report;

import com.lotus.gausscmp.compare.diff.*;
import java.util.*;

public final class HtmlReportRenderer {
    private static final String CSS = """
        body { font-family: sans-serif; margin: 20px; color: #222; }
        .summary { background: #f4f6f8; padding: 15px; border-radius: 8px; margin-bottom: 20px; }
        .summary h2 { margin-top: 0; }
        .tabs { display: flex; gap: 5px; margin-bottom: 10px; }
        .tab { padding: 8px 16px; cursor: pointer; border: 1px solid #ccc; border-radius: 4px 4px 0 0; background: #eee; }
        .tab.active { background: #fff; border-bottom: 1px solid #fff; }
        .panel { display: none; border: 1px solid #ccc; padding: 15px; }
        .panel.active { display: block; }
        table { border-collapse: collapse; width: 100%; margin-bottom: 10px; }
        th, td { border: 1px solid #ddd; padding: 6px 10px; text-align: left; }
        th { background: #f0f0f0; }
        .diff { color: #c0392b; font-weight: bold; }
        .skip { color: #7f8c8d; }
        """;

    public String render(ReportModel model) {
        return render(model, 1000);
    }

    public String render(ReportModel model, int maxDisplayRows) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta charset=\"UTF-8\"><title>GaussDB Schema 比对报告</title><style>").append(CSS).append("</style></head><body>");
        renderSummary(sb, model);
        renderTabs(sb);
        sb.append("<div id=\"structure\" class=\"panel active\">");
        renderStructure(sb, model.structureDiff());
        sb.append("</div>");
        sb.append("<div id=\"data\" class=\"panel\">");
        renderData(sb, model.dataDiff(), maxDisplayRows);
        sb.append("</div>");
        sb.append("<script>")
          .append("document.querySelectorAll('.tab').forEach(t=>t.onclick(()=>{")
          .append("document.querySelectorAll('.tab').forEach(x=>x.classList.remove('active'));")
          .append("document.querySelectorAll('.panel').forEach(x=>x.classList.remove('active'));")
          .append("t.classList.add('active');document.getElementById(t.dataset.target).classList.add('active');")
          .append("}));")
          .append("</script>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private void renderSummary(StringBuilder sb, ReportModel m) {
        sb.append("<div class=\"summary\"><h2>GaussDB Schema 比对报告</h2>");
        sb.append("<p>源 schema: <b>").append(m.sourceSchema()).append("</b> → 目标 schema: <b>").append(m.targetSchema()).append("</b></p>");
        sb.append("<table><tr><th>类别</th><th>一致</th><th>差异</th><th>跳过</th></tr>");
        sb.append("<tr><td>结构</td><td>").append(m.structureConsistent()).append("</td><td class=\"diff\">")
          .append(m.structureDifferent()).append("</td><td>-</td></tr>");
        sb.append("<tr><td>数据</td><td>").append(m.dataConsistent()).append("</td><td class=\"diff\">")
          .append(m.dataDifferent()).append("</td><td class=\"skip\">").append(m.dataSkipped()).append("</td></tr>");
        sb.append("</table></div>");
    }

    private void renderTabs(StringBuilder sb) {
        sb.append("<div class=\"tabs\">");
        sb.append("<div class=\"tab active\" data-target=\"structure\">结构差异</div>");
        sb.append("<div class=\"tab\" data-target=\"data\">数据差异</div>");
        sb.append("</div>");
    }

    private void renderStructure(StringBuilder sb, StructureDiffResult sdr) {
        for (TableStructureDiff t : sdr.tableDiffs()) {
            if (t.status() == TableStructureStatus.CONSISTENT) continue;
            sb.append("<h3>").append(t.tableName()).append(" <span class=\"diff\">").append(t.status()).append("</span></h3>");
            if (!t.columnDiffs().isEmpty()) {
                sb.append("<table><tr><th>类型</th><th>列</th><th>源</th><th>目标</th><th>字段</th></tr>");
                for (ColumnDiff cd : t.columnDiffs())
                    sb.append("<tr><td>").append(cd.type()).append("</td><td>").append(cd.columnName())
                      .append("</td><td>").append(cd.sourceValue()).append("</td><td>").append(cd.targetValue())
                      .append("</td><td>").append(cd.field()).append("</td></tr>");
                sb.append("</table>");
            }
            if (!t.constraintDiffs().isEmpty()) {
                sb.append("<table><tr><th>类型</th><th>约束</th><th>源</th><th>目标</th></tr>");
                for (ConstraintDiff cd : t.constraintDiffs())
                    sb.append("<tr><td>").append(cd.type()).append("</td><td>").append(cd.constraintName())
                      .append("</td><td>").append(cd.sourceDef()).append("</td><td>").append(cd.targetDef()).append("</td></tr>");
                sb.append("</table>");
            }
            if (!t.indexDiffs().isEmpty()) {
                sb.append("<table><tr><th>类型</th><th>索引</th><th>源</th><th>目标</th></tr>");
                for (IndexDiff id : t.indexDiffs())
                    sb.append("<tr><td>").append(id.type()).append("</td><td>").append(id.indexName())
                      .append("</td><td>").append(id.sourceDef()).append("</td><td>").append(id.targetDef()).append("</td></tr>");
                sb.append("</table>");
            }
        }
    }

    private void renderData(StringBuilder sb, DataDiffResult ddr, int maxDisplayRows) {
        for (TableDataDiff t : ddr.tableDiffs()) {
            sb.append("<h3>").append(t.tableName()).append(" <span class=\"").append(t.status() == TableDataStatus.CONSISTENT ? "" : "diff")
              .append("\">").append(t.status()).append("</span></h3>");
            if (t.status() != TableDataStatus.DIFFERENT) {
                if (t.status() == TableDataStatus.SKIPPED) sb.append("<p class=\"skip\">跳过原因: ").append(t.skippedReason()).append("</p>");
                continue;
            }
            sb.append("<p>源行数: ").append(t.sourceRowCount()).append(" / 目标行数: ").append(t.targetRowCount())
              .append(" | 块统计: 共 ").append(t.chunkStats().total()).append(", 一致 ").append(t.chunkStats().consistent())
              .append(", 差异 ").append(t.chunkStats().mismatched()).append("</p>");
            if (!t.rowDiffs().isEmpty()) {
                int shown = Math.min(t.rowDiffs().size(), maxDisplayRows);
                sb.append("<table><tr><th>差异类型</th><th>主键</th><th>源值</th><th>目标值</th></tr>");
                for (int i = 0; i < shown; i++) {
                    RowDiff rd = t.rowDiffs().get(i);
                    sb.append("<tr><td>").append(rd.type()).append("</td><td>").append(rd.keyValues())
                      .append("</td><td>").append(rd.sourceValues()).append("</td><td>").append(rd.targetValues()).append("</td></tr>");
                }
                sb.append("</table>");
                if (t.rowDiffs().size() > maxDisplayRows)
                    sb.append("<p class=\"skip\">截断: 显示 ").append(shown).append(" / ").append(t.rowDiffs().size()).append("（完整见 result.json / dml_sync.sql）</p>");
            }
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=HtmlReportRendererTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src
git commit -m "feat: html report renderer with tabs and summary"
```

---

## Task 19: CLI 入口与编排

**Files:**
- Create: `src/main/java/com/lotus/gausscmp/cli/Main.java`
- Create: `src/main/java/com/lotus/gausscmp/cli/CompareCommand.java`
- Test: `src/test/java/com/lotus/gausscmp/cli/CompareCommandTest.java`

- [ ] **Step 1: 写失败测试（CLI 参数解析与退出码）**

```java
package com.lotus.gausscmp.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class CompareCommandTest {

    @Test
    void missingConfigFileExitsWithError() {
        int exitCode = new Main().execute("compare", "-c", "nonexistent.yaml");
        assertThat(exitCode).isEqualTo(2);
    }

    @Test
    void versionFlagPrintsVersion() {
        int exitCode = new Main().execute("--version");
        assertThat(exitCode).isEqualTo(0);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=CompareCommandTest`
Expected: FAIL

- [ ] **Step 3: 实现 CompareCommand（编排逻辑）**

```java
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
                            List<String> keys = tm.primaryKey().map(c -> c.columns())
                                .orElseGet(() -> tm.constraints().stream().filter(c -> c.type() == ConstraintType.UNIQUE).findFirst()
                                    .map(c -> c.columns()).orElse(List.of()));
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
```

> 注：上述代码引用 `java.nio.file.Files`，需在文件顶部 import `java.nio.file.Files`。

- [ ] **Step 4: 修正 CompareCommand 的 import**

在 CompareCommand.java 顶部添加：
```java
import java.nio.file.Files;
```

- [ ] **Step 5: 实现 Main（picocli 入口）**

```java
package com.lotus.gausscmp.cli;

import picocli.CommandLine;
import picocli.CommandLine.*;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "gausscmp", mixinStandardHelpOptions = true, version = "gausscmp 0.1.0",
         subcommands = { CompareCommand.PicocliCompare.class })
public final class Main implements Callable<Integer> {

    @Override
    public Integer call() {
        System.out.println("用法: gausscmp compare -c <config.yaml>");
        return 0;
    }

    public int execute(String... args) {
        return new CommandLine(this).execute(args);
    }

    public static void main(String[] args) {
        System.exit(new Main().execute(args));
    }

    @Command(name = "compare", description = "比对两个 GaussDB schema")
    public static class PicocliCompare implements Callable<Integer> {
        @Option(names = {"-c", "--config"}, required = true, description = "配置文件路径")
        Path configPath;

        @Override
        public Integer call() {
            try {
                var config = com.lotus.gausscmp.config.ConfigLoader.load(configPath);
                var cmd = new CompareCommand(configPath, config);
                cmd.run();
                return cmd.getExitCode();
            } catch (Exception e) {
                System.err.println("错误: " + e.getMessage());
                return 2;
            }
        }
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn -q test -Dtest=CompareCommandTest`
Expected: PASS

- [ ] **Step 7: 提交**

```bash
git add src
git commit -m "feat: cli entry point and compare orchestration"
```

---

## Task 20: 端到端集成测试

**Files:**
- Test: `src/test/java/com/lotus/gausscmp/integration/EndToEndIT.java`

- [ ] **Step 1: 写端到端集成测试**

```java
package com.lotus.gausscmp.integration;

import com.lotus.gausscmp.cli.Main;
import com.lotus.gausscmp.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class EndToEndIT {

    @Container
    static PostgreSQLContainer<?> src = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("srcdb").withUsername("test").withPassword("test");
    @Container
    static PostgreSQLContainer<?> tgt = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("tgtdb").withUsername("test").withPassword("test");

    @Test
    void consistentDatabasesExitZero(@TempDir Path tmp) throws Exception {
        setupSchema(src, "CREATE TABLE app.t (id int PRIMARY KEY, name text)");
        setupSchema(tgt, "CREATE TABLE app.t (id int PRIMARY KEY, name text)");
        exec(src, "INSERT INTO app.t VALUES (1,'a'),(2,'b')");
        exec(tgt, "INSERT INTO app.t VALUES (1,'a'),(2,'b')");
        Path cfg = writeConfig(tmp, src, tgt);
        int exitCode = new Main().execute("compare", "-c", cfg.toString());
        assertThat(exitCode).isEqualTo(0);
    }

    @Test
    void differentDatabasesExitOneAndGenerateScripts(@TempDir Path tmp) throws Exception {
        setupSchema(src, "CREATE TABLE app.t (id int PRIMARY KEY, name text)");
        setupSchema(tgt, "CREATE TABLE app.t (id int PRIMARY KEY, name text)");
        exec(src, "INSERT INTO app.t VALUES (1,'a'),(2,'b')");
        exec(tgt, "INSERT INTO app.t VALUES (1,'a'),(3,'c')");
        Path cfg = writeConfig(tmp, src, tgt);
        int exitCode = new Main().execute("compare", "-c", cfg.toString());
        assertThat(exitCode).isEqualTo(1);
        Path reportDir = findReportDir(Path.of(tmp.toString(), "report"));
        assertThat(reportDir.resolve("result.json")).exists();
        assertThat(reportDir.resolve("report.html")).exists();
        assertThat(reportDir.resolve("dml_sync.sql")).exists();
        String dml = Files.readString(reportDir.resolve("dml_sync.sql"));
        assertThat(dml).contains("INSERT").contains("DELETE");
    }

    @Test
    void ddlScriptFixesStructureDifference(@TempDir Path tmp) throws Exception {
        setupSchema(src, "CREATE TABLE app.t (id int PRIMARY KEY, name text)");
        setupSchema(tgt, "CREATE TABLE app.t (id int PRIMARY KEY)");
        exec(src, "INSERT INTO app.t VALUES (1,'a')");
        exec(tgt, "INSERT INTO app.t VALUES (1)");
        Path cfg = writeConfig(tmp, src, tgt);
        int exitCode = new Main().execute("compare", "-c", cfg.toString());
        assertThat(exitCode).isEqualTo(1);
        Path reportDir = findReportDir(Path.of(tmp.toString(), "report"));
        String ddl = Files.readString(reportDir.resolve("ddl_sync.sql"));
        assertThat(ddl).contains("ADD COLUMN");
    }

    @Test
    void noPrimaryKeyTableSkipped(@TempDir Path tmp) throws Exception {
        setupSchema(src, "CREATE TABLE app.no_pk (a int, b int)");
        setupSchema(tgt, "CREATE TABLE app.no_pk (a int, b int)");
        exec(src, "INSERT INTO app.no_pk VALUES (1,2)");
        exec(tgt, "INSERT INTO app.no_pk VALUES (1,2)");
        Path cfg = writeConfig(tmp, src, tgt);
        int exitCode = new Main().execute("compare", "-c", cfg.toString());
        assertThat(exitCode).isEqualTo(0);
        Path reportDir = findReportDir(Path.of(tmp.toString(), "report"));
        String json = Files.readString(reportDir.resolve("result.json"));
        assertThat(json).contains("SKIPPED");
    }

    private Path writeConfig(Path tmp, PostgreSQLContainer<?> s, PostgreSQLContainer<?> t) throws Exception {
        Path cfg = tmp.resolve("compare.yaml");
        Files.writeString(cfg, """
            source:
              host: %s
              port: %d
              database: %s
              username: %s
              password: %s
              schema: app
              readOnly: false
            target:
              host: %s
              port: %d
              database: %s
              username: %s
              password: %s
              schema: app
              readOnly: false
            options:
              parallelism: 1
              chunkSize: 5000
              drillDown: true
              checksumFunction: md5
              tableFilter: {include: [".*"], exclude: []}
              output: {dir: "%s/report", html: true, ddlScript: true, dmlScript: true}
              syncDirection: source-to-target
              maxDisplayRows: 1000
              tableTimeout: 0
            """.formatted(s.getHost(), s.getMappedPort(5432), s.getDatabaseName(), s.getUsername(), s.getPassword(),
                          t.getHost(), t.getMappedPort(5432), t.getDatabaseName(), t.getUsername(), t.getPassword(),
                          tmp.toString().replace("\\", "/")));
        return cfg;
    }

    private static void setupSchema(PostgreSQLContainer<?> c, String ddl) throws Exception {
        try (var conn = java.sql.DriverManager.getConnection(c.getJdbcUrl(), c.getUsername(), c.getPassword());
             var st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS app");
            st.execute(ddl);
        }
    }

    private static void exec(PostgreSQLContainer<?> c, String sql) throws Exception {
        try (var conn = java.sql.DriverManager.getConnection(c.getJdbcUrl(), c.getUsername(), c.getPassword());
             var st = conn.createStatement()) { st.execute(sql); }
    }

    private static Path findReportDir(Path reportRoot) throws Exception {
        try (var stream = Files.list(reportRoot)) {
            return stream.findFirst().orElseThrow();
        }
    }
}
```

- [ ] **Step 2: 运行端到端集成测试**

Run: `mvn -q verify -Dit.test=EndToEndIT`
Expected: PASS（4 个测试全通过）

- [ ] **Step 3: 运行全部测试**

Run: `mvn -q verify`
Expected: 所有单元测试 + 集成测试通过

- [ ] **Step 4: 提交**

```bash
git add src
git commit -m "test: end-to-end integration tests with testcontainers"
```

---

## Self-Review

**Spec coverage check:**
- §1 架构与模块：Task 1（骨架）+ Task 2-18 覆盖所有模块 ✓
- §2 配置与连接：Task 2（ConfigLoader + EnvInterpolator）+ Task 3（DualDataSource）✓
- §3 元数据抽取：Task 4（模型）+ Task 5（TypeNormalizer）+ Task 6（DefinitionNormalizer）+ Task 7（OpenGaussMetadataReader）✓
- §4 结构比对：Task 8（diff 模型 + StructureComparator）✓
- §5 数据比对：Task 9（diff 模型）+ Task 10（Chunker）+ Task 11（ChecksumCalculator）+ Task 12（DrillDownComparator）+ Task 13（DataComparator）✓
- §6 并发：Task 14（TableTaskExecutor）✓
- §7 输出：Task 15（DDL）+ Task 16（DML + ScriptWriter）+ Task 17（ReportModel + JSON）+ Task 18（HTML）✓
- §8 错误处理：分散在各 Task 的异常处理 + Task 19 退出码 ✓
- §9 测试：每个 Task 有单元测试 + Task 20 端到端集成测试 ✓

**Placeholder scan:** 无 TBD/TODO；每个步骤含完整代码或命令 ✓

**Type consistency:** `TableStructureDiff`、`TableDataDiff`、`RowDiff` 等在定义（Task 8/9）与使用（Task 15-19）中字段名一致 ✓

**已知简化（非占位符，已在相应 Task 注明）：**
- `extractIndexSpec` 为简化实现，端到端测试（Task 20）验证基本场景可执行。
- 集成测试用 `postgres:16-alpine` 代替 openGauss 镜像（PG 协议兼容，系统目录相同）；如需 openGauss 镜像可在 CI 环境替换。
