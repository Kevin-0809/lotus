package com.lotus.gausscmp.compare.data;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ChunkerTest {

    @Test
    void chunksByOffset() {
        Chunker chunker = new Chunker(3);
        List<Chunker.Chunk> chunks = chunker.plan(10);
        assertThat(chunks).hasSize(4);
        assertThat(chunks.get(0).offset()).isEqualTo(0);
        assertThat(chunks.get(0).limit()).isEqualTo(3);
        assertThat(chunks.get(3).offset()).isEqualTo(9);
        assertThat(chunks.get(3).limit()).isEqualTo(1);
    }

    @Test
    void singleChunkForEmpty() {
        Chunker chunker = new Chunker(5000);
        List<Chunker.Chunk> chunks = chunker.plan(0);
        assertThat(chunks).isEmpty();
    }

    @Test
    void exactDivision() {
        Chunker chunker = new Chunker(5);
        List<Chunker.Chunk> chunks = chunker.plan(10);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1).offset()).isEqualTo(5);
    }
}
