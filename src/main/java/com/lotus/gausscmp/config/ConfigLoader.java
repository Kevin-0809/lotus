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
