package com.lotus.gausscmp.metadata;

import java.util.regex.Pattern;

public final class DefinitionNormalizer {

    private DefinitionNormalizer() {}

    private static final Pattern LOCAL_PARTITION_LIST = Pattern.compile(
        "local\\s*\\(.*?\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INDEX_STORAGE_OPTIONS = Pattern.compile(
        "\\s+with\\s*\\([^)]*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INDEX_TABLESPACE = Pattern.compile(
        "\\s+tablespace\\s+[^\\s,)]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern INDEX_SCOPE = Pattern.compile(
        "\\b(global|local)\\b", Pattern.CASE_INSENSITIVE);

    public static String normalize(String def) {
        if (def == null) return null;
        String s = def.trim().toLowerCase().replaceAll("\\s+", " ");
        s = stripLocalPartitionList(s);
        return s;
    }

    public static String normalize(String def, String schema) {
        if (def == null) return null;
        String s = normalize(def);
        if (schema != null && !schema.isBlank()) {
            s = s.replaceAll("(?i)" + Pattern.quote(schema.toLowerCase()) + "\\.", "");
        }
        return s;
    }

    public static String normalizeForDdl(String def) {
        if (def == null) return null;
        String s = def.trim().replaceAll("\\s+", " ");
        return stripLocalPartitionList(s);
    }

    public static String normalizeForDdl(String def, String schema) {
        if (def == null) return null;
        String s = normalizeForDdl(def);
        return s;
    }

    public static String stripLocalPartitionList(String def) {
        if (def == null) return null;
        return LOCAL_PARTITION_LIST.matcher(def).replaceAll("local").trim();
    }

    public static String normalizeIndexDefinition(String def, String schema, boolean partitioned) {
        if (def == null) return null;
        String s = normalize(def, schema);
        s = INDEX_STORAGE_OPTIONS.matcher(s).replaceAll("");
        s = INDEX_TABLESPACE.matcher(s).replaceAll("");
        if (!partitioned) s = INDEX_SCOPE.matcher(s).replaceAll(" ");
        return s.replaceAll("\\s+", " ").trim();
    }

    public static String stripSchemaPrefix(String name, String schema) {
        if (name == null) return null;
        if (schema != null && !schema.isBlank()) {
            String lower = name.toLowerCase();
            String prefix = schema.toLowerCase() + ".";
            if (lower.startsWith(prefix)) {
                return name.substring(prefix.length());
            }
        }
        return name;
    }

    public static String normalizeDefaultValue(String val) {
        if (val == null) return null;
        String s = val.trim().toLowerCase().replaceAll("\\s+", " ");
        if (s.equals("null")) return "null";
        if (s.startsWith("'") && s.endsWith("'") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.startsWith("now") || s.startsWith("current_timestamp") || s.startsWith("pg_systimestamp")) return "current_timestamp";
        return s;
    }
}
