// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (c) 2012-2014 Monty Program Ab
// Copyright (c) 2015-2026 MariaDB plc
package org.mariadb.jdbc.unit.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.mariadb.jdbc.client.impl.PrepareCache;
import org.mariadb.jdbc.message.server.PrepareResultPacket;
import org.mariadb.jdbc.util.PreparedStatementFrequencyTracker;

public class PrepareCacheTest {

  @Test
  public void resetClearsFrequencyLearning() {
    PreparedStatementFrequencyTracker selection = new PreparedStatementFrequencyTracker(2, 16);
    PrepareCache cache = new PrepareCache(2, null, selection);
    assertFalse(cache.recordExecutionAndSelected("db", "SELECT ?", 1));
    assertTrue(cache.recordExecutionAndSelected("db", "SELECT ?", 1));
    cache.reset();
    assertEquals(0, selection.size());
    assertFalse(cache.recordExecutionAndSelected("db", "SELECT ?", 1));
  }

  @Test
  public void allStrategyDoesNotTrackExecutions() {
    PrepareCache cache = new PrepareCache(2, null);
    assertTrue(cache.recordExecutionAndSelected("db", "SELECT ?", 1));
    cache.reset();
    assertTrue(cache.recordExecutionAndSelected("db", "SELECT ?", 1));
  }

  @Test
  public void check() {
    PrepareCache cache = new PrepareCache(20, null);
    try {
      cache.get("dd");
      fail();
    } catch (IllegalStateException s) {
      assertTrue(s.getMessage().contains("not available method"));
    }
    try {
      cache.put("dd", (PrepareResultPacket) null);
      fail();
    } catch (IllegalStateException s) {
      assertTrue(s.getMessage().contains("not available method"));
    }
  }
}
