package com.lotus.gausscmp.config;

public record SourceConfig(String host, int port, String database,
                           String username, String password, String schema, boolean readOnly) {}
