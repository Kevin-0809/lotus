package com.lotus.gausscmp.metadata;

public final class DefinitionNormalizer {

    private DefinitionNormalizer() {}

    public static String normalize(String def) {
        if (def == null) return null;
        return def.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    public static String normalize(String def, String schema) {
        if (def == null) return null;
        String s = normalize(def);
        if (schema != null && !schema.isBlank()) {
            s = s.replaceAll("(?i)" + java.util.regex.Pattern.quote(schema.toLowerCase()) + "\\.", "");
        }
        return s;
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
        if (s.startsWith("now") || s.startsWith("current_timestamp")) return "current_timestamp";
        return s;
    }
}
