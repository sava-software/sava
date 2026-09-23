package software.sava.rpc.soak.issue52;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;

import static software.sava.rpc.soak.issue52.DeliveryFrames.FrameKind;

/// Wraps sava's per-attempt `AttemptListener`: substitutes the one `HarnessSocket` for the raw
/// socket in every callback (sava adopts the `onOpen` argument and compares identities), and
/// pushes a listener frame carrying this attempt's ordinal around each delegation, so a
/// retirement abort inside the callback lands in a frame that knows which attempt's listener
/// was running. The frame's ordinal is the *listener's* attempt; the abort records the
/// *retired* socket's, and the two differ on the `onPing`/`onPong` re-resolution path.
///
/// `onOpen` additionally records the adoption time and thread and which of the two "open"
/// signals arrived first, because on JDK 25 the build future settles on the handshake thread
/// before `onOpen` runs on the executor, and the #52 window depends on that order.
final class HarnessListener implements WebSocket.Listener {

  private final AttemptTracker tracker;
  private final AttemptTracker.Attempt attempt;
  final WebSocket.Listener sava;

  HarnessListener(final AttemptTracker tracker, final AttemptTracker.Attempt attempt, final WebSocket.Listener sava) {
    this.tracker = tracker;
    this.attempt = attempt;
    this.sava = sava;
  }

  @Override
  public void onOpen(final WebSocket webSocket) {
    final long now = System.nanoTime();
    final var frames = DeliveryFrames.of();
    final var wrapper = attempt.wrapperFor(webSocket);
    attempt.onOpenEntered(now, Thread.currentThread());
    final var frame = frames.push(tracker, FrameKind.ON_OPEN, attempt.ordinal, now);
    try {
      sava.onOpen(wrapper);
    } finally {
      frames.pop(frame);
      attempt.onOpenReturned(System.nanoTime());
    }
  }

  @Override
  public CompletionStage<?> onText(final WebSocket webSocket, final CharSequence data, final boolean last) {
    final var frames = DeliveryFrames.of();
    final var frame = frames.push(tracker, FrameKind.ON_TEXT, attempt.ordinal, 0L);
    try {
      return sava.onText(attempt.wrapperFor(webSocket), data, last);
    } finally {
      frames.pop(frame);
    }
  }

  @Override
  public CompletionStage<?> onBinary(final WebSocket webSocket, final ByteBuffer data, final boolean last) {
    final var frames = DeliveryFrames.of();
    final var frame = frames.push(tracker, FrameKind.ON_BINARY, attempt.ordinal, 0L);
    try {
      return sava.onBinary(attempt.wrapperFor(webSocket), data, last);
    } finally {
      frames.pop(frame);
    }
  }

  @Override
  public CompletionStage<?> onPing(final WebSocket webSocket, final ByteBuffer message) {
    final var frames = DeliveryFrames.of();
    final var frame = frames.push(tracker, FrameKind.ON_PING, attempt.ordinal, 0L);
    try {
      return sava.onPing(attempt.wrapperFor(webSocket), message);
    } finally {
      frames.pop(frame);
    }
  }

  @Override
  public CompletionStage<?> onPong(final WebSocket webSocket, final ByteBuffer message) {
    final var frames = DeliveryFrames.of();
    final var frame = frames.push(tracker, FrameKind.ON_PONG, attempt.ordinal, 0L);
    try {
      return sava.onPong(attempt.wrapperFor(webSocket), message);
    } finally {
      frames.pop(frame);
    }
  }

  @Override
  public CompletionStage<?> onClose(final WebSocket webSocket, final int statusCode, final String reason) {
    final var frames = DeliveryFrames.of();
    final var frame = frames.push(tracker, FrameKind.ON_CLOSE, attempt.ordinal, 0L);
    try {
      return sava.onClose(attempt.wrapperFor(webSocket), statusCode, reason);
    } finally {
      frames.pop(frame);
    }
  }

  @Override
  public void onError(final WebSocket webSocket, final Throwable error) {
    final var frames = DeliveryFrames.of();
    final var frame = frames.push(tracker, FrameKind.ON_ERROR, attempt.ordinal, 0L);
    try {
      sava.onError(attempt.wrapperFor(webSocket), error);
    } finally {
      frames.pop(frame);
    }
  }
}
