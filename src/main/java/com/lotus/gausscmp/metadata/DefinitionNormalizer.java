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
