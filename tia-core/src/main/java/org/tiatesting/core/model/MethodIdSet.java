package org.tiatesting.core.model;

import java.io.Serializable;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Compact, sorted-unique {@code Set<Integer>} backed by an {@code int[]}.
 *
 * <p>Drop-in for the previous {@code HashSet<Integer>} used as the methods-impacted set on
 * {@link ClassImpactTracker}: same {@code Set<Integer>} contract, but stores its values as a
 * primitive {@code int[]} sorted in ascending order with no duplicates. For the typical Tia
 * payload (~6 method IDs per source class, ~940K classes per large project) this is roughly
 * one order of magnitude smaller in heap than {@code HashSet<Integer>} and removes the per-add
 * {@code Integer.valueOf} allocation that dominated GC overhead in profiling of
 * {@code H2DataStore.getTestSuitesData}.
 *
 * <p>Two add paths are exposed:
 * <ul>
 *   <li>{@link #add(int)} keeps the set sorted-unique on every insertion (binary insert).
 *       This is the right choice when adds are interleaved with reads.</li>
 *   <li>{@link #appendForBulkBuild(int)} appends without sorting; call
 *       {@link #finishBulkBuild()} when done to sort and dedupe. Use this when the caller
 *       knows it is doing a tight bulk-load loop with no reads in between (e.g. the H2
 *       streaming reducer).</li>
 * </ul>
 *
 * <p>{@code equals} / {@code hashCode} follow the {@link java.util.Set} contract — content
 * equality, sum-of-element-hashes — so a {@code MethodIdSet} compares equal to any other
 * {@code Set<Integer>} with the same contents.
 */
public final class MethodIdSet extends AbstractSet<Integer> implements Serializable {
    private static final long serialVersionUID = 1L;

    private static final int[] EMPTY = new int[0];

    /** Backing storage. Sorted ascending and deduplicated when {@code sorted == true}. */
    private int[] ids;
    private int size;
    /** When {@code false}, {@code ids[0..size)} may contain duplicates and unsorted entries. */
    private boolean sorted;

    public MethodIdSet() {
        this.ids = EMPTY;
        this.size = 0;
        this.sorted = true;
    }

    public MethodIdSet(int initialCapacity) {
        this.ids = initialCapacity == 0 ? EMPTY : new int[initialCapacity];
        this.size = 0;
        this.sorted = true;
    }

    /** Copy constructor accepting any {@link Collection} of integer values. */
    public MethodIdSet(Collection<? extends Integer> source) {
        if (source instanceof MethodIdSet) {
            MethodIdSet other = (MethodIdSet) source;
            other.ensureSorted();
            this.ids = other.size == 0 ? EMPTY : Arrays.copyOf(other.ids, other.size);
            this.size = other.size;
            this.sorted = true;
        } else {
            this.ids = source.isEmpty() ? EMPTY : new int[source.size()];
            this.size = 0;
            this.sorted = true;
            for (Integer i : source) {
                add(i.intValue());
            }
        }
    }

    /**
     * Add {@code id} maintaining the sorted-unique invariant. Returns {@code true} when the
     * id was not already present.
     */
    public boolean add(int id) {
        ensureSorted();
        int pos = Arrays.binarySearch(ids, 0, size, id);
        if (pos >= 0) {
            return false;
        }
        int insertAt = -pos - 1;
        ensureCapacity(size + 1);
        if (insertAt < size) {
            System.arraycopy(ids, insertAt, ids, insertAt + 1, size - insertAt);
        }
        ids[insertAt] = id;
        size++;
        return true;
    }

    /**
     * Append without sorting. Use only when the caller commits to calling
     * {@link #finishBulkBuild()} before any read or set-like operation. Cheaper than
     * {@link #add(int)} for the bulk-load case because it avoids the O(n) shift per insert.
     */
    public void appendForBulkBuild(int id) {
        ensureCapacity(size + 1);
        ids[size++] = id;
        sorted = false;
    }

    /** Finalise the set after a sequence of {@link #appendForBulkBuild(int)} calls. Idempotent. */
    public void finishBulkBuild() {
        if (sorted) {
            return;
        }
        Arrays.sort(ids, 0, size);
        if (size > 1) {
            int writePos = 1;
            for (int readPos = 1; readPos < size; readPos++) {
                if (ids[readPos] != ids[readPos - 1]) {
                    ids[writePos++] = ids[readPos];
                }
            }
            size = writePos;
        }
        sorted = true;
    }

    @Override
    public boolean add(Integer id) {
        return add(id.intValue());
    }

    public boolean contains(int id) {
        ensureSorted();
        return Arrays.binarySearch(ids, 0, size, id) >= 0;
    }

    @Override
    public boolean contains(Object o) {
        return o instanceof Integer && contains(((Integer) o).intValue());
    }

    @Override
    public int size() {
        ensureSorted();
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Bulk merge another {@link MethodIdSet}. Faster than the default
     * {@link AbstractSet#addAll(Collection)} which boxes every element.
     */
    public boolean addAll(MethodIdSet other) {
        if (other == null || other.isEmpty()) {
            return false;
        }
        ensureSorted();
        other.ensureSorted();
        int initialSize = size;
        // Two-pointer merge into a fresh int[] so we don't have to repeatedly shift.
        int[] merged = new int[size + other.size];
        int i = 0;
        int j = 0;
        int k = 0;
        while (i < size && j < other.size) {
            int a = ids[i];
            int b = other.ids[j];
            if (a < b) {
                merged[k++] = a;
                i++;
            } else if (a > b) {
                merged[k++] = b;
                j++;
            } else {
                merged[k++] = a;
                i++;
                j++;
            }
        }
        while (i < size) merged[k++] = ids[i++];
        while (j < other.size) merged[k++] = other.ids[j++];
        ids = merged;
        size = k;
        return size != initialSize;
    }

    @Override
    public boolean addAll(Collection<? extends Integer> source) {
        if (source instanceof MethodIdSet) {
            return addAll((MethodIdSet) source);
        }
        return super.addAll(source);
    }

    @Override
    public Iterator<Integer> iterator() {
        ensureSorted();
        return new Iter();
    }

    private final class Iter implements Iterator<Integer> {
        private int cursor = 0;

        @Override public boolean hasNext() { return cursor < size; }

        @Override public Integer next() {
            if (cursor >= size) {
                throw new NoSuchElementException();
            }
            return ids[cursor++];
        }
    }

    private void ensureSorted() {
        if (!sorted) {
            finishBulkBuild();
        }
    }

    private void ensureCapacity(int needed) {
        if (needed > ids.length) {
            int newCapacity = Math.max(needed, ids.length == 0 ? 4 : ids.length * 2);
            ids = Arrays.copyOf(ids, newCapacity);
        }
    }

    /**
     * Returns the contents as a defensive copy of the internal array trimmed to {@link #size()}.
     * Mostly useful for tests and serialization-style use cases.
     */
    public int[] toIntArray() {
        ensureSorted();
        return size == 0 ? EMPTY : Arrays.copyOf(ids, size);
    }
}
