package com.lotus.gausscmp.config;

import java.util.List;

public record TableFilterConfig(List<String> include, List<String> exclude) {
    public TableFilterConfig {
        include = include == null ? List.of() : List.copyOf(include);
        exclude = exclude == null ? List.of() : List.copyOf(exclude);
    }
}
