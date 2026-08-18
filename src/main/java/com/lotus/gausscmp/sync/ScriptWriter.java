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
