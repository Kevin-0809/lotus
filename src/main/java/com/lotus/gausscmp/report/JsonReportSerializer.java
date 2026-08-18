package com.lotus.gausscmp.report;

import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

public final class JsonReportSerializer {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .registerModule(new Jdk8Module());

    static {
        MAPPER.setDefaultPrettyPrinter(new DefaultPrettyPrinter().withoutSpacesInObjectEntries());
    }

    private JsonReportSerializer() {}

    public static String serialize(ReportModel model) {
        try {
            return MAPPER.writeValueAsString(model);
        } catch (Exception e) {
            throw new RuntimeException("序列化报告失败", e);
        }
    }

    public static ReportModel deserialize(String json) {
        try {
            return MAPPER.readValue(json, ReportModel.class);
        } catch (Exception e) {
            throw new RuntimeException("反序列化报告失败", e);
        }
    }

    public static void writeToFile(ReportModel model, java.nio.file.Path path) {
        try {
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.writeString(path, serialize(model));
        } catch (Exception e) {
            throw new RuntimeException("写入报告文件失败: " + path, e);
        }
    }
}
