// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (c) 2012-2014 Monty Program Ab
// Copyright (c) 2015-2026 MariaDB plc
package org.mariadb.jdbc;

import java.sql.BatchUpdateException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import org.mariadb.jdbc.client.Context;
import org.mariadb.jdbc.message.ClientMessage;
import org.mariadb.jdbc.message.client.QueryWithParametersPacket;
import org.mariadb.jdbc.util.ClientParser;
import org.mariadb.jdbc.util.constants.ServerStatus;

/** Text protocol operations on the caller's statement state. This is not a JDBC statement. */
final class PreparedTextExecution {
  private PreparedTextExecution() {}

  static ClientParser parser(String sql, Context context) {
    boolean noBackslashEscapes =
        (context.getServerStatus() & ServerStatus.NO_BACKSLASH_ESCAPES) > 0;
    return context.getConf().rewriteBatchedStatements()
        ? ClientParser.rewritableParts(sql, noBackslashEscapes)
        : ClientParser.parameterParts(sql, noBackslashEscapes);
  }

  static void execute(BasePreparedStatement owner, ClientParser parser) throws SQLException {
    QueryWithParametersPacket query =
        new QueryWithParametersPacket(
            owner.preSqlCmd(), parser, owner.parameters, owner.localInfileInputStream);
    owner.results =
        owner
            .con
            .getClient()
            .execute(
                query,
                owner,
                owner.fetchSize,
                owner.maxRows,
                owner.resultSetConcurrency,
                owner.resultSetType,
                owner.closeOnCompletion,
                false);
  }

  static void executeBatchPipeline(BasePreparedStatement owner, ClientParser parser)
      throws SQLException {
    ClientMessage[] packets = new ClientMessage[owner.batchParameters.size()];
    String prefix = owner.preSqlCmd();
    for (int i = 0; i < packets.length; i++) {
      packets[i] =
          new QueryWithParametersPacket(prefix, parser, owner.batchParameters.get(i), null);
    }
    try {
      owner.results =
          owner
              .con
              .getClient()
              .executePipeline(
                  packets,
                  owner,
                  0,
                  owner.maxRows,
                  ResultSet.CONCUR_READ_ONLY,
                  ResultSet.TYPE_FORWARD_ONLY,
                  owner.closeOnCompletion,
                  false);
    } catch (SQLException e) {
      owner.results = null;
      throw e;
    }
  }

  static void executeBatchStandard(BasePreparedStatement owner, ClientParser parser)
      throws SQLException {
    try {
      owner.results = new ArrayList<>();
      String prefix = owner.preSqlCmd();
      for (var parameters : owner.batchParameters) {
        owner.results.addAll(
            owner
                .con
                .getClient()
                .execute(
                    new QueryWithParametersPacket(
                        prefix, parser, parameters, owner.localInfileInputStream),
                    owner,
                    0,
                    owner.maxRows,
                    ResultSet.CONCUR_READ_ONLY,
                    ResultSet.TYPE_FORWARD_ONLY,
                    owner.closeOnCompletion,
                    false));
      }
    } catch (SQLException e) {
      BatchUpdateException exception =
          owner
              .con
              .getExceptionFactory()
              .of(owner)
              .createBatchUpdate(owner.results, owner.batchParameters.size(), e);
      owner.results = null;
      owner.localInfileInputStream = null;
      throw exception;
    }
  }
}
