package software.sava.rpc.json.http.ws;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.token.TokenAccount;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.*;
import systems.comodal.jsoniter.CharBufferFunction;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.lang.System.Logger.Level.*;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.joining;
import static software.sava.rpc.json.http.response.AccountInfo.BYTES_IDENTITY;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

final class SolanaJsonRpcWebsocket implements WebSocket.Listener, SolanaRpcWebsocket {

  private static final System.Logger log = System.getLogger(SolanaJsonRpcWebsocket.class.getName());

  private final URI wsUri;
  private final SolanaAccounts solanaAccounts;
  private final Commitment defaultCommitment;
  private final Timings timings;
  private final WebSocket.Builder webSocketBuilder;
  private final ScheduledExecutorService executorService;
  private final Consumer<SolanaRpcWebsocket> onOpen;
  private final OnClose onClose;
  private final BiConsumer<SolanaRpcWebsocket, Throwable> onError;
  private final AtomicLong msgId;
  private final Map<Long, Subscription<?>> pendingSubscriptions;
  private final Map<Long, String> pendingUnsubscriptions;
  private final Map<String, Map<Commitment, Subscription<AccountInfo<byte[]>>>> accountSubs;
  private final Map<String, Map<Commitment, Subscription<TxLogs>>> txLogSubs;
  private final Map<String, Map<Commitment, Subscription<TxResult>>> signatureSubs;
  private final Map<String, Map<Commitment, Subscription<AccountInfo<byte[]>>>> programSubs;
  private final Set<Consumer<RuntimeException>> exceptionSubs;
  private final AtomicReference<Subscription<ProcessedSlot>> slotSub;
  private final Map<Long, Subscription<?>> subscriptionsBySubId;

  private final AtomicLong lastOutGoing;
  // private volatile long lastIncoming; // TODO: if more than N seconds send close.
  private volatile WebSocket webSocket;

  private char[] buffer;
  private int offset;
  private final JsonIterator ji;

  SolanaJsonRpcWebsocket(final URI wsUri,
                         final SolanaAccounts solanaAccounts,
                         final Commitment defaultCommitment,
                         final WebSocket.Builder webSocketBuilder,
                         final Timings timings,
                         final Consumer<SolanaRpcWebsocket> onOpen,
                         final OnClose onClose,
                         final BiConsumer<SolanaRpcWebsocket, Throwable> onError) {
    this.wsUri = wsUri;
    this.solanaAccounts = solanaAccounts;
    this.defaultCommitment = defaultCommitment;
    this.timings = timings;
    this.webSocketBuilder = webSocketBuilder;
    this.onOpen = onOpen;
    this.onClose = onClose;
    this.onError = onError;
    this.msgId = new AtomicLong(1);
    this.lastOutGoing = new AtomicLong(0);
    this.pendingSubscriptions = new ConcurrentSkipListMap<>();
    this.pendingUnsubscriptions = new ConcurrentSkipListMap<>();
    this.accountSubs = new ConcurrentSkipListMap<>();
    this.txLogSubs = new ConcurrentSkipListMap<>();
    this.signatureSubs = new ConcurrentSkipListMap<>();
    this.programSubs = new ConcurrentSkipListMap<>();
    this.slotSub = new AtomicReference<>();
    this.subscriptionsBySubId = new ConcurrentSkipListMap<>();
    this.exceptionSubs = HashSet.newHashSet(1);
    this.executorService = Executors.newScheduledThreadPool(1);
    this.executorService.scheduleWithFixedDelay(this::subOrPing,
        timings.subscriptionAndPingCheckDelay() << 1, timings.subscriptionAndPingCheckDelay(), MILLISECONDS);
    this.buffer = new char[4_096];
    this.ji = JsonIterator.parse(new byte[0]);
  }

  @Override
  public void connect() {
    if (this.msgId.get() >= 0) {
      this.webSocket = null;
      final long now = System.currentTimeMillis();
      final long millisSinceLastWrite = now - this.lastOutGoing.get();
      if (millisSinceLastWrite < timings.reConnect()) {
        this.executorService.schedule(this::connect, this.timings.reConnect() - millisSinceLastWrite, MILLISECONDS);
      } else {
        this.lastOutGoing.set(System.currentTimeMillis());
        this.webSocketBuilder.buildAsync(this.wsUri, this);
      }
    }
  }

  @Override
  public void exceptionSubscribe(final Consumer<RuntimeException> consumer) {
    this.exceptionSubs.add(consumer);
  }

  private void queuePendingSubsOnOpen(final Map<String, ? extends Map<Commitment, ? extends Subscription<?>>> subs) {
    for (final var subscriptions : subs.values()) {
      for (final var sub : subscriptions.values()) {
        this.pendingSubscriptions.put(sub.msgId(), sub);
      }
    }
  }

  @Override
  public void onOpen(final WebSocket webSocket) {
    this.offset = 0;
    this.pendingSubscriptions.clear();
    queuePendingSubsOnOpen(this.accountSubs);
    queuePendingSubsOnOpen(this.txLogSubs);
    queuePendingSubsOnOpen(this.signatureSubs);
    queuePendingSubsOnOpen(this.programSubs);
    final var slotSub = this.slotSub.get();
    if (slotSub != null) {
      this.pendingSubscriptions.put(slotSub.msgId(), slotSub);
    }
    this.pendingUnsubscriptions.clear();
    this.subscriptionsBySubId.clear();
    handlePendingSubscriptions(webSocket);
    // this.lastIncoming = System.currentTimeMillis();
    webSocket.request(Long.MAX_VALUE);
    this.webSocket = webSocket;
    log.log(INFO, "WebSocket connected to {0}.", wsUri);
    if (onOpen != null) {
      onOpen.accept(this);
    }
  }

  private static String createSubscriptionMsg(final long msgId,
                                              final Channel channel,
                                              final String params) {
    return String.format("""
        {"jsonrpc":"2.0","id":%d,"method":"%s","params":[%s]}""", msgId, channel.subscribe(), params);
  }

  private <T> boolean queueSubscription(final Commitment commitment,
                                        final Channel channel,
                                        final String key,
                                        final String params,
                                        final Map<String, Map<Commitment, Subscription<T>>> subs,
                                        final Consumer<T> consumer) {
    final long msgId = this.msgId.incrementAndGet();
    final var msg = createSubscriptionMsg(msgId, channel, params);
    final var sub = Subscription.createSubscription(commitment, channel, key, msgId, msg, consumer);
    final var duplicate = subs.computeIfAbsent(sub.key(), _ -> new EnumMap<>(Commitment.class)).putIfAbsent(commitment, sub);
    if (duplicate == null) {
      this.pendingSubscriptions.put(msgId, sub);
      // System.out.println(msg);
      return true;
    } else {
      return false;
    }
  }

  private <T> boolean queueSubscription(final Commitment commitment,
                                        final Channel channel,
                                        final PublicKey publicKey,
                                        final String params,
                                        final Map<String, Map<Commitment, Subscription<T>>> subs,
                                        final Consumer<T> consumer) {
    final long msgId = this.msgId.incrementAndGet();
    final var msg = createSubscriptionMsg(msgId, channel, params);
    final var sub = Subscription.createAccountSubscription(commitment, channel, publicKey, msgId, msg, consumer);
    final var duplicate = subs.computeIfAbsent(sub.key(), _ -> new EnumMap<>(Commitment.class)).putIfAbsent(commitment, sub);
    if (duplicate == null) {
      this.pendingSubscriptions.put(msgId, sub);
      // System.out.println(msg);
      return true;
    } else {
      return false;
    }
  }

  private void queueUnsubscribe(final Subscription<?> sub) {
    this.pendingSubscriptions.remove(sub.msgId());
    final long subId = sub.subId();
    if (subId >= 0) {
      this.subscriptionsBySubId.remove(subId);
      final var msg = createUnSubMsg(sub.channel(), subId);
      this.pendingUnsubscriptions.put(subId, msg);
    }
  }

  private boolean removeDanglingSub(final String key,
                                    final Channel channel,
                                    final Commitment commitment) {
    final var iterator = this.subscriptionsBySubId.entrySet().iterator();
    while (iterator.hasNext()) {
      final var activeSub = iterator.next().getValue();
      if (activeSub.channel() == channel && activeSub.commitment() == commitment && activeSub.key().equals(key)) {
        iterator.remove();
        this.queueUnsubscribe(activeSub);
        return true;
      }
    }
    return false;
  }

  private boolean queueUnsubscribe(final String key,
                                   final Channel channel,
                                   final Commitment commitment,
                                   final Map<String, ? extends Map<Commitment, ? extends Subscription<?>>> subs) {
    final var commitmentSubs = subs.get(key);
    if (commitmentSubs == null) {
      return removeDanglingSub(key, channel, commitment);
    } else {
      final var sub = commitmentSubs.remove(commitment);
      if (sub == null) {
        return removeDanglingSub(key, channel, commitment);
      } else {
        subs.compute(key, (_, v) -> v == null || v.isEmpty() ? null : v);
        this.queueUnsubscribe(sub);
        return true;
      }
    }
  }

  @Override
  public boolean accountSubscribe(final PublicKey key, final Consumer<AccountInfo<byte[]>> consumer) {
    return accountSubscribe(this.defaultCommitment, key, consumer);
  }

  @Override
  public boolean accountSubscribe(final Commitment commitment,
                                  final PublicKey key,
                                  final Consumer<AccountInfo<byte[]>> consumer) {
    final var sub = this.accountSubs.get(key.toBase58());
    if (sub == null || !sub.containsKey(commitment)) {
      final var params = String.format("""
          "%s",{"encoding":"base64","commitment":"%s"}""", key, commitment.getValue());
      return queueSubscription(commitment, Channel.account, key, params, this.accountSubs, consumer);
    } else {
      return false;
    }
  }

  @Override
  public boolean accountUnsubscribe(final PublicKey key) {
    return accountUnsubscribe(this.defaultCommitment, key);
  }

  @Override
  public boolean accountUnsubscribe(final Commitment commitment, final PublicKey key) {
    return queueUnsubscribe(key.toBase58(), Channel.account, commitment, this.accountSubs);
  }

  @Override
  public boolean logsSubscribe(final PublicKey key, final Consumer<TxLogs> consumer) {
    return logsSubscribe(this.defaultCommitment, key, consumer);
  }

  @Override
  public boolean logsSubscribe(final Commitment commitment,
                               final PublicKey key,
                               final Consumer<TxLogs> consumer) {
    final var sub = this.txLogSubs.get(key.toBase58());
    if (sub == null || !sub.containsKey(commitment)) {
      final var params = String.format("""
          {"mentions":["%s"]},{"commitment":"%s"}""", key, commitment.getValue());
      return queueSubscription(commitment, Channel.logs, key.toBase58(), params, this.txLogSubs, consumer);
    } else {
      return false;
    }
  }

  @Override
  public boolean logsUnsubscribe(final PublicKey key) {
    return logsUnsubscribe(this.defaultCommitment, key);
  }

  @Override
  public boolean logsUnsubscribe(final Commitment commitment, final PublicKey key) {
    return queueUnsubscribe(key.toBase58(), Channel.logs, commitment, this.txLogSubs);
  }

  @Override
  public boolean signatureSubscribe(final String b58TxSig, final Consumer<TxResult> consumer) {
    return signatureSubscribe(this.defaultCommitment, b58TxSig, consumer);
  }

  @Override
  public boolean signatureSubscribe(final String b58TxSig,
                                    final boolean enableReceivedNotification,
                                    final Consumer<TxResult> consumer) {
    return signatureSubscribe(this.defaultCommitment, enableReceivedNotification, b58TxSig, consumer);
  }

  @Override
  public boolean signatureSubscribe(final Commitment commitment,
                                    final boolean enableReceivedNotification,
                                    final String b58TxSig,
                                    final Consumer<TxResult> consumer) {
    final var sub = this.signatureSubs.get(b58TxSig);
    if (sub == null || !sub.containsKey(commitment)) {
      final var params = String.format("""
          "%s",{"commitment":"%s","enableReceivedNotification":%b}""", b58TxSig, commitment.getValue(), enableReceivedNotification);
      return queueSubscription(commitment, Channel.signature, b58TxSig, params, this.signatureSubs, consumer);
    } else {
      return false;
    }
  }

  @Override
  public boolean signatureUnsubscribe(final String b58TxSig) {
    return signatureUnsubscribe(this.defaultCommitment, b58TxSig);
  }

  @Override
  public boolean signatureUnsubscribe(final Commitment commitment, final String b58TxSig) {
    return queueUnsubscribe(b58TxSig, Channel.signature, commitment, this.signatureSubs);
  }

  @Override
  public boolean subscribeToTokenAccount(final PublicKey tokenMint,
                                         final PublicKey ownerAddress,
                                         final Consumer<AccountInfo<byte[]>> consumer) {
    return subscribeToTokenAccount(this.defaultCommitment, tokenMint, ownerAddress, consumer);
  }

  @Override
  public boolean subscribeToTokenAccount(final Commitment commitment,
                                         final PublicKey tokenMint,
                                         final PublicKey ownerAddress,
                                         final Consumer<AccountInfo<byte[]>> consumer) {
    return programSubscribe(
        commitment,
        solanaAccounts.tokenProgram(),
        List.of(
            TokenAccount.TOKEN_ACCOUNT_SIZE_FILTER,
            TokenAccount.createMintFilter(tokenMint),
            TokenAccount.createOwnerFilter(ownerAddress)
        ),
        consumer
    );
  }

  @Override
  public boolean subscribeToTokenAccounts(final PublicKey ownerAddress, final Consumer<AccountInfo<byte[]>> consumer) {
    return subscribeToTokenAccounts(this.defaultCommitment, ownerAddress, consumer);
  }

  @Override
  public boolean subscribeToTokenAccounts(final Commitment commitment,
                                          final PublicKey ownerAddress,
                                          final Consumer<AccountInfo<byte[]>> consumer) {
    return programSubscribe(
        commitment,
        solanaAccounts.tokenProgram(),
        List.of(
            TokenAccount.TOKEN_ACCOUNT_SIZE_FILTER,
            TokenAccount.createOwnerFilter(ownerAddress)
        ),
        consumer
    );
  }

  @Override
  public boolean programSubscribe(final PublicKey program, final Consumer<AccountInfo<byte[]>> consumer) {
    return programSubscribe(this.defaultCommitment, program, List.of(), consumer);
  }

  @Override
  public boolean programSubscribe(final PublicKey program,
                                  final List<Filter> filters,
                                  final Consumer<AccountInfo<byte[]>> consumer) {
    return programSubscribe(this.defaultCommitment, program, filters, consumer);
  }

  @Override
  public boolean programSubscribe(final Commitment commitment,
                                  final PublicKey program,
                                  final List<Filter> filters,
                                  final Consumer<AccountInfo<byte[]>> consumer) {
    final var sub = this.programSubs.get(program.toBase58());
    if (sub == null || !sub.containsKey(commitment)) {
      final var filtersJson = filters.isEmpty() ? "" : filters.stream()
          .map(Filter::toJson)
          .collect(joining(",", ",\"filters\":[", "]"));

      final var params = String.format("""
              "%s",{"commitment":"%s","encoding":"base64"%s}""",
          program, commitment.getValue(), filtersJson);
      return queueSubscription(commitment, Channel.program, program, params, this.programSubs, consumer);
    } else {
      return false;
    }
  }

  @Override
  public boolean programUnsubscribe(final PublicKey program) {
    return programUnsubscribe(this.defaultCommitment, program);
  }

  @Override
  public boolean programUnsubscribe(final Commitment commitment, final PublicKey program) {
    return queueUnsubscribe(program.toBase58(), Channel.program, commitment, this.programSubs);
  }

  @Override
  public boolean slotSubscribe(final Consumer<ProcessedSlot> consumer) {
    final long msgId = this.msgId.incrementAndGet();
    final var msg = String.format("""
        {"jsonrpc":"2.0","id":%d,"method":"%s"}""", msgId, Channel.slot.subscribe());
    final var slotSub = Subscription.createSubscription(null, Channel.slot, Channel.slot.name(), msgId, msg, consumer);
    if (this.slotSub.compareAndSet(null, slotSub)) {
      this.pendingSubscriptions.put(msgId, slotSub);
      return true;
    } else {
      return false;
    }
  }

  @Override
  public boolean slotUnsubscribe() {
    final var slotSub = this.slotSub.getAndSet(null);
    if (slotSub == null) {
      return false;
    } else {
      this.queueUnsubscribe(slotSub);
      return true;
    }
  }

  private static final CharBufferFunction<Channel> METHOD_PARSER = (buf, offset, len) -> {
    if (fieldEquals("accountNotification", buf, offset, len)) {
      return Channel.account;
    } else if (fieldEquals("signatureNotification", buf, offset, len)) {
      return Channel.signature;
    } else if (fieldEquals("programNotification", buf, offset, len)) {
      return Channel.program;
    } else if (fieldEquals("logsNotification", buf, offset, len)) {
      return Channel.logs;
    } else if (fieldEquals("slotNotification", buf, offset, len)) {
      return Channel.slot;
    } else {
      return null;
    }
  };

  private String createUnSubMsg(final Channel channel, long subId) {
    return String.format("""
            {"jsonrpc":"2.0","id":%d,"method":"%s","params":[%d]}""",
        this.msgId.incrementAndGet(), channel.unSubscribe(), subId);
  }

  private void sendText(final WebSocket webSocket, final String msg) {
    webSocket.sendText(msg, true);
    log.log(DEBUG, msg);
  }

  private void sendUnSubscription(final WebSocket webSocket,
                                  final Channel channel,
                                  final long subId) {
    final var msg = this.pendingUnsubscriptions.remove(subId);
    sendText(webSocket, msg == null ? createUnSubMsg(channel, subId) : msg);
  }

  private <T> void publish(final WebSocket webSocket,
                           final Channel channel,
                           final JsonIterator ji,
                           final int paramsMark,
                           final T item) {
    ji.skipRestOfObject();
    if (ji.skipUntil("subscription") == null) {
      ji.reset(paramsMark).skipUntil("subscription");
    }
    final long subId = ji.readLong();
    @SuppressWarnings("unchecked") final var sub = ((Subscription<T>) this.subscriptionsBySubId.get(subId));
    if (sub == null) {
      sendUnSubscription(webSocket, channel, subId);
    } else {
      sub.accept(item);
    }
  }

  private <T> void publish(final WebSocket webSocket,
                           final Channel channel,
                           final JsonIterator ji,
                           final int paramsMark,
                           final Function<Subscription<T>, T> factory) {
    final int mark = ji.mark();
    ji.skipRestOfObject();
    if (ji.skipUntil("subscription") == null) {
      ji.reset(paramsMark).skipUntil("subscription");
    }
    final long subId = ji.readLong();
    @SuppressWarnings("unchecked") final var sub = ((Subscription<T>) this.subscriptionsBySubId.get(subId));
    if (sub == null) {
      sendUnSubscription(webSocket, channel, subId);
    } else {
      ji.reset(mark);
      sub.accept(factory.apply(sub));
    }
  }

  private void onWholeMessage(final char[] msg,
                              final int offset,
                              final int tail,
                              final JsonIterator ji,
                              final WebSocket webSocket) {
    // System.out.format("<- %s%n", new String(msg, offset, tail - offset));
    try {
      if (ji.skipUntil("method") == null) {
        if (ji.reset(offset).skipUntil("error") != null) {
          final var exception = JsonRpcException.parseException(ji, OptionalLong.empty());
          final var message = exception.getMessage();
          if (message == null || !message.startsWith("Invalid subscription id")) {
            for (final var sub : this.exceptionSubs) {
              sub.accept(exception);
            }
          }
        } else {
          final var sub = SubConfirmation.parse(ji.reset(offset));
          if (sub.subId() > 0) {
            final var pendingSub = this.pendingSubscriptions.remove(sub.msgId());
            if (pendingSub != null) {
              pendingSub.setSubId(sub.subId());
              this.subscriptionsBySubId.put(sub.subId(), pendingSub);
            }
          } else if (sub.jsonRpcException() != null) {
            if (sub.jsonRpcException().code() != -32602) {  // May happen due to stale/duplicate un-subscription requests.
              log.log(WARNING, "Unexpected json rpc error.", sub.jsonRpcException());
            }
          }
        }
      } else {
        final var channel = ji.applyChars(METHOD_PARSER);
        if (channel != null) {
          ji.skipUntil("params");
          if (channel == Channel.slot) {
            final var slotSub = this.slotSub.get();
            if (slotSub == null) {
              final long subId = ji.skipUntil("subscription").readLong();
              sendUnSubscription(webSocket, channel, subId);
            } else {
              ji.skipUntil("result");
              final var slot = ProcessedSlot.parse(ji);
              slotSub.accept(slot);
            }
          } else {
            log.log(DEBUG, () -> new String(msg, offset, tail - offset));
            final int paramsMark = ji.mark();
            ji.skipUntil("result");

            final int resultMark = ji.mark();
            ji.skipUntil("context");
            final var context = Context.parse(ji);
            if (ji.skipUntil("value") == null) {
              ji.reset(resultMark).skipUntil("value");
            }
            switch (channel) {
              case account ->
                  publish(webSocket, channel, ji, paramsMark, sub -> AccountInfo.parse(sub.publicKey(), ji, context, AccountInfo.BYTES_IDENTITY));
              case logs -> publish(webSocket, channel, ji, paramsMark, TxLogs.parse(ji, context));
              case program ->
                  publish(webSocket, channel, ji, paramsMark, AccountInfo.parseAccount(ji, context, BYTES_IDENTITY));
              case signature -> {
                final var result = TxResult.parseResult(ji, context);
                ji.skipRestOfObject();
                if (ji.skipUntil("subscription") == null) {
                  ji.reset(paramsMark).skipUntil("subscription");
                }
                final long subId = ji.readLong();
                @SuppressWarnings("unchecked") final var sub = (Subscription<TxResult>) this.subscriptionsBySubId.get(subId);
                if (sub == null) {
                  sendUnSubscription(webSocket, channel, subId);
                } else {
                  if (result != null) {
                    sub.accept(result);
                    if (result.value() == null) {
                      this.subscriptionsBySubId.remove(subId);
                    }
                  }
                }
              }
              default -> { // ignored.
              }
            }
          }
        }
        handlePendingSubscriptions(webSocket);
      }
    } catch (final RuntimeException ex) {
      log.log(WARNING, "Unexpected json rpc error.", ex);
      for (final var sub : this.exceptionSubs) {
        sub.accept(ex);
      }
    }
  }

  private void ensureCapacity(final int minCapacity) {
    if (minCapacity - this.buffer.length > 0) {
      final int newCapacity = (this.buffer.length << 1) + 2;
      this.buffer = Arrays.copyOf(this.buffer, Math.max(newCapacity, minCapacity));
    }
  }

  @Override
  public CompletionStage<?> onText(final WebSocket webSocket, final CharSequence message, final boolean last) {
    final var buf = (CharBuffer) message;
    final int len = message.length();
    if (last) {
      // this.lastIncoming = System.currentTimeMillis();
      if (this.offset > 0) {
        final int to = this.offset + len;
        ensureCapacity(to);
        if (buf.hasArray()) {
          System.arraycopy(buf.array(), buf.position() + buf.arrayOffset(), this.buffer, this.offset, len);
        } else {
          buf.get(this.buffer, this.offset, len);
        }
        onWholeMessage(this.buffer, 0, to, this.ji.reset(this.buffer, 0, to), webSocket);
        this.offset = 0;
      } else {
        if (buf.hasArray()) {
          final int offset = buf.position() + buf.arrayOffset();
          final int to = offset + len;
          final char[] bufArray = buf.array();
          onWholeMessage(bufArray, offset, to, this.ji.reset(bufArray, offset, to), webSocket);
        } else {
          ensureCapacity(len);
          buf.get(this.buffer, 0, len);
          onWholeMessage(this.buffer, 0, len, this.ji.reset(this.buffer, 0, len), webSocket);
        }
      }
    } else {
      ensureCapacity(this.offset + len);
      if (buf.hasArray()) {
        System.arraycopy(buf.array(), buf.position() + buf.arrayOffset(), this.buffer, this.offset, len);
      } else {
        buf.get(this.buffer, this.offset, len);
      }
      this.offset += len;
    }
    return null;
  }

  @Override
  public CompletionStage<?> onBinary(final WebSocket webSocket, final ByteBuffer data, final boolean last) {
    throw new UnsupportedOperationException();
  }

  private void subOrPing() {
    try {
      final var webSocket = this.webSocket;
      if (webSocket != null) {
        handlePendingSubscriptions(webSocket);
      }
    } catch (final RuntimeException ex) {
      log.log(WARNING, "Unexpected exception handlePendingSubscriptions.", ex);
    }
  }

  private boolean noPendingUnSubscriptions(final WebSocket webSocket) {
    int numUnSubs = 0;
    final var iterator = this.pendingUnsubscriptions.entrySet().iterator();
    for (; iterator.hasNext(); ++numUnSubs) {
      sendText(webSocket, iterator.next().getValue());
      iterator.remove();
    }
    return numUnSubs <= 0;
  }

  private void handlePendingSubscriptions(final WebSocket webSocket) {
    if (this.pendingSubscriptions.isEmpty()) {
      if (noPendingUnSubscriptions(webSocket)) {
        sendPing(webSocket);
      }
    } else {
      final long now = System.currentTimeMillis();
      final long lastOutGoing = this.lastOutGoing.getAndSet(now);
      int numSubs = 0;
      for (final var sub : this.pendingSubscriptions.values()) {
        if (now - sub.lastAttempt() > this.timings.reConnect()) {
          sub.setLastAttempt(now);
          sendText(webSocket, sub.msg());
          ++numSubs;
        }
      }
      if (noPendingUnSubscriptions(webSocket) && numSubs == 0) {
        if (this.lastOutGoing.compareAndSet(now, lastOutGoing)) {
          sendPing(webSocket);
        }
      }
    }
  }

  @Override
  public CompletionStage<?> onPing(final WebSocket webSocket, final ByteBuffer message) {
    final long now = System.currentTimeMillis();
    // this.lastIncoming = now;
    this.lastOutGoing.set(now);
    log.log(INFO, new String(message.array()));
    handlePendingSubscriptions(webSocket);
    return null;
  }

  @Override
  public CompletionStage<?> onPong(final WebSocket webSocket, final ByteBuffer message) {
    // this.lastIncoming = System.currentTimeMillis();
    handlePendingSubscriptions(webSocket);
    log.log(DEBUG, () -> new String(message.array()));
    return null;
  }

  private void sendPing(final WebSocket webSocket) {
    final long now = System.currentTimeMillis();
    final long millisSinceLastWrite = now - this.lastOutGoing.get();
    if (millisSinceLastWrite > timings.writeOrPingDelay()) {
      this.lastOutGoing.set(now);
      final var pingMsg = String.valueOf(now);
      webSocket.sendPing(ByteBuffer.wrap(pingMsg.getBytes(ISO_8859_1))).whenComplete(((_, throwable) -> {
        if (throwable != null) {
          this.lastOutGoing.compareAndSet(now, System.currentTimeMillis() - (this.timings.writeOrPingDelay() - this.timings.reConnect()));
          log.log(WARNING, String.format("Failed to ping %d to %s.", now, this.wsUri), throwable);
        }
      }));
      log.log(DEBUG, "{0} to {1}.\n", pingMsg, wsUri);
    }
  }

  @Override
  public CompletionStage<?> onClose(final WebSocket webSocket, final int statusCode, final String reason) {
    if (onClose == null) {
      if (reason == null || reason.isBlank()) {
        log.log(WARNING,
            "WebSocket connection to {0} closed with code {1,number,integer}.",
            wsUri, statusCode);
      } else {
        log.log(WARNING,
            "WebSocket connection to {0} closed with code {1,number,integer} because ''{2}''.",
            wsUri, statusCode, reason);
      }
    } else {
      onClose.accept(this, statusCode, reason);
    }
    return null;
  }

  @Override
  public void onError(final WebSocket webSocket, final Throwable error) {
    if (onError == null) {
      log.log(ERROR, "Error on connection to %s" + this.wsUri, error);
    } else {
      onError.accept(this, error);
    }
  }

  @Override
  public void close() {
    this.msgId.set(Long.MIN_VALUE);

    final var webSocket = this.webSocket;
    if (webSocket != null) {
      this.webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    this.executorService.shutdown();
    this.pendingSubscriptions.clear();
    this.pendingUnsubscriptions.clear();
    this.subscriptionsBySubId.clear();
    this.accountSubs.clear();
    this.txLogSubs.clear();
    this.signatureSubs.clear();
    this.programSubs.clear();
    this.slotSub.set(null);
  }
}
