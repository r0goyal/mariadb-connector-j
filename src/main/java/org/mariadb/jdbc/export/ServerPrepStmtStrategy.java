// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (c) 2012-2014 Monty Program Ab
// Copyright (c) 2015-2026 MariaDB plc
package org.mariadb.jdbc.export;

/** Selection policy for new server-prepared handles. */
public enum ServerPrepStmtStrategy {
  /** Preserve the existing server-preparation behavior. */
  ALL("all"),
  /** Select SQL using connection-local execution frequency. */
  FREQUENCY("frequency");

  private final String value;

  ServerPrepStmtStrategy(String value) {
    this.value = value;
  }

  public static ServerPrepStmtStrategy from(String value) {
    for (ServerPrepStmtStrategy strategy : values()) {
      if (strategy.value.equalsIgnoreCase(value) || strategy.name().equalsIgnoreCase(value)) {
        return strategy;
      }
    }
    throw new IllegalArgumentException(
        String.format("Wrong argument value '%s' for ServerPrepStmtStrategy", value));
  }

  @Override
  public String toString() {
    return value;
  }
}
