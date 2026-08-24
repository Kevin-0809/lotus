package com.lotus.gausscmp.web;

import com.lotus.gausscmp.web.service.ProgressTracker;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/progress")
@CrossOrigin
public class ProgressController {

    private final ProgressTracker tracker;

    public ProgressController(ProgressTracker tracker) {
        this.tracker = tracker;
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        ProgressTracker.Progress p = tracker.get(id);
        if (p == null) throw new NoSuchElementException("进度不存在或已过期: " + id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase", p.phase());
        m.put("total", p.total());
        m.put("completed", p.completed());
        m.put("percent", p.percent());
        m.put("startedAt", p.startedAtMs());
        m.put("done", p.done());
        if (p.error() != null) m.put("error", p.error());
        return m;
    }
}
