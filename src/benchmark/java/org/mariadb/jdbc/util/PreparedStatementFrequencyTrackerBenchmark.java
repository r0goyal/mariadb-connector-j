// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (c) 2012-2014 Monty Program Ab
// Copyright (c) 2015-2026 MariaDB plc
package org.mariadb.jdbc.util;

import java.util.concurrent.TimeUnit;
import org.mariadb.jdbc.util.PreparedStatementFrequencyTracker.Key;
import org.openjdk.jmh.annotations.*;

/** Tracker-only measurements. No database or connection pool is involved. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(
    value = 2,
    jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@Threads(1)
public class PreparedStatementFrequencyTrackerBenchmark {
  @State(Scope.Thread)
  public static class Queries {
    @Param({"5000", "30000"})
    public int window;

    @Param({"2048", "3072"})
    public int sqlBytes;

    private Key[] keys;
    private PreparedStatementFrequencyTracker policy;
    private int sequence;

    @Setup(Level.Trial)
    public void setup() {
      keys = new Key[600];
      for (int i = 0; i < keys.length; i++) {
        keys[i] = key(i, sqlBytes);
      }
      policy = new PreparedStatementFrequencyTracker(100, window);
      for (int i = 0; i < window; i++) {
        policy.recordExecution(keys[i % 300], 1);
      }
    }

    private Key repeatedKey() {
      return keys[(sequence++ & Integer.MAX_VALUE) % 300];
    }

    private Key tailKey() {
      int next = sequence++ & Integer.MAX_VALUE;
      return next % 10 == 0 ? key(600 + next, sqlBytes) : keys[next % 300];
    }
  }

  @Benchmark
  public boolean repeated(Queries state) {
    Key key = state.repeatedKey();
    state.policy.recordExecution(key, 1);
    return state.policy.isSelected(key);
  }

  /** Includes the identity allocation made by the integrated cache recording path. */
  @Benchmark
  public boolean repeatedWithNewIdentity(Queries state) {
    Key input = state.repeatedKey();
    Key key = new Key(input.database(), input.sql());
    state.policy.recordExecution(key, 1);
    return state.policy.isSelected(key);
  }

  /** Includes construction of fresh SQL for the ten-percent one-use tail. */
  @Benchmark
  public boolean oneUseTail(Queries state) {
    Key key = state.tailKey();
    state.policy.recordExecution(key, 1);
    return state.policy.isSelected(key);
  }

  /** Input-generation baseline for oneUseTail; this method does not update the tracker. */
  @Benchmark
  public Key tailInputBaseline(Queries state) {
    return state.tailKey();
  }

  /** Alternates between two disjoint sets of 300 queries every window execution units. */
  @Benchmark
  public boolean workloadShift(Queries state) {
    int next = state.sequence++ & Integer.MAX_VALUE;
    int offset = ((next / state.window) & 1) * 300;
    Key key = state.keys[offset + next % 300];
    state.policy.recordExecution(key, 1);
    return state.policy.isSelected(key);
  }

  /** Reports cost per batch submission, not per individual execution unit. */
  @Benchmark
  public boolean batch64(Queries state) {
    Key key = state.repeatedKey();
    state.policy.recordExecution(key, 64);
    return state.policy.isSelected(key);
  }

  private static Key key(int id, int sqlBytes) {
    String prefix = "SELECT ? /* query " + id + " ";
    return new Key("benchmark", prefix + "x".repeat(sqlBytes - prefix.length() - 2) + "*/");
  }
}
