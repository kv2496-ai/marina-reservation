package edu.whoi.marina.store;

import java.util.List;

/**
 * A unit of work executed under the collection's write lock, given the full mutable in-memory
 * list. Throwing from {@link #apply} aborts the transaction — nothing is persisted — which is how
 * callers implement "check for a conflict, then write, atomically" (e.g. reservation creation).
 */
@FunctionalInterface
public interface StoreTransaction<T, R> {
    R apply(List<T> mutableList);
}
