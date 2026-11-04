// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (c) 2012-2014 Monty Program Ab
// Copyright (c) 2015-2026 MariaDB plc
package org.mariadb.jdbc.client.impl;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.mariadb.jdbc.BasePreparedStatement;
import org.mariadb.jdbc.client.util.ClosableLock;
import org.mariadb.jdbc.export.Prepare;
import org.mariadb.jdbc.message.server.CachedPrepareResultPacket;
import org.mariadb.jdbc.message.server.PrepareResultPacket;
import org.mariadb.jdbc.util.PreparedStatementFrequencyTracker;

/** LRU prepare cache, evicting non-selected entries before selected ones in frequency mode */
public final class PrepareCache extends LinkedHashMap<String, CachedPrepareResultPacket>
    implements org.mariadb.jdbc.client.PrepareCache {

  private static final long serialVersionUID = -8922905563713952695L;

  /** cache maximum size */
  private final int maxSize;

  private final transient ClosableLock lock = new ClosableLock();

  /** client */
  private final transient StandardClient con;

  /**
   * SQL identities selected for server preparation in frequency mode. Entries absent from this set
   * are evicted first. Null in "all" strategy: eviction is then plain LRU.
   */
  private final transient PreparedStatementFrequencyTracker selection;

  /** frequency-mode identity of each cached entry, for eviction priority */
  private final transient Map<String, PreparedStatementFrequencyTracker.Key> identities;

  /**
   * LRU prepare cache constructor
   *
   * @param size cache size
   * @param con client
   */
  public PrepareCache(int size, StandardClient con) {
    this(size, con, null);
  }

  /**
   * LRU prepare cache constructor with frequency selection
   *
   * @param size cache size
   * @param con client
   * @param selection connection-local selection state, or null for plain LRU
   */
  public PrepareCache(int size, StandardClient con, PreparedStatementFrequencyTracker selection) {
    super(size, .75f, true);
    this.maxSize = size;
    this.con = con;
    this.selection = selection;
    this.identities = selection == null ? null : new HashMap<>();
  }

  @Override
  public boolean removeEldestEntry(Map.Entry<String, CachedPrepareResultPacket> eldest) {
    if (this.size() > maxSize) {
      String victim = selectEvictionVictim();
      if (victim == null) {
        eldest.getValue().unCache(con);
        if (identities != null) identities.remove(eldest.getKey());
        return true;
      }
      remove(victim).unCache(con);
      identities.remove(victim);
    }
    return false;
  }

  /**
   * Returns the cache key of the eviction victim: the least-recently-used entry not selected for
   * server preparation, or null when all entries are selected (caller then evicts the eldest).
   */
  private String selectEvictionVictim() {
    if (selection == null) {
      return null;
    }
    for (Map.Entry<String, CachedPrepareResultPacket> entry : entrySet()) {
      PreparedStatementFrequencyTracker.Key identity = identities.get(entry.getKey());
      if (identity == null || !selection.isSelected(identity)) {
        return entry.getKey();
      }
    }
    return null;
  }

  public Prepare put(String key, Prepare result, BasePreparedStatement preparedStatement) {
    return put(key, result, preparedStatement, null);
  }

  public Prepare put(
      String key,
      Prepare result,
      BasePreparedStatement preparedStatement,
      PreparedStatementFrequencyTracker.Key identity) {
    // a ReentrantLock, not a monitor: evicting an entry sends COM_STMT_CLOSE to the server, and a
    // virtual thread parking in a socket write must not pin its carrier
    try (ClosableLock ignore = lock.closeableLock()) {
      CachedPrepareResultPacket cached = super.get(key);

      // if there is already some cached data, return existing cached data
      if (cached != null) {
        cached.incrementUse(preparedStatement);
        ((CachedPrepareResultPacket) result).unCache(con);
        return cached;
      }

      if (((CachedPrepareResultPacket) result).cache()) {
        ((CachedPrepareResultPacket) result).incrementUse(preparedStatement);
        // Register before insertion: the eviction callback must see the new entry's identity.
        if (selection != null) identities.put(key, identity);
        super.put(key, (CachedPrepareResultPacket) result);
      }
      return null;
    }
  }

  /**
   * Record a submitted execution unit in frequency mode, then indicate whether its SQL is selected
   * for server preparation. No-op returning true in "all" mode.
   *
   * @param database current database, can be null
   * @param sql exact native SQL
   * @param weight submitted execution units
   * @return true if the SQL is selected for server preparation
   */
  public boolean recordExecutionAndSelected(String database, String sql, long weight) {
    if (selection == null) {
      return true;
    }
    PreparedStatementFrequencyTracker.Key key =
        new PreparedStatementFrequencyTracker.Key(database, sql);
    selection.recordExecution(key, weight);
    return selection.isSelected(key);
  }

  public Prepare get(String key, BasePreparedStatement preparedStatement) {
    try (ClosableLock ignore = lock.closeableLock()) {
      CachedPrepareResultPacket prepare = super.get(key);
      if (prepare != null && preparedStatement != null) {
        prepare.incrementUse(preparedStatement);
      }
      return prepare;
    }
  }

  public CachedPrepareResultPacket get(Object key) {
    throw new IllegalStateException("not available method");
  }

  /**
   * NOT USED
   *
   * @param key key
   * @param result results
   * @return will throw an exception
   */
  @SuppressWarnings("unused")
  public CachedPrepareResultPacket put(String key, PrepareResultPacket result) {
    throw new IllegalStateException("not available method");
  }

  public void reset() {
    for (CachedPrepareResultPacket prep : values()) {
      prep.reset();
    }
    this.clear();
    if (identities != null) {
      identities.clear();
    }
    if (selection != null) selection.reset();
  }
}
