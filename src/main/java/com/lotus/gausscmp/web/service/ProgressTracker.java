package com.lotus.gausscmp.web.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进行中任务的进度注册表。
 * 前端发起采集/比对前生成 progressId，执行期间通过 GET /api/progress/{id} 轮询进度。
 */
@Component
public class ProgressTracker {

    public record Progress(String phase, int total, int completed,
                           long startedAtMs, Long finishedAtMs, boolean done, String error) {
        public int percent() {
            if (total <= 0) return done ? 100 : 0;
            return (int) Math.min(100, Math.round(completed * 100.0 / total));
        }
    }

    private static final long RETAIN_MS = 5 * 60 * 1000L;
    private final Map<String, Progress> progresses = new ConcurrentHashMap<>();

    public String start(String initialPhase) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        register(id, initialPhase);
        return id;
    }

    /** 用调用方预生成的 ID 注册进度（幂等：已存在则忽略） */
    public void register(String id, String initialPhase) {
        progresses.putIfAbsent(id, new Progress(initialPhase, 0, 0, System.currentTimeMillis(), null, false, null));
    }

    public void phase(String id, String phase) {
        progresses.computeIfPresent(id, (k, p) -> new Progress(phase, p.total(), p.completed(),
            p.startedAtMs(), p.finishedAtMs(), p.done(), p.error()));
    }

    public void total(String id, int total) {
        progresses.computeIfPresent(id, (k, p) -> new Progress(p.phase(), total, p.completed(),
            p.startedAtMs(), p.finishedAtMs(), p.done(), p.error()));
    }

    /** 任务计数 +1（成功与失败均计，避免进度卡死） */
    public void increment(String id) {
        progresses.computeIfPresent(id, (k, p) -> new Progress(p.phase(), p.total(),
            p.completed() + 1, p.startedAtMs(), p.finishedAtMs(), p.done(), p.error()));
    }

    public void complete(String id) {
        progresses.computeIfPresent(id, (k, p) -> new Progress(p.phase(), p.total(),
            p.total(), p.startedAtMs(), System.currentTimeMillis(), true, null));
    }

    public void fail(String id, String error) {
        progresses.computeIfPresent(id, (k, p) -> new Progress(p.phase(), p.total(),
            p.completed(), p.startedAtMs(), System.currentTimeMillis(), true, error));
    }

    public Progress get(String id) {
        Progress p = progresses.get(id);
        if (p != null && p.done() && p.finishedAtMs() != null
                && System.currentTimeMillis() - p.finishedAtMs() > RETAIN_MS) {
            progresses.remove(id);
            return null;
        }
        return p;
    }
}
