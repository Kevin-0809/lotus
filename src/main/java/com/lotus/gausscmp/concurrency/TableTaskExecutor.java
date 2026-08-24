package com.lotus.gausscmp.concurrency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public final class TableTaskExecutor<R> implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(TableTaskExecutor.class);
    private final ExecutorService pool;
    private final int parallelism;

    public TableTaskExecutor(int parallelism) {
        this(parallelism, "table-task");
    }

    public TableTaskExecutor(int parallelism, String threadNamePrefix) {
        this.parallelism = Math.max(1, parallelism);
        AtomicInteger seq = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(this.parallelism, runnable -> {
            Thread thread = new Thread(runnable, threadNamePrefix + "-" + seq.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        });
        LOG.debug("创建表级并发执行器: parallelism={}", this.parallelism);
    }

    public int parallelism() {
        return parallelism;
    }

    public Map<String, R> execute(List<String> tables, Function<String, R> task) throws Exception {
        return execute(tables, task, 0);
    }

    /**
     * 并发执行表级任务。
     *
     * @param timeoutSeconds 单表任务超时秒数（自本批次开始计），0 表示不限制；超时任务将被中断并记为 null
     */
    public Map<String, R> execute(List<String> tables, Function<String, R> task, long timeoutSeconds) throws Exception {
        long deadlineNanos = timeoutSeconds > 0 ? System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds) : 0;
        Map<String, CompletableFuture<R>> futures = new LinkedHashMap<>();
        for (String table : tables) {
            futures.put(table, CompletableFuture.supplyAsync(() -> {
                long started = System.nanoTime();
                try {
                    R result = task.apply(table);
                    LOG.debug("表任务完成: table={}, durationMs={}", table, elapsedMs(started));
                    return result;
                } catch (Throwable e) {
                    LOG.warn("表任务失败: table={}, durationMs={}, error={}",
                        table, elapsedMs(started), e.toString(), e);
                    return null;
                }
            }, pool));
        }
        Map<String, R> results = new TreeMap<>();
        for (Map.Entry<String, CompletableFuture<R>> e : futures.entrySet()) {
            try {
                R value;
                if (timeoutSeconds <= 0) {
                    value = e.getValue().get();
                } else {
                    long remainingNanos = deadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0) {
                        if (!e.getValue().isDone()) throw new TimeoutException();
                        value = e.getValue().get();
                    } else {
                        value = e.getValue().get(remainingNanos, TimeUnit.NANOSECONDS);
                    }
                }
                results.put(e.getKey(), value);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                LOG.warn("等待表任务被中断: table={}", e.getKey(), ex);
                cancelRemaining(futures);
                results.put(e.getKey(), null);
            } catch (ExecutionException ex) {
                LOG.warn("获取表任务结果失败: table={}", e.getKey(), ex.getCause());
                results.put(e.getKey(), null);
            } catch (TimeoutException ex) {
                LOG.warn("表任务超时: table={}, timeoutSeconds={}", e.getKey(), timeoutSeconds);
                e.getValue().cancel(true);
                results.put(e.getKey(), null);
            }
        }
        return results;
    }

    private static void cancelRemaining(Map<String, ? extends CompletableFuture<?>> futures) {
        for (CompletableFuture<?> future : futures.values()) {
            future.cancel(true);
        }
    }

    private static long elapsedMs(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    @Override
    public void close() {
        pool.shutdown();
        try { if (!pool.awaitTermination(30, TimeUnit.SECONDS)) pool.shutdownNow(); }
        catch (InterruptedException e) { pool.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
