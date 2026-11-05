// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (c) 2012-2014 Monty Program Ab
// Copyright (c) 2015-2026 MariaDB plc
package org.mariadb.jdbc.util;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.mariadb.jdbc.Connection;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Measures the integrated driver against a dedicated local test database. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms256m", "-Xmx256m"})
@Threads(1)
public class PreparedStatementStrategyBenchmark {
  @State(Scope.Thread)
  public static class Queries {
    @Param({"all", "frequency"})
    public String strategy;

    @Param({"2048"})
    public int sqlBytes;

    private Connection connection;
    private PreparedStatement reusable;
    private String sql;
    private int sequence;

    @Setup(Level.Trial)
    public void setup() throws Exception {
      Properties properties = new Properties();
      properties.setProperty("user", System.getProperty("TEST_USERNAME", "root"));
      properties.setProperty("password", System.getProperty("TEST_PASSWORD", ""));
      properties.setProperty("useServerPrepStmts", "true");
      properties.setProperty("cachePrepStmts", "true");
      properties.setProperty("prepStmtCacheSize", "100");
      properties.setProperty("serverPrepStmtStrategy", strategy);
      properties.setProperty("serverPrepStmtWindowSize", "5000");
      properties.setProperty("sslMode", "disable");
      properties.setProperty("connectTimeout", "5000");
      properties.setProperty("socketTimeout", "30000");
      String url = "jdbc:mariadb://" + System.getProperty("TEST_HOST", "localhost") + ":"
          + System.getProperty("TEST_PORT", "3306") + "/"
          + System.getProperty("TEST_DATABASE", "testj");
      connection = new org.mariadb.jdbc.Driver().connect(url, properties);
      String prefix = "SELECT ? /* ";
      sql = prefix + "x".repeat(sqlBytes - prefix.length() - 2) + "*/";
      reusable = connection.prepareStatement(sql);
      execute(reusable, 1);
      execute(reusable, 2);
    }

    @TearDown(Level.Trial)
    public void close() throws SQLException {
      try {
        reusable.close();
      } finally {
        connection.close();
      }
    }
  }

  @Benchmark
  public PreparedStatement constructBindAndClose(Queries state) throws SQLException {
    PreparedStatement statement = state.connection.prepareStatement(state.sql);
    try (statement) {
      statement.setInt(1, state.sequence++);
    }
    return statement;
  }

  @Benchmark
  public int reusedStatement(Queries state) throws SQLException {
    return execute(state.reusable, state.sequence++);
  }

  @Benchmark
  public int prepareAndClose(Queries state) throws SQLException {
    try (PreparedStatement statement = state.connection.prepareStatement(state.sql)) {
      return execute(statement, state.sequence++);
    }
  }

  private static int execute(PreparedStatement statement, int value) throws SQLException {
    statement.setInt(1, value);
    try (ResultSet rows = statement.executeQuery()) {
      if (!rows.next()) throw new IllegalStateException("Missing benchmark result");
      return rows.getInt(1);
    }
  }
}
