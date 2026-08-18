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
            CompareConfig cfg = yaml.treeToValue(interpolateNode(tree, yaml), CompareConfig.class);
            validate(cfg);
            return cfg;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("加载配置失败: " + e.getMessage(), e);
        }
    }

    private static JsonNode interpolateNode(JsonNode node, ObjectMapper yaml) {
        if (node.isTextual()) {
            return yaml.getNodeFactory().textNode(
                EnvInterpolator.interpolate(node.asText()));
        }
        if (node.isObject()) {
            var obj = yaml.createObjectNode();
            node.fields().forEachRemaining(e -> obj.set(e.getKey(), interpolateNode(e.getValue(), yaml)));
            return obj;
        }
        if (node.isArray()) {
            var arr = yaml.createArrayNode();
            node.forEach(n -> arr.add(interpolateNode(n, yaml)));
            return arr;
        }
        return node;
    }

    private static void validate(CompareConfig c) {
        if (c.source() == null) throw new IllegalStateException("配置缺失: source");
        if (c.target() == null) throw new IllegalStateException("配置缺失: target");
        if (c.options() == null) throw new IllegalStateException("配置缺失: options");
        if (c.options().tableFilter() == null) throw new IllegalStateException("配置缺失: options.tableFilter");
        if (c.options().output() == null) throw new IllegalStateException("配置缺失: options.output");
        require(c.source().host(), "source.host");
        require(c.source().database(), "source.database");
        require(c.source().username(), "source.username");
        require(c.source().schema(), "source.schema");
        require(c.target().host(), "target.host");
        require(c.target().database(), "target.database");
        require(c.target().username(), "target.username");
        require(c.target().schema(), "target.schema");
        require(c.options().checksumFunction(), "options.checksumFunction");
        require(c.options().syncDirection(), "options.syncDirection");
        require(c.options().output().dir(), "options.output.dir");
        if (c.source().port() <= 0) throw new IllegalStateException("配置字段非法: source.port (需 > 0)");
        if (c.target().port() <= 0) throw new IllegalStateException("配置字段非法: target.port (需 > 0)");
        if (c.options().parallelism() < 1) throw new IllegalStateException("配置字段非法: options.parallelism (需 >= 1)");
        if (c.options().chunkSize() <= 0) throw new IllegalStateException("配置字段非法: options.chunkSize (需 > 0)");
        if (c.options().maxDisplayRows() <= 0) throw new IllegalStateException("配置字段非法: options.maxDisplayRows (需 > 0)");
        if (c.options().tableTimeout() < 0) throw new IllegalStateException("配置字段非法: options.tableTimeout (需 >= 0)");
    }

    private static void require(String val, String field) {
        if (val == null || val.isBlank()) {
            throw new IllegalStateException("配置字段缺失: " + field);
        }
    }
}
