package software.sava.rpc.json.http.client;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/// Captures what a class logs while an action runs, through the JUL backend the
/// `System.Logger` facade routes to — the same technique the ws suite uses for
/// its check-loop funnel. For the parse-failure tails here, the log line is the
/// only place the HTTP status and offending body are recorded (the rethrown
/// exception is the parser's own and knows nothing about the exchange), so the
/// diagnostic is part of the contract and is asserted, not assumed.
final class TestLogs {

  static List<LogRecord> capture(final Class<?> loggerClass, final Runnable action) {
    final var records = new ArrayList<LogRecord>();
    final var handler = new Handler() {
      @Override
      public void publish(final LogRecord record) {
        records.add(record);
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    };
    final var julLogger = Logger.getLogger(loggerClass.getName());
    final boolean parentHandlers = julLogger.getUseParentHandlers();
    julLogger.setUseParentHandlers(false);
    julLogger.addHandler(handler);
    try {
      action.run();
    } finally {
      julLogger.removeHandler(handler);
      julLogger.setUseParentHandlers(parentHandlers);
    }
    return records;
  }

  private TestLogs() {
  }
}
