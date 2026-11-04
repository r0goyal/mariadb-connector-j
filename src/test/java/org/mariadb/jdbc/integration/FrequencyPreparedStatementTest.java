// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (c) 2012-2014 Monty Program Ab
// Copyright (c) 2015-2026 MariaDB plc
package org.mariadb.jdbc.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.mariadb.jdbc.Connection;
import org.mariadb.jdbc.export.HaMode;

/**
 * Frequency-mode coverage uses the normal integration fixture and session-scoped server counters.
 */
public class FrequencyPreparedStatementTest extends Common {
  private static final String FREQUENCY =
      "&useServerPrepStmts=true&cachePrepStmts=true&prepStmtCacheSize=2"
          + "&serverPrepStmtStrategy=frequency&serverPrepStmtWindowSize=16";

  @Test
  void reusedStatementPromotesFromTextToBinaryWithoutChangingOwner() throws SQLException {
    try (Connection con = createCon(FREQUENCY);
        PreparedStatement statement = con.prepareStatement("SELECT ? AS v")) {
      long initial = status(con, "Com_stmt_prepare");
      execute(statement, 42);
      assertEquals(initial, status(con, "Com_stmt_prepare"));
      execute(statement, 43);
      assertEquals(initial + 1, status(con, "Com_stmt_prepare"));
      execute(statement, 44);
      assertEquals(initial + 1, status(con, "Com_stmt_prepare"));
    }
  }

  @Test
  void promotionWorksWithoutPipeline() throws SQLException {
    try (Connection con = createCon(FREQUENCY + "&disablePipeline=true");
        PreparedStatement statement = con.prepareStatement("SELECT ? AS no_pipeline")) {
      long prepared = status(con, "Com_stmt_prepare");
      execute(statement, 1);
      assertEquals(prepared, status(con, "Com_stmt_prepare"));
      execute(statement, 2);
      assertEquals(prepared + 1, status(con, "Com_stmt_prepare"));
      execute(statement, 3);
      assertEquals(prepared + 1, status(con, "Com_stmt_prepare"));
    }
  }

  @Test
  void timeoutAndRowLimitChangesKeepOneFrequencyIdentity() throws SQLException {
    try (Connection con = createCon(FREQUENCY);
        PreparedStatement statement = con.prepareStatement("SELECT ? AS settings_identity")) {
      org.junit.jupiter.api.Assumptions.assumeTrue(
          con.useServerTimeout() && con.useServerMaxRows());
      long prepared = status(con, "Com_stmt_prepare");
      statement.setQueryTimeout(1);
      statement.setMaxRows(1);
      execute(statement, 1);
      assertEquals(prepared, status(con, "Com_stmt_prepare"));
      statement.setQueryTimeout(2);
      statement.setMaxRows(2);
      execute(statement, 2);
      assertEquals(prepared + 1, status(con, "Com_stmt_prepare"));
      statement.setMaxRows(3);
      execute(statement, 3);
      assertEquals(prepared + 2, status(con, "Com_stmt_prepare"));
    }
  }

  @Test
  void coldQueriesDoNotPrepareOrEvictHotHandles() throws SQLException {
    try (Connection con = createCon(FREQUENCY)) {
      String hot = "SELECT ? AS hot";
      try (PreparedStatement statement = con.prepareStatement(hot)) {
        execute(statement, 1);
        execute(statement, 2);
      }
      long prepared = status(con, "Com_stmt_prepare");
      for (String sql :
          new String[] {
            "SELECT ? AS cold_a", "SELECT ? AS cold_b", "SELECT ? AS cold_c", "SELECT ? AS cold_d"
          }) {
        try (PreparedStatement statement = con.prepareStatement(sql)) {
          execute(statement, 3);
        }
      }
      assertEquals(prepared, status(con, "Com_stmt_prepare"));
      try (PreparedStatement statement = con.prepareStatement(hot)) {
        execute(statement, 4);
      }
      assertEquals(prepared, status(con, "Com_stmt_prepare"));
    }
  }

  @Test
  void learningIsIndependentAcrossConnections() throws SQLException {
    try (Connection first = createCon(FREQUENCY);
        Connection second = createCon(FREQUENCY);
        PreparedStatement hot = first.prepareStatement("SELECT ? AS v");
        PreparedStatement cold = second.prepareStatement("SELECT ? AS v")) {
      long firstInitial = status(first, "Com_stmt_prepare");
      long secondInitial = status(second, "Com_stmt_prepare");
      execute(hot, 1);
      execute(hot, 2);
      assertEquals(firstInitial + 1, status(first, "Com_stmt_prepare"));
      execute(cold, 3);
      assertEquals(secondInitial, status(second, "Com_stmt_prepare"));
    }
  }

  @Test
  void metadataUsesTemporaryHandlesWithoutLearningOrCacheRetention() throws SQLException {
    try (Connection con = createCon(FREQUENCY);
        PreparedStatement statement = con.prepareStatement("SELECT ? AS metadata_value")) {
      long prepared = status(con, "Com_stmt_prepare");
      long closed = status(con, "Com_stmt_close");
      assertEquals(1, statement.getMetaData().getColumnCount());
      assertEquals(1, statement.getParameterMetaData().getParameterCount());
      assertEquals(prepared + 2, status(con, "Com_stmt_prepare"));
      assertEquals(closed + 2, status(con, "Com_stmt_close"));
      execute(statement, 1);
      assertEquals(prepared + 2, status(con, "Com_stmt_prepare"));
      execute(statement, 2);
      assertEquals(prepared + 3, status(con, "Com_stmt_prepare"));
    }
  }

  @Test
  void evictionPrefersDemotedHandleAndDefersCloseWhileItIsInUse() throws SQLException {
    try (Connection con = createCon(FREQUENCY);
        PreparedStatement oldest = con.prepareStatement("SELECT ? AS oldest_hot");
        PreparedStatement demoted = con.prepareStatement("SELECT ? AS newer_demoted");
        PreparedStatement challenger = con.prepareStatement("SELECT ? AS challenger")) {
      for (int i = 0; i < 6; i++) execute(oldest, i);
      oldest.close();
      execute(demoted, 1);
      execute(demoted, 2);
      long closed = status(con, "Com_stmt_close");
      execute(challenger, 1);
      execute(challenger, 2);
      execute(challenger, 3);
      long prepared = status(con, "Com_stmt_prepare");
      assertEquals(closed, status(con, "Com_stmt_close"));
      try (PreparedStatement reused = con.prepareStatement("SELECT ? AS oldest_hot")) {
        execute(reused, 7);
      }
      execute(demoted, 3);
      assertEquals(prepared, status(con, "Com_stmt_prepare"));
      demoted.close();
      assertEquals(closed + 1, status(con, "Com_stmt_close"));
    }
  }

  @Test
  void batchesCountSubmittedParameterSetsAndKeepColdSqlOnText() throws SQLException {
    try (Connection con = createCon(FREQUENCY)) {
      try (java.sql.Statement setup = con.createStatement()) {
        setup.execute("CREATE TEMPORARY TABLE frequency_batch (v INT)");
      }
      long prepared = status(con, "Com_stmt_prepare");
      try (PreparedStatement statement =
          con.prepareStatement("INSERT INTO frequency_batch VALUES (?)")) {
        statement.setInt(1, 1);
        statement.addBatch();
        assertArrayEquals(new int[] {1}, statement.executeBatch());
        assertEquals(prepared, status(con, "Com_stmt_prepare"));
        statement.setInt(1, 2);
        statement.addBatch();
        assertArrayEquals(new int[] {1}, statement.executeBatch());
        assertEquals(prepared + 1, status(con, "Com_stmt_prepare"));
      }
      try (PreparedStatement weighted =
          con.prepareStatement("INSERT INTO frequency_batch VALUES (? + 10)")) {
        for (int i = 0; i < 3; i++) {
          weighted.setInt(1, i);
          weighted.addBatch();
        }
        assertArrayEquals(new long[] {1, 1, 1}, weighted.executeLargeBatch());
        assertEquals(prepared + 2, status(con, "Com_stmt_prepare"));
      }
      try (java.sql.Statement query = con.createStatement();
          ResultSet rows = query.executeQuery("SELECT COUNT(*), SUM(v) FROM frequency_batch")) {
        assertTrue(rows.next());
        assertEquals(5, rows.getInt(1));
        assertEquals(36, rows.getInt(2));
      }
    }
  }

  @Test
  void callableStatementsKeepOutputParametersInFrequencyMode() throws SQLException {
    try (java.sql.Statement setup = sharedConn.createStatement()) {
      setup.execute("DROP PROCEDURE IF EXISTS frequency_output");
      setup.execute("CREATE PROCEDURE frequency_output(INOUT v INT) SET v = v * 2");
    }
    try (Connection con = createCon(FREQUENCY);
        java.sql.CallableStatement statement = con.prepareCall("{CALL frequency_output(?)}")) {
      statement.registerOutParameter(1, java.sql.Types.INTEGER);
      for (int i = 1; i <= 3; i++) {
        statement.setInt(1, i);
        statement.execute();
        assertEquals(i * 2, statement.getInt(1));
      }
    } finally {
      try (java.sql.Statement cleanup = sharedConn.createStatement()) {
        cleanup.execute("DROP PROCEDURE IF EXISTS frequency_output");
      }
    }
  }

  @Test
  void transactionReplayDoesNotCountColdExecutionsOnTheNewConnection() throws SQLException {
    try (java.sql.Statement setup = sharedConn.createStatement()) {
      setup.execute("DROP TABLE IF EXISTS frequency_replay");
      setup.execute("CREATE TABLE frequency_replay (v INT PRIMARY KEY) ENGINE=InnoDB");
    }
    try (Connection con =
        createProxyCon(HaMode.SEQUENTIAL, FREQUENCY + "&transactionReplay=true")) {
      con.setNetworkTimeout(Runnable::run, 10000);
      con.setAutoCommit(false);
      String sql = "INSERT INTO frequency_replay VALUES (?)";
      try (PreparedStatement statement = con.prepareStatement(sql)) {
        statement.setInt(1, 1);
        assertEquals(1, statement.executeUpdate());
      }
      long initialThread = con.getThreadId();
      con.setNetworkTimeout(Runnable::run, 500);
      proxy.restart(200);
      try (java.sql.Statement trigger = con.createStatement();
          ResultSet rows = trigger.executeQuery("SELECT 1")) {
        assertTrue(rows.next());
        assertEquals(1, rows.getInt(1));
      }
      assertNotEquals(initialThread, con.getThreadId());
      con.setNetworkTimeout(Runnable::run, 10000);
      long prepared = status(con, "Com_stmt_prepare");
      try (PreparedStatement statement = con.prepareStatement(sql)) {
        statement.setInt(1, 2);
        assertEquals(1, statement.executeUpdate());
        assertEquals(prepared, status(con, "Com_stmt_prepare"));
      }
      con.commit();
      try (java.sql.Statement query = con.createStatement();
          ResultSet rows = query.executeQuery("SELECT v FROM frequency_replay ORDER BY v")) {
        assertTrue(rows.next());
        assertEquals(1, rows.getInt(1));
        assertTrue(rows.next());
        assertEquals(2, rows.getInt(1));
        assertFalse(rows.next());
      }
    } finally {
      try (java.sql.Statement cleanup = sharedConn.createStatement()) {
        cleanup.execute("DROP TABLE IF EXISTS frequency_replay");
      }
    }
  }

  @Test
  void rejectedBatchDoesNotUseBinaryBulk() throws SQLException {
    try (Connection con = createCon(FREQUENCY + "&useBulkStmts=true")) {
      try (PreparedStatement first = con.prepareStatement("SELECT ? AS first_hot");
          PreparedStatement second = con.prepareStatement("SELECT ? AS second_hot")) {
        for (int i = 0; i < 6; i++) execute(first, i);
        for (int i = 0; i < 6; i++) execute(second, i);
      }
      try (java.sql.Statement setup = con.createStatement()) {
        setup.execute("CREATE TEMPORARY TABLE frequency_cold_batch (v INT)");
      }
      long prepared = status(con, "Com_stmt_prepare");
      try (PreparedStatement cold =
          con.prepareStatement("INSERT INTO frequency_cold_batch VALUES (?)")) {
        for (int i = 0; i < 2; i++) {
          cold.setInt(1, i);
          cold.addBatch();
        }
        assertArrayEquals(new int[] {1, 1}, cold.executeBatch());
      }
      assertEquals(prepared, status(con, "Com_stmt_prepare"));
    }
  }

  @Test
  void resetClearsLearningAndCachedHandles() throws SQLException {
    try (Connection con = createCon(FREQUENCY + "&useResetConnection=true")) {
      try (PreparedStatement statement = con.prepareStatement("SELECT ? AS reset_value")) {
        execute(statement, 1);
        execute(statement, 2);
      }
      con.reset();
      long prepared = status(con, "Com_stmt_prepare");
      try (PreparedStatement statement = con.prepareStatement("SELECT ? AS reset_value")) {
        execute(statement, 3);
        assertEquals(prepared, status(con, "Com_stmt_prepare"));
        execute(statement, 4);
        assertEquals(prepared + 1, status(con, "Com_stmt_prepare"));
      }
    }
  }

  @Test
  void streamsNullsAndSettingsSurviveProtocolChanges() throws SQLException {
    try (Connection con = createCon(FREQUENCY);
        PreparedStatement statement = con.prepareStatement("SELECT ? AS stream_value")) {
      statement.setQueryTimeout(2);
      statement.setMaxRows(1);
      long prepared = status(con, "Com_stmt_prepare");
      for (int i = 0; i < 3; i++) {
        String value = "value-'\\-" + i;
        statement.setCharacterStream(1, new StringReader(value));
        try (ResultSet rows = statement.executeQuery()) {
          assertSame(statement, rows.getStatement());
          assertTrue(rows.next());
          assertEquals(value, rows.getString(1));
          assertFalse(rows.next());
        }
        assertEquals(prepared + (i == 0 ? 0 : 1), status(con, "Com_stmt_prepare"));
      }
      statement.setNull(1, java.sql.Types.VARCHAR);
      try (ResultSet rows = statement.executeQuery()) {
        assertTrue(rows.next());
        assertNull(rows.getString(1));
      }
      assertEquals(2, statement.getQueryTimeout());
      assertEquals(1, statement.getMaxRows());
    }
  }

  @Test
  void generatedKeysSurviveProtocolChanges() throws SQLException {
    try (Connection con = createCon(FREQUENCY)) {
      try (java.sql.Statement setup = con.createStatement()) {
        setup.execute(
            "CREATE TEMPORARY TABLE frequency_keys (id INT AUTO_INCREMENT PRIMARY KEY, v INT)");
      }
      try (PreparedStatement statement =
          con.prepareStatement(
              "INSERT INTO frequency_keys(v) VALUES (?)",
              java.sql.Statement.RETURN_GENERATED_KEYS)) {
        for (int i = 1; i <= 3; i++) {
          statement.setInt(1, i);
          assertEquals(1, statement.executeUpdate());
          try (ResultSet keys = statement.getGeneratedKeys()) {
            assertTrue(keys.next());
            assertEquals(i, keys.getInt(1));
            assertFalse(keys.next());
          }
        }
      }
    }
  }

  @Test
  void prepareFailureKeepsTheSameStatementOwner() throws SQLException {
    try (Connection con = createCon(FREQUENCY + "&allowMultiQueries=true");
        PreparedStatement statement = con.prepareStatement("SELECT ?; SELECT ?")) {
      for (int i = 0; i < 3; i++) {
        statement.setInt(1, i);
        statement.setInt(2, i + 10);
        assertTrue(statement.execute());
        try (ResultSet first = statement.getResultSet()) {
          assertSame(statement, first.getStatement());
          assertTrue(first.next());
          assertEquals(i, first.getInt(1));
        }
        assertTrue(statement.getMoreResults());
        try (ResultSet second = statement.getResultSet()) {
          assertSame(statement, second.getStatement());
          assertTrue(second.next());
          assertEquals(i + 10, second.getInt(1));
        }
        assertFalse(statement.getMoreResults());
      }
    }
  }

  @Test
  void allStrategyKeepsExistingEagerPreparation() throws SQLException {
    try (Connection con = createCon("&useServerPrepStmts=true&serverPrepStmtStrategy=all");
        PreparedStatement statement = con.prepareStatement("SELECT ? AS legacy_value")) {
      long prepared = status(con, "Com_stmt_prepare");
      execute(statement, 1);
      assertEquals(prepared + 1, status(con, "Com_stmt_prepare"));
    }
  }

  private static void execute(PreparedStatement statement, int value) throws SQLException {
    statement.setInt(1, value);
    try (ResultSet rows = statement.executeQuery()) {
      assertSame(statement, rows.getStatement());
      assertTrue(rows.next());
      assertEquals(value, rows.getInt(1));
      assertFalse(rows.next());
    }
  }

  private static long status(Connection con, String name) throws SQLException {
    // This forced client-side statement must not prepare a handle or alter the measured counter.
    try (PreparedStatement query =
        con.prepareStatement("/*client prepare*/ SHOW SESSION STATUS LIKE ?")) {
      query.setString(1, name);
      try (ResultSet rows = query.executeQuery()) {
        assertTrue(rows.next(), name);
        return rows.getLong(2);
      }
    }
  }
}
