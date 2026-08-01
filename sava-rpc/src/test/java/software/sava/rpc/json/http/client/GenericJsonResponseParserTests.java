package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import systems.comodal.jsoniter.JsonIterator;

import java.io.UncheckedIOException;
import java.net.UnknownServiceException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/// The non-RPC response gate. Endpoints that answer plain JSON rather than a
/// JSON-RPC envelope go through [BaseJsonResponseController]'s status-and-body
/// check instead of the envelope gate — same shape of verdict (result, garbage,
/// or nothing), but with no `result`/`error` scan. Driven with
/// [StubHttpResponse] literals like its sibling `JsonRpcResponseParserTests`.
final class GenericJsonResponseParserTests {

  private static byte[] json(final String body) {
    return body.getBytes(StandardCharsets.UTF_8);
  }

  private static Function<HttpResponse<?>, String> controller(final BiFunction<byte[], JsonIterator, String> parser) {
    return JsonHttpClient.applyGenericResponse(parser);
  }

  @Test
  void parserReceivesTheRawBodyAndAPositionedIterator() {
    final byte[] body = json("{\"ok\":true}");
    final var controller = controller((parsedBody, ji) -> {
      assertSame(body, parsedBody, "the raw body must be handed through");
      assertNotNull(ji.skipUntil("ok"), "the iterator must be positioned at the document start");
      return Boolean.toString(ji.readBoolean());
    });
    assertEquals("true", controller.apply(StubHttpResponse.of(200, body)));
  }

  /// A null body under a success status is an answered request with nothing in
  /// it; the parser is never consulted.
  @Test
  void nullBodyUnderASuccessStatusReturnsNull() {
    final var controller = controller((_, _) -> fail("the parser must not run without a body"));
    assertNull(controller.apply(StubHttpResponse.of(200, (byte[]) null)));
  }

  /// Like the envelope gate, the interval is [200, 300) — and 199 is
  /// constructible here even though the JDK client never surfaces one.
  @Test
  void nonSuccessStatusesAreRejectedWithStatusAndBody() {
    final var controller = controller((_, _) -> fail("the parser must not see a failed response"));
    for (final int status : new int[]{199, 300, 404, 500}) {
      final var ex = assertThrows(UncheckedIOException.class,
          () -> controller.apply(StubHttpResponse.of(status, json("{\"why\":\"down\"}"))), "status " + status);
      assertInstanceOf(UnknownServiceException.class, ex.getCause(), "status " + status);
      assertTrue(ex.getMessage().contains("httpCode:" + status), ex.getMessage());
      assertTrue(ex.getMessage().contains("down"), ex.getMessage());
    }
  }

  @Test
  void successStatusesAllParse() {
    final var controller = controller((_, ji) -> Integer.toString(ji.skipUntil("ok").readInt()));
    for (final int status : new int[]{200, 201, 299}) {
      assertEquals("1", controller.apply(StubHttpResponse.of(status, json("{\"ok\":1}"))), "status " + status);
    }
  }

  /// A failed status with no body still reports the status; the body slot in the
  /// message is simply empty.
  @Test
  void rejectionWithoutABodyReportsTheStatus() {
    final var controller = controller((_, _) -> fail("the parser must not see a failed response"));
    final var ex = assertThrows(UncheckedIOException.class,
        () -> controller.apply(StubHttpResponse.of(503, (byte[]) null)));
    assertTrue(ex.getMessage().contains("httpCode:503"), ex.getMessage());
    assertTrue(ex.getMessage().contains("body="), ex.getMessage());
  }

  /// A parser failure on a well-formed response escapes as itself — logged, but
  /// neither swallowed nor rewrapped.
  @Test
  void parserFailuresAreRethrownAsThemselves() {
    final var failure = new IllegalStateException("parser blew up");
    final var controller = controller((_, _) -> {
      throw failure;
    });
    final var thrown = assertThrows(IllegalStateException.class,
        () -> controller.apply(StubHttpResponse.of(200, json("{}"))));
    assertSame(failure, thrown);
  }
}
