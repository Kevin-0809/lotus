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
