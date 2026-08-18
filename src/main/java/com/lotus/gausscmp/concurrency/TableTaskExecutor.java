package com.lotus.gausscmp.concurrency;

import java.util.*;
import java.util.concurrent.*;

public final class TableTaskExecutor<R> implements AutoCloseable {
    private final ExecutorService pool;

    public TableTaskExecutor(int parallelism) {
        this.pool = Executors.newFixedThreadPool(Math.max(1, parallelism));
    }

    public Map<String, R> execute(List<String> tables, java.util.function.Function<String, R> task) throws Exception {
        Map<String, CompletableFuture<R>> futures = new LinkedHashMap<>();
        for (String table : tables) {
            futures.put(table, CompletableFuture.supplyAsync(() -> {
                try { return task.apply(table); }
                catch (Exception e) { return null; }
            }, pool));
        }
        Map<String, R> results = new TreeMap<>();
        for (Map.Entry<String, CompletableFuture<R>> e : futures.entrySet()) {
            try { results.put(e.getKey(), e.getValue().get()); }
            catch (Exception ex) { results.put(e.getKey(), null); }
        }
        return results;
    }

    @Override
    public void close() {
        pool.shutdown();
        try { if (!pool.awaitTermination(30, TimeUnit.SECONDS)) pool.shutdownNow(); }
        catch (InterruptedException e) { pool.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
