// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (c) 2012-2014 Monty Program Ab
// Copyright (c) 2015-2026 MariaDB plc
package org.mariadb.jdbc.util;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.mariadb.jdbc.util.PreparedStatementFrequencyTracker.Key;

class PreparedStatementFrequencyTrackerTest {
  private static final Key A = new Key("db", "SELECT ?");
  private static final Key B = new Key("db", "SELECT ? + 1");
  private static final Key C = new Key("db", "SELECT ? + 2");

  private PreparedStatementFrequencyTracker tracker(int capacity, int window) {
    return new PreparedStatementFrequencyTracker(capacity, window);
  }

  @Test
  void countsRepeatedExecutionsWithoutCounterSaturation() {
    var policy = tracker(2, 2000);
    assertFalse(policy.isSelected(A));
    policy.recordExecution(A, 1);
    assertFalse(policy.isSelected(A));
    for (int i = 1; i < 1000; i++) {
      policy.recordExecution(A, 1);
    }
    assertEquals(1000, policy.frequency(A));
    assertTrue(policy.isSelected(A));
  }

  @Test
  void rejectsOneUseQueriesAndKeepsAllWindowIdentities() {
    var policy = tracker(2, 100);
    for (int i = 0; i < 100; i++) {
      Key key = new Key("db", "SELECT " + i);
      policy.recordExecution(key, 1);
      assertFalse(policy.isSelected(key));
    }
    assertEquals(100, policy.size());
    policy.recordExecution(A, 2);
    assertEquals(99, policy.size());
    assertEquals(0, policy.frequency(new Key("db", "SELECT 0")));
    assertEquals(0, policy.frequency(new Key("db", "SELECT 1")));
    assertTrue(policy.isSelected(A));
  }

  @Test
  void keepsIncumbentOnTiesAndAtReplacementMargin() {
    var policy = tracker(1, 100);
    policy.recordExecution(A, 5);
    policy.recordExecution(B, 5);
    assertTrue(policy.isSelected(A));
    assertFalse(policy.isSelected(B));
    policy.recordExecution(B, 1);
    assertTrue(policy.isSelected(A));
    policy.recordExecution(B, 1);
    assertFalse(policy.isSelected(A));
    assertTrue(policy.isSelected(B));
  }

  @Test
  void removesPartialWeightedObservationsAtWindowBoundary() {
    var policy = tracker(2, 10);
    policy.recordExecution(A, 6);
    policy.recordExecution(B, 6);
    assertEquals(4, policy.frequency(A));
    assertEquals(6, policy.frequency(B));
    policy.recordExecution(C, 5);
    assertEquals(0, policy.frequency(A));
    assertEquals(5, policy.frequency(B));
    assertEquals(5, policy.frequency(C));
    assertFalse(policy.isSelected(A));
    assertTrue(policy.isSelected(B));
    assertTrue(policy.isSelected(C));
  }

  @Test
  void clipsOversizedBatchWithoutLongOverflow() {
    var policy = tracker(1, 10);
    policy.recordExecution(A, 8);
    policy.recordExecution(B, Long.MAX_VALUE);
    assertEquals(0, policy.frequency(A));
    assertEquals(10, policy.frequency(B));
    assertEquals(1, policy.size());
    assertTrue(policy.isSelected(B));
  }

  @Test
  void distinguishesDatabasesAndHashCollisions() {
    Key aa = new Key("db", "Aa");
    Key bb = new Key("db", "BB");
    Key otherDatabase = new Key("other", "Aa");
    assertEquals(aa.hashCode(), bb.hashCode());
    var policy = tracker(2, 100);
    policy.recordExecution(aa, 10);
    policy.recordExecution(bb, 1);
    policy.recordExecution(otherDatabase, 5);
    assertEquals(10, policy.frequency(aa));
    assertEquals(1, policy.frequency(bb));
    assertEquals(5, policy.frequency(otherDatabase));
    assertTrue(policy.isSelected(aa));
    assertFalse(policy.isSelected(bb));
    assertTrue(policy.isSelected(otherDatabase));
  }

  @Test
  void resetsAndDoesNotShareConnectionState() {
    var first = tracker(1, 10);
    var second = tracker(1, 10);
    first.recordExecution(A, 2);
    assertEquals(0, second.size());
    assertFalse(second.isSelected(A));
    first.reset();
    assertEquals(0, first.size());
    assertFalse(first.isSelected(A));
    first.recordExecution(B, 2);
    assertTrue(first.isSelected(B));
  }

  @Test
  void growsHistoryUpToTheWindowAndKeepsChronologicalOrderAcrossWraps()
      throws ReflectiveOperationException {
    var policy = tracker(2, 40);
    Key[] keys = new Key[60];
    for (int i = 0; i < keys.length; i++) {
      keys[i] = new Key("db", "SELECT " + i);
    }
    for (int i = 0; i < 40; i++) {
      policy.recordExecution(keys[i], 1);
    }
    assertEquals(40, Array.getLength(storage(policy, "historyCounters")));
    for (int i = 40; i < 60; i++) {
      policy.recordExecution(keys[i], 1);
    }
    assertEquals(40, Array.getLength(storage(policy, "historyCounters")));
    for (int i = 0; i < 60; i++) {
      assertEquals(i < 20 ? 0 : 1, policy.frequency(keys[i]), "key " + i);
    }
    assertEquals(40, policy.size());
  }

  @Test
  void reusesRingSlotsAcrossManyWraps() throws ReflectiveOperationException {
    var policy = tracker(1, 2);
    for (int i = 0; i < 100; i++) {
      policy.recordExecution(A, 1);
      policy.recordExecution(B, 1);
    }
    assertEquals(1, policy.frequency(A));
    assertEquals(1, policy.frequency(B));
    assertEquals(2, policy.size());
    assertEquals(2, Array.getLength(storage(policy, "historyCounters")));
  }

  @Test
  void largeWeightedWindowDoesNotReserveOneSlotPerExecutionUnit() {
    var policy = tracker(1, Integer.MAX_VALUE);
    policy.recordExecution(A, Long.MAX_VALUE);
    policy.recordExecution(B, 1);
    assertEquals(Integer.MAX_VALUE - 1, policy.frequency(A));
    assertEquals(1, policy.frequency(B));
    assertEquals(2, policy.size());
    assertTrue(policy.isSelected(A));
    assertFalse(policy.isSelected(B));
  }

  @Test
  void clearsEvictedReferencesFromRingSlots() throws ReflectiveOperationException {
    var policy = tracker(2, 4);
    policy.recordExecution(A, 1);
    policy.recordExecution(B, 1);
    policy.recordExecution(C, 1);
    policy.recordExecution(new Key("db", "SELECT 4"), 3);
    Object slots = storage(policy, "historyCounters");
    assertNull(Array.get(slots, 0));
    assertNull(Array.get(slots, 1));
    assertNotNull(Array.get(slots, 2));
    assertNotNull(Array.get(slots, 3));
    assertEquals(0, policy.frequency(A));
    assertEquals(0, policy.frequency(B));
    assertEquals(1, policy.frequency(C));
  }

  @Test
  void resetReleasesHistoryStorageAndStaysUsable() throws ReflectiveOperationException {
    var policy = tracker(2, 64);
    for (int i = 0; i < 40; i++) {
      policy.recordExecution(new Key("db", "SELECT " + i), 1);
    }
    assertTrue(Array.getLength(storage(policy, "historyCounters")) > 0);
    policy.reset();
    assertEquals(0, Array.getLength(storage(policy, "historyCounters")));
    assertEquals(0, Array.getLength(storage(policy, "historyWeights")));
    assertEquals(0, policy.size());
    policy.recordExecution(A, 2);
    assertEquals(2, policy.frequency(A));
    assertTrue(policy.isSelected(A));
  }

  @Test
  void matchesExactReferenceWindowAcrossRandomWeightedExecutions() {
    var policy = tracker(3, 30);
    ArrayDeque<Key> history = new ArrayDeque<>();
    Random random = new Random(90210);
    for (int step = 0; step < 3000; step++) {
      Key key = new Key("db" + random.nextInt(2), "SELECT " + random.nextInt(8));
      int weight = 1 + random.nextInt(50);
      for (int i = 0; i < weight; i++) {
        history.addLast(key);
        if (history.size() > 30) {
          history.removeFirst();
        }
      }
      policy.recordExecution(key, weight);
      Map<Key, Integer> expected = new HashMap<>();
      for (Key observed : history) {
        expected.merge(observed, 1, Integer::sum);
      }
      assertEquals(expected.size(), policy.size(), "step " + step);
      int selectedCount = 0;
      int eligible = 0;
      int weakestSelected = Integer.MAX_VALUE;
      int strongestRejected = 0;
      for (int database = 0; database < 2; database++) {
        for (int sql = 0; sql < 8; sql++) {
          Key known = new Key("db" + database, "SELECT " + sql);
          int frequency = expected.getOrDefault(known, 0);
          assertEquals(frequency, policy.frequency(known), "step " + step + ": " + known);
          if (frequency >= PreparedStatementFrequencyTracker.MINIMUM_FREQUENCY) {
            eligible++;
          }
          if (policy.isSelected(known)) {
            assertTrue(
                frequency >= PreparedStatementFrequencyTracker.MINIMUM_FREQUENCY, "step " + step);
            selectedCount++;
            weakestSelected = Math.min(weakestSelected, frequency);
          } else {
            strongestRejected = Math.max(strongestRejected, frequency);
          }
        }
      }
      assertEquals(Math.min(3, eligible), selectedCount, "step " + step);
      if (selectedCount == 3) {
        // A rejected challenger never exceeds the weakest selected identity by the margin.
        assertTrue(
            strongestRejected * 100L
                <= weakestSelected
                    * (100L + PreparedStatementFrequencyTracker.REPLACEMENT_MARGIN_PERCENT),
            "ranking at step " + step);
      }
    }
  }

  @Test
  void validatesParametersAndRejectsInvalidWeightsWithoutMutation() {
    assertThrows(IllegalArgumentException.class, () -> tracker(0, 10));
    assertThrows(IllegalArgumentException.class, () -> tracker(2, 1));
    assertThrows(NullPointerException.class, () -> new Key("db", null));
    var policy = tracker(1, 10);
    policy.recordExecution(A, 2);
    assertThrows(IllegalArgumentException.class, () -> policy.recordExecution(B, 0));
    assertThrows(IllegalArgumentException.class, () -> policy.recordExecution(B, -1));
    assertThrows(NullPointerException.class, () -> policy.recordExecution(null, 1));
    assertEquals(2, policy.frequency(A));
    assertEquals(1, policy.size());
    assertTrue(policy.isSelected(A));
  }

  private Object storage(PreparedStatementFrequencyTracker policy, String name)
      throws ReflectiveOperationException {
    Field field = PreparedStatementFrequencyTracker.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(policy);
  }
}
