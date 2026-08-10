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

  private boolean parentHandlers;

  @Override
  public void beforeAll(final ExtensionContext context) {
    final var logger = Logger.getLogger(SolanaJsonRpcWebsocket.class.getName());
    this.parentHandlers = logger.getUseParentHandlers();
    logger.setUseParentHandlers(false);
  }

  @Override
  public void afterAll(final ExtensionContext context) {
    Logger.getLogger(SolanaJsonRpcWebsocket.class.getName()).setUseParentHandlers(parentHandlers);
  }
}
