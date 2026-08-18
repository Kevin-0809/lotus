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
