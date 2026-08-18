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
              url: jdbc:postgresql://10.0.0.1:5432/prod
              username: ro
              password: ${GAUSSCMP_DEFINITELY_MISSING:-secret}
              schema: app
              readOnly: true
            target:
              url: jdbc:postgresql://10.0.0.2:5432/prod
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
              dataCompareTables: ["t1", "t2"]
            """);
        CompareConfig config = ConfigLoader.load(cfg);
        assertThat(config.source().url()).isEqualTo("jdbc:postgresql://10.0.0.1:5432/prod");
        assertThat(config.source().password()).isEqualTo("secret");
        assertThat(config.options().tableFilter().exclude()).containsExactly("^tmp_.*");
        assertThat(config.options().maxDisplayRows()).isEqualTo(1000);
        assertThat(config.options().dataCompareTables()).containsExactly("t1", "t2");
    }

    @Test
    void throwsOnMissingRequiredPassword(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("compare.yaml");
        java.nio.file.Files.writeString(cfg, """
            source:
              url: jdbc:postgresql://h:5432/d
              username: u
              password: ${GAUSSCMP_REQUIRED_BUT_MISSING}
              schema: s
              readOnly: true
            target:
              url: jdbc:postgresql://h:5432/d
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
