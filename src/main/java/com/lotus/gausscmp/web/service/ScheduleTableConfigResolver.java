package com.lotus.gausscmp.web.service;

import com.lotus.gausscmp.web.entity.CompareTableConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class ScheduleTableConfigResolver {
    record Resolved(List<String> include, List<String> exclude, List<String> dataTables) {}

    private ScheduleTableConfigResolver() {}

    static Resolved resolve(List<CompareTableConfig> rows, List<String> scheduleInclude,
                            List<String> scheduleExclude) {
        List<String> parameters = rows.stream()
            .filter(CompareTableConfig::isEnabled)
            .filter(x -> x.getTableType() == CompareTableConfig.TableType.PARAMETER)
            .map(CompareTableConfig::getTableName)
            .map(Pattern::quote)
            .toList();
        List<String> exclude = new ArrayList<>(scheduleExclude == null ? List.of() : scheduleExclude);
        exclude.addAll(rows.stream()
            .filter(CompareTableConfig::isEnabled)
            .filter(x -> x.getTableType() == CompareTableConfig.TableType.EXCLUDE)
            .map(CompareTableConfig::getTableName)
            .map(Pattern::quote)
            .toList());
        List<String> include = parameters.isEmpty()
            ? (scheduleInclude == null ? List.of() : scheduleInclude) : parameters;
        return new Resolved(include, List.copyOf(exclude), parameters);
    }
}
