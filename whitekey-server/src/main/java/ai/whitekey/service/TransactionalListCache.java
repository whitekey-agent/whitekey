package ai.whitekey.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Thread-safe, transaction-aware in-memory cache for read-mostly entity lists.
 * Invalidations inside an active transaction are deferred until {@code afterCommit},
 * and versioned CAS prevents concurrent loads from repopulating stale snapshots.
 */
final class TransactionalListCache<T> {

    private record Entry<T>(long version, List<T> value) {}

    private final AtomicReference<Entry<T>> ref = new AtomicReference<>(new Entry<>(0L, null));

    List<T> getOrLoad(Supplier<List<T>> loader) {
        Entry<T> current = ref.get();
        if (current.value() != null) {
            return current.value();
        }
        List<T> loaded = List.copyOf(loader.get());
        ref.compareAndSet(current, new Entry<>(current.version(), loaded));
        return loaded;
    }

    void invalidateAfterCommit() {
        ApprovalWaitRegistry.runAfterCommit(this::invalidateNow);
    }

    void invalidateNow() {
        ref.updateAndGet(entry -> new Entry<>(entry.version() + 1, null));
    }
}
