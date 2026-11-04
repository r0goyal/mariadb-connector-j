// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (c) 2012-2014 Monty Program Ab
// Copyright (c) 2015-2026 MariaDB plc
package org.mariadb.jdbc.client;

import org.mariadb.jdbc.BasePreparedStatement;
import org.mariadb.jdbc.export.Prepare;
import org.mariadb.jdbc.util.PreparedStatementFrequencyTracker;

/** LRU Prepare cache */
public interface PrepareCache {

  /**
   * Get cache value for key
   *
   * @param key key
   * @param preparedStatement prepared statement
   * @return Prepare value
   */
  Prepare get(String key, BasePreparedStatement preparedStatement);

  /**
   * Add a prepare cache value. Equivalent to {@link #put(String, Prepare, BasePreparedStatement,
   * PreparedStatementFrequencyTracker.Key)} without a frequency identity.
   *
   * @param key key
   * @param result value
   * @param preparedStatement current statement
   * @return Prepare if was already cached
   */
  Prepare put(String key, Prepare result, BasePreparedStatement preparedStatement);

  /**
   * Add a prepare cache value with its frequency-mode SQL identity, for eviction priority.
   *
   * @param key key
   * @param result value
   * @param preparedStatement current statement
   * @param identity frequency-mode SQL identity of the entry, or null in "all" strategy
   * @return Prepare if was already cached
   */
  Prepare put(
      String key,
      Prepare result,
      BasePreparedStatement preparedStatement,
      PreparedStatementFrequencyTracker.Key identity);

  /**
   * Record a submitted execution unit for the frequency strategy, then indicate whether its SQL is
   * selected for server preparation. A no-op that returns true in "all" strategy.
   *
   * @param database current database, can be null
   * @param sql exact native SQL, without timeout or row-limit wrappers
   * @param weight submitted execution units (batch size, or 1)
   * @return true if the SQL is selected for server preparation
   */
  boolean recordExecutionAndSelected(String database, String sql, long weight);

  /** Reset cache */
  void reset();
}
