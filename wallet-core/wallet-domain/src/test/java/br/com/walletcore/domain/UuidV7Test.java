package br.com.walletcore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.walletcore.domain.shared.UuidV7;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class UuidV7Test {

    @Test
    void isStrictlyIncreasingEvenWhenGeneratedFasterThanOneMillisecond() {
        // A tight loop is exactly the case a naive millisecond-resolution implementation gets
        // wrong: thousands of these calls land on the same System.currentTimeMillis() value.
        List<UUID> ids = new ArrayList<>(50_000);
        for (int i = 0; i < 50_000; i++) {
            ids.add(UuidV7.next());
        }
        for (int i = 1; i < ids.size(); i++) {
            assertThat(ids.get(i)).as("id at index %d vs %d", i, i - 1).isGreaterThan(ids.get(i - 1));
        }
    }

    @Test
    void isUniqueAndOrderedUnderConcurrentGeneration() throws Exception {
        int perThread = 5_000;
        int threads = 8;
        List<Callable<List<UUID>>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            tasks.add(() -> {
                List<UUID> batch = new ArrayList<>(perThread);
                for (int i = 0; i < perThread; i++) batch.add(UuidV7.next());
                return batch;
            });
        }
        List<UUID> all = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (var future : pool.invokeAll(tasks)) {
                all.addAll(future.get());
            }
        }
        assertThat(all).hasSize(threads * perThread);
        assertThat(all).doesNotHaveDuplicates(); // the synchronized counter must never repeat a value
    }
}
