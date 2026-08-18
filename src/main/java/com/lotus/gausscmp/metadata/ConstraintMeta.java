package com.lotus.gausscmp.metadata;

import java.util.List;

public record ConstraintMeta(String name, ConstraintType type, String definition,
                             List<String> columns, String referencesTable) {}
