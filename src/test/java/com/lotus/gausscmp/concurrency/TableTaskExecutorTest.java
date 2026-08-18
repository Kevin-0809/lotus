package com.lotus.gausscmp.concurrency;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class TableTaskExecutorTest {

    @Test
    void runsTasksAndAggregatesResultsInOrder() throws Exception {
        List<String> tables = List.of("c", "a", "b");
        AtomicInteger counter = new AtomicInteger(0);
        try (var exec = new TableTaskExecutor<String>(2)) {
            Map<String, String> results = exec.execute(tables, table -> {
                try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                counter.incrementAndGet();
                return table.toUpperCase();
            });
            assertThat(results).containsKeys("a", "b", "c");
            assertThat(results.get("a")).isEqualTo("A");
            assertThat(counter.get()).isEqualTo(3);
        }
    }

    @Test
    void handlesExceptionPerTable() throws Exception {
        List<String> tables = List.of("ok", "bad");
        try (var exec = new TableTaskExecutor<String>(1)) {
            Map<String, String> results = exec.execute(tables, table -> {
                if (table.equals("bad")) throw new RuntimeException("boom");
                return table;
            });
            assertThat(results.get("ok")).isEqualTo("ok");
            assertThat(results.get("bad")).isNull();
        }
    }
}
