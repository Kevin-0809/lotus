package com.lotus.gausscmp.config;

public record SourceConfig(String url, String username, String password,
                           String schema, boolean readOnly) {}
