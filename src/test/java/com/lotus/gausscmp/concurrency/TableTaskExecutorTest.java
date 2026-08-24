package com.lotus.gausscmp.concurrency;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class TableTaskExecutorTest {

    @Test
    void honorsConfiguredParallelism() {
        try (var exec = new TableTaskExecutor<String>(16)) {
            assertThat(exec.parallelism()).isEqualTo(16);
        }
    }

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

    @Test
    void stressTestAggregatesAllResultsWithoutLoss() throws Exception {
        int taskCount = 600;
        List<String> tables = new ArrayList<>();
        for (int i = 0; i < taskCount; i++) tables.add("t" + i);
        try (var exec = new TableTaskExecutor<String>(8)) {
            Map<String, String> results = exec.execute(tables, table -> table + "-ok");
            assertThat(results).hasSize(taskCount);
            for (int i = 0; i < taskCount; i++) {
                assertThat(results.get("t" + i)).isEqualTo("t" + i + "-ok");
            }
        }
    }

    @Test
    void neverExceedsConfiguredParallelism() throws Exception {
        int parallelism = 3;
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        List<String> tables = new ArrayList<>();
        for (int i = 0; i < 30; i++) tables.add("t" + i);
        try (var exec = new TableTaskExecutor<Void>(parallelism)) {
            exec.execute(tables, table -> {
                int now = running.incrementAndGet();
                maxConcurrent.accumulateAndGet(now, Math::max);
                try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                running.decrementAndGet();
                completed.incrementAndGet();
                return null;
            });
            assertThat(maxConcurrent.get()).isLessThanOrEqualTo(parallelism);
            assertThat(maxConcurrent.get()).isGreaterThanOrEqualTo(2);
            assertThat(completed.get()).isEqualTo(tables.size());
        }
    }

    @Test
    void errorInTaskYieldsNullResultInsteadOfPropagating() throws Exception {
        List<String> tables = List.of("bad", "ok");
        try (var exec = new TableTaskExecutor<String>(1)) {
            Map<String, String> results = exec.execute(tables, table -> {
                if (table.equals("bad")) throw new AssertionError("fatal");
                return table;
            });
            assertThat(results.get("bad")).isNull();
            assertThat(results.get("ok")).isEqualTo("ok");
        }
    }

    @Test
    void failedTaskDoesNotAffectSiblingTasks() throws Exception {
        List<String> tables = new ArrayList<>();
        tables.add("bad");
        for (int i = 0; i < 20; i++) tables.add("ok" + i);
        try (var exec = new TableTaskExecutor<String>(4)) {
            Map<String, String> results = exec.execute(tables, table -> {
                if (table.equals("bad")) throw new RuntimeException("boom");
                return table.toUpperCase();
            });
            assertThat(results.get("bad")).isNull();
            for (int i = 0; i < 20; i++) {
                assertThat(results.get("ok" + i)).isEqualTo("OK" + i);
            }
        }
    }

    @Test
    void timedOutTaskYieldsNullAndDoesNotBlockOthers() throws Exception {
        List<String> tables = List.of("slow", "fast");
        try (var exec = new TableTaskExecutor<String>(2)) {
            long started = System.currentTimeMillis();
            Map<String, String> results = exec.execute(tables, table -> {
                if (table.equals("slow")) {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                return table;
            }, 1);
            long elapsed = System.currentTimeMillis() - started;
            assertThat(results.get("fast")).isEqualTo("fast");
            assertThat(results.get("slow")).isNull();
            assertThat(elapsed).isLessThan(4000);
        }
    }

    @Test
    void zeroTimeoutMeansUnlimited() throws Exception {
        List<String> tables = List.of("a");
        try (var exec = new TableTaskExecutor<String>(1)) {
            Map<String, String> results = exec.execute(tables, table -> table, 0);
            assertThat(results.get("a")).isEqualTo("a");
        }
    }

    @Test
    void sameExecutorSupportsConcurrentExecuteCalls() throws Exception {
        try (var exec = new TableTaskExecutor<Integer>(4)) {
            int callers = 4;
            int tasksPerCall = 25;
            ExecutorService callerPool = Executors.newFixedThreadPool(callers);
            try {
                List<Future<Map<String, Integer>>> futures = new ArrayList<>();
                for (int c = 0; c < callers; c++) {
                    final int callerId = c;
                    List<String> tables = new ArrayList<>();
                    for (int i = 0; i < tasksPerCall; i++) tables.add("c" + callerId + "-t" + i);
                    futures.add(callerPool.submit(() -> exec.execute(tables, table -> {
                        int v = Integer.parseInt(table.substring(table.lastIndexOf('t') + 1));
                        return callerId * 100 + v;
                    })));
                }
                for (int c = 0; c < callers; c++) {
                    Map<String, Integer> results = futures.get(c).get(30, TimeUnit.SECONDS);
                    assertThat(results).hasSize(tasksPerCall);
                    for (int i = 0; i < tasksPerCall; i++) {
                        assertThat(results.get("c" + c + "-t" + i)).isEqualTo(c * 100 + i);
                    }
                }
            } finally {
                callerPool.shutdownNow();
            }
        }
    }

    @Test
    void closeIsIdempotent() {
        var exec = new TableTaskExecutor<String>(2);
        exec.close();
        exec.close();
    }

    @Test
    void negativeParallelismFallsBackToSingleThread() throws Exception {
        List<String> tables = List.of("a", "b");
        try (var exec = new TableTaskExecutor<String>(-5)) {
            assertThat(exec.parallelism()).isEqualTo(1);
            Map<String, String> results = exec.execute(tables, table -> table);
            assertThat(results).containsOnlyKeys("a", "b");
        }
    }
}
