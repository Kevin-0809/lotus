package com.lotus.gausscmp.config;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EnvInterpolator {
    private static final Pattern PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private EnvInterpolator() {}

    public static String interpolate(String input) {
        if (input == null) return null;
        Matcher m = PATTERN.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String expr = m.group(1);
            String value = resolveExpr(expr);
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String resolveExpr(String expr) {
        Map<String, String> env = System.getenv();
        if (expr.contains(":-")) {
            int idx = expr.indexOf(":-");
            String name = expr.substring(0, idx).trim();
            String def = expr.substring(idx + 2);
            return env.getOrDefault(name, def);
        }
        String name = expr.trim();
        String val = env.get(name);
        if (val == null) throw new IllegalStateException("环境变量未设置: " + name);
        return val;
    }
}
