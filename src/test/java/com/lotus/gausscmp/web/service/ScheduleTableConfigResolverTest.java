package com.lotus.gausscmp.web.service;

import com.lotus.gausscmp.web.entity.CompareTableConfig;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ScheduleTableConfigResolverTest {
    @Test
    void globalParameterAndExcludeTablesOverrideScheduleFilters() {
        var rows = List.of(
            new CompareTableConfig("a", CompareTableConfig.TableType.PARAMETER, true, ""),
            new CompareTableConfig("b", CompareTableConfig.TableType.PARAMETER, true, ""),
            new CompareTableConfig("b", CompareTableConfig.TableType.EXCLUDE, true, "")
        );

        var resolved = ScheduleTableConfigResolver.resolve(rows, List.of("legacy_include"), List.of("legacy_exclude"));

        assertThat(resolved.include()).containsExactly("\\Qa\\E", "\\Qb\\E");
        assertThat(resolved.exclude()).containsExactly("legacy_exclude", "\\Qb\\E");
        assertThat(resolved.dataTables()).containsExactly("\\Qa\\E", "\\Qb\\E");
    }

    @Test
    void keepsScheduleIncludeWhenNoGlobalParameterTablesAreEnabled() {
        var rows = List.of(new CompareTableConfig("b", CompareTableConfig.TableType.EXCLUDE, true, ""));

        var resolved = ScheduleTableConfigResolver.resolve(rows, List.of("legacy_include"), List.of());

        assertThat(resolved.include()).containsExactly("legacy_include");
        assertThat(resolved.exclude()).containsExactly("\\Qb\\E");
        assertThat(resolved.dataTables()).isEmpty();
    }
}
