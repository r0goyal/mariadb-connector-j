// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (c) 2012-2014 Monty Program Ab
// Copyright (c) 2015-2026 MariaDB plc
package org.mariadb.jdbc.util;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Connection-local selection of SQL for server preparation, based on exact execution counts over
 * the last {@code windowSize} submitted execution units.
 *
 * <p>The caller must hold the connection lock. This class creates no threads and owns no server
 * handles. History grows lazily up to the window size, is reused afterwards, and is released by
 * {@link #reset()}.
 */
public final class PreparedStatementFrequencyTracker {
  /** SQL identity before timeout and row-limit wrappers. The database can be null. */
  public record Key(String database, String sql) {
    public Key {
      Objects.requireNonNull(sql, "sql");
    }
  }

  /** Executions needed before an identity can be selected. Fixed internal policy. */
  public static final int MINIMUM_FREQUENCY = 2;

  /** Percentage by which a challenger must exceed a selected identity to replace it. */
  public static final int REPLACEMENT_MARGIN_PERCENT = 20;

  private static final Comparator<Counter> ORDER =
      Comparator.<Counter>comparingInt(counter -> counter.count)
          .reversed()
          .thenComparingLong(counter -> counter.sequence);

  private static final Counter[] EMPTY_COUNTERS = new Counter[0];
  private static final int[] EMPTY_WEIGHTS = new int[0];

  private final int maximumSize;
  private final int windowSize;
  private final Map<Key, Counter> counters = new HashMap<>();
  private final TreeSet<Counter> candidates = new TreeSet<>(ORDER);
  private final TreeSet<Counter> selected = new TreeSet<>(ORDER);
  private Counter[] historyCounters = EMPTY_COUNTERS;
  private int[] historyWeights = EMPTY_WEIGHTS;
  private int windowWeight;
  private int historyHead;
  private int historySize;
  private long sequence;

  /**
   * Creates a tracker. All limits apply to this connection, not to the application.
   *
   * @param maximumSize maximum number of selected SQL identities
   * @param windowSize maximum retained execution units, at least maximumSize
   */
  public PreparedStatementFrequencyTracker(int maximumSize, int windowSize) {
    if (maximumSize <= 0 || windowSize < maximumSize) {
      throw new IllegalArgumentException("Require 0 < maximumSize <= windowSize");
    }
    this.maximumSize = maximumSize;
    this.windowSize = windowSize;
  }

  /**
   * Records submitted execution units, including text executions. Do not record internal retries.
   *
   * <p>A batch uses one weighted observation. An oversized batch retains only its last windowSize
   * units, without iterating over individual units.
   *
   * @param key exact database and native SQL identity
   * @param weight number of submitted execution units, greater than zero
   */
  public void recordExecution(Key key, long weight) {
    Objects.requireNonNull(key, "key");
    if (weight <= 0) {
      throw new IllegalArgumentException("weight must be positive");
    }
    int retainedWeight = (int) Math.min(weight, windowSize);
    removeWeight(Math.max(0L, (long) windowWeight + retainedWeight - windowSize));

    Counter counter = counters.get(key);
    if (counter == null) {
      counter = new Counter(key, sequence++);
      counters.put(key, counter);
    }
    changeCount(counter, retainedWeight);
    ensureHistoryCapacity();
    int tail = historyIndex(historySize);
    // Retain the canonical counter, not a new SQL string for each execution.
    historyCounters[tail] = counter;
    historyWeights[tail] = retainedWeight;
    historySize++;
    windowWeight += retainedWeight;
    rebalance();
  }

  /** Returns membership without changing frequency, history, or selection. */
  public boolean isSelected(Key key) {
    Counter counter = counters.get(Objects.requireNonNull(key, "key"));
    return counter != null && counter.selected;
  }

  /** Returns the exact retained frequency. */
  public int frequency(Key key) {
    Counter counter = counters.get(Objects.requireNonNull(key, "key"));
    return counter == null ? 0 : counter.count;
  }

  /** Returns the number of retained SQL identities, at most windowSize. */
  public int size() {
    return counters.size();
  }

  /** Clears tracking state and releases history storage after a physical connection reset. */
  public void reset() {
    historyCounters = EMPTY_COUNTERS;
    historyWeights = EMPTY_WEIGHTS;
    historyHead = 0;
    historySize = 0;
    counters.clear();
    candidates.clear();
    selected.clear();
    windowWeight = 0;
    sequence = 0;
  }

  private void removeWeight(long excess) {
    while (excess > 0) {
      Counter counter = historyCounters[historyHead];
      int removed = (int) Math.min(excess, historyWeights[historyHead]);
      historyWeights[historyHead] -= removed;
      windowWeight -= removed;
      excess -= removed;
      changeCount(counter, -removed);
      if (historyWeights[historyHead] == 0) {
        removeFirstHistory();
      }
    }
  }

  private int historyIndex(int offset) {
    int remaining = historyCounters.length - historyHead;
    return offset < remaining ? historyHead + offset : offset - remaining;
  }

  private void removeFirstHistory() {
    // An unused slot must not retain an evicted SQL identity.
    historyCounters[historyHead] = null;
    historyHead++;
    if (historyHead == historyCounters.length) {
      historyHead = 0;
    }
    historySize--;
  }

  private void ensureHistoryCapacity() {
    int previous = historyCounters.length;
    if (historySize < previous) {
      return;
    }
    int capacity =
        (int) Math.min(windowSize, previous == 0 ? 16L : previous + Math.max(1L, previous / 2L));
    Counter[] nextCounters = new Counter[capacity];
    int[] nextWeights = new int[capacity];
    int first = Math.min(historySize, previous - historyHead);
    System.arraycopy(historyCounters, historyHead, nextCounters, 0, first);
    System.arraycopy(historyCounters, 0, nextCounters, first, historySize - first);
    System.arraycopy(historyWeights, historyHead, nextWeights, 0, first);
    System.arraycopy(historyWeights, 0, nextWeights, first, historySize - first);
    historyCounters = nextCounters;
    historyWeights = nextWeights;
    historyHead = 0;
  }

  private void changeCount(Counter counter, int delta) {
    // Remove before mutation: both tree orders depend on count.
    if (counter.count > 0) {
      (counter.selected ? selected : candidates).remove(counter);
    }
    counter.count += delta;
    if (counter.count == 0) {
      counters.remove(counter.key);
      counter.selected = false;
      return;
    }
    if (counter.count < MINIMUM_FREQUENCY) {
      counter.selected = false;
    }
    (counter.selected ? selected : candidates).add(counter);
  }

  private void rebalance() {
    while (!candidates.isEmpty()) {
      Counter challenger = candidates.first();
      if (challenger.count < MINIMUM_FREQUENCY) {
        return;
      }
      if (selected.size() == maximumSize) {
        Counter incumbent = selected.last();
        // Long arithmetic prevents overflow for large windows and weighted batches.
        if ((long) challenger.count * 100
            <= (long) incumbent.count * (100 + REPLACEMENT_MARGIN_PERCENT)) {
          return;
        }
        selected.remove(incumbent);
        incumbent.selected = false;
        candidates.add(incumbent);
      }
      candidates.remove(challenger);
      challenger.selected = true;
      selected.add(challenger);
    }
  }

  private static final class Counter {
    private final Key key;
    private final long sequence;
    private int count;
    private boolean selected;

    private Counter(Key key, long sequence) {
      this.key = key;
      this.sequence = sequence;
    }
  }
}
