package com.lotus.gausscmp.compare.data;

import java.util.ArrayList;
import java.util.List;

public final class Chunker {
    public record Chunk(int offset, int limit) {}

    private final int chunkSize;

    public Chunker(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public List<Chunk> plan(long totalRows) {
        List<Chunk> chunks = new ArrayList<>();
        if (totalRows <= 0) return chunks;
        long remaining = totalRows;
        int offset = 0;
        while (remaining > 0) {
            int limit = (int) Math.min(chunkSize, remaining);
            chunks.add(new Chunk(offset, limit));
            offset += limit;
            remaining -= limit;
        }
        return chunks;
    }
}
