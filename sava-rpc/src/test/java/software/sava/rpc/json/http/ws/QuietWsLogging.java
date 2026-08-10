package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.logging.Logger;

/// Keeps the console clean while a suite drives failures on purpose.
///
/// Most of what this package tests is what the engine does when something goes wrong — a
/// consumer throwing, a send failing, a peer sending garbage — and the engine's answer is
/// frequently a log line carrying the throwable. JUL's default console handler then prints the
/// whole stack trace, so a fully passing run scrolls with traces that look like failures and
/// bury the ones that are. Silencing them is not hiding evidence: no assertion reads the
/// console, and the diagnostics that ARE the only record of an event are asserted directly
/// through the JUL backend by [WsDiagnosticLogTests], which installs its own handler and is
/// unaffected by this.
///
/// Class-scoped rather than global: a suite opts in by declaring it, so a new suite's
/// unexpected noise still reaches the console until someone decides it is expected.
final class QuietWsLogging implements BeforeAllCallback, AfterAllCallback {

  /// Held, not re-fetched. `LogManager` keeps loggers by WEAK reference, so a configured
  /// logger nobody holds can be collected between the setting and the logging — after which
  /// `System.getLogger` hands the engine a fresh one with `useParentHandlers` back to true and
  /// the traces return. The field is the strong reference that makes the setting stick.
  private Logger logger;
  private boolean parentHandlers;

  @Override
  public void beforeAll(final ExtensionContext context) {
    this.logger = Logger.getLogger(SolanaJsonRpcWebsocket.class.getName());
    this.parentHandlers = logger.getUseParentHandlers();
    logger.setUseParentHandlers(false);
  }

  @Override
  public void afterAll(final ExtensionContext context) {
    logger.setUseParentHandlers(parentHandlers);
    this.logger = null;
  }
}
