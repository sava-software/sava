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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.lang.System.Logger.Level.*;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.joining;
import static software.sava.rpc.json.http.response.AccountInfo.BYTES_IDENTITY;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

final class SolanaJsonRpcWebsocket implements WebSocket.Listener, SolanaRpcWebsocket, Runnable {

  private static final System.Logger log = System.getLogger(SolanaJsonRpcWebsocket.class.getName());

  private final URI endpoint;
  private final SolanaAccounts solanaAccounts;
  private final Commitment defaultCommitment;
  private final Timings timings;
  private final WebSocket.Builder webSocketBuilder;
  private final ExecutorService executorService;
  private final Consumer<SolanaRpcWebsocket> onOpen;
  private final OnClose onClose;
  private final BiConsumer<SolanaRpcWebsocket, Throwable> onError;
  private final AtomicLong msgId;
  private final Map<Long, Subscription<?>> pendingSubscriptions;
  private final Map<Long, String> pendingUnSubscriptions;
  private final Map<String, Map<Commitment, Subscription<AccountInfo<byte[]>>>> accountSubs;
  private final Map<String, Map<Commitment, Subscription<TxLogs>>> txLogSubs;
  private final Map<String, Map<Commitment, Subscription<TxResult>>> signatureSubs;
  private final Map<String, Map<Commitment, Subscription<AccountInfo<byte[]>>>> programSubs;
  private final Set<Consumer<RuntimeException>> exceptionSubs;
  private final AtomicReference<Subscription<ProcessedSlot>> slotSub;
  private final Map<Long, Subscription<?>> subscriptionsBySubId;

  private final ReentrantLock lock;
  private final Condition newSubscription;
  private final AtomicLong lastWrite;
  private volatile WebSocket webSocket;

  private char[] buffer;
  private int offset;
  private final JsonIterator ji;

  SolanaJsonRpcWebsocket(final URI endpoint,
                         final SolanaAccounts solanaAccounts,
                         final Commitment defaultCommitment,
                         final WebSocket.Builder webSocketBuilder,
                         final Timings timings,
                         final Consumer<SolanaRpcWebsocket> onOpen,
                         final OnClose onClose,
                         final BiConsumer<SolanaRpcWebsocket, Throwable> onError) {
    this.endpoint = endpoint;
    this.solanaAccounts = solanaAccounts;
    this.defaultCommitment = defaultCommitment;
    this.timings = timings;
    this.webSocketBuilder = webSocketBuilder;
    this.onOpen = onOpen;
    this.onClose = onClose;
    this.onError = onError;
    this.msgId = new AtomicLong(1);
    this.lastWrite = new AtomicLong(0);
    this.pendingSubscriptions = new ConcurrentSkipListMap<>();
    this.pendingUnSubscriptions = new ConcurrentSkipListMap<>();
    this.accountSubs = new ConcurrentSkipListMap<>();
    this.txLogSubs = new ConcurrentSkipListMap<>();
    this.signatureSubs = new ConcurrentSkipListMap<>();
    this.programSubs = new ConcurrentSkipListMap<>();
    this.slotSub = new AtomicReference<>();
    this.subscriptionsBySubId = new ConcurrentSkipListMap<>();
    this.exceptionSubs = HashSet.newHashSet(1);
    this.buffer = new char[4_096];
    this.ji = JsonIterator.parse(new byte[0]);
    this.lock = new ReentrantLock();
    this.newSubscription = lock.newCondition();
    this.executorService = Executors.newFixedThreadPool(1);
    this.executorService.execute(this);
  }

  @Override
  public URI endpoint() {
    return endpoint;
  }

  @Override
  public SolanaAccounts solanaAccounts() {
    return solanaAccounts;
  }

  @Override
  public Commitment defaultCommitment() {
    return defaultCommitment;
  }

  @Override
  public Timings timings() {
    return timings;
  }

  @Override
  public boolean closed() {
    return this.msgId.get() < 0;
  }

  @Override
  public CompletableFuture<?> connect() {
    if (!closed()) {
      this.webSocket = null;
      final long now = System.currentTimeMillis();
      final long millisSinceLastWrite = now - this.lastWrite.get();
      if (millisSinceLastWrite < timings.reConnectDelay()) {
        final long delay = this.timings.reConnectDelay() - millisSinceLastWrite;
        final var delayedExecutor = CompletableFuture.delayedExecutor(delay, MILLISECONDS);
        return CompletableFuture.supplyAsync(() -> {
              this.lastWrite.set(System.currentTimeMillis());
              return this.webSocketBuilder.buildAsync(this.endpoint, this).join();
            }, delayedExecutor
        );
      } else {
        this.lastWrite.set(now);
        return this.webSocketBuilder.buildAsync(this.endpoint, this);
      }
    } else {
      return null;
    }
  }

  @SuppressWarnings({"InfiniteLoopStatement", "ResultOfMethodCallIgnored"})
  @Override
  public void run() {
    try {
      final long sleep = timings.subscriptionAndPingCheckDelay();
      for (; ; ) {
        lock.lock();
        try {
          newSubscription.await(sleep, MILLISECONDS);
          final var webSocket = this.webSocket;
          if (webSocket != null) {
            handlePendingSubscriptions(webSocket);
          }
        } finally {
          lock.unlock();
        }
      }
    } catch (final InterruptedException e) {
      close();
    } catch (final RuntimeException ex) {
      log.log(ERROR, "Unhandled Solana Websocket exception.", ex);
      close();
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
    this.pendingUnSubscriptions.clear();
    this.subscriptionsBySubId.clear();
    lockAndHandlePendingSubscriptions(webSocket);
    webSocket.request(Long.MAX_VALUE);
    this.webSocket = webSocket;
    if (this.onOpen != null) {
      this.onOpen.accept(this);
    } else {
      log.log(INFO, "WebSocket connected to {0}.", endpoint.getHost());
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
                                        final Consumer<Subscription<T>> onSub,
                                        final Consumer<T> consumer) {
    final long msgId = this.msgId.incrementAndGet();
    final var msg = createSubscriptionMsg(msgId, channel, params);
    final var sub = Subscription.createSubscription(commitment, channel, key, msgId, msg, onSub, consumer);
    final var duplicate = subs.computeIfAbsent(sub.key(), _ -> new EnumMap<>(Commitment.class)).putIfAbsent(commitment, sub);
    if (duplicate == null) {
      this.pendingSubscriptions.put(msgId, sub);
      lock.lock();
      try {
        newSubscription.signal();
      } finally {
        lock.unlock();
      }
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
                                        final Consumer<Subscription<T>> onSub,
                                        final Consumer<T> consumer) {
    final long msgId = this.msgId.incrementAndGet();
    final var msg = createSubscriptionMsg(msgId, channel, params);
    final var sub = Subscription.createAccountSubscription(commitment, channel, publicKey, msgId, msg, onSub, consumer);
    final var duplicate = subs.computeIfAbsent(sub.key(), _ -> new EnumMap<>(Commitment.class)).putIfAbsent(commitment, sub);
    if (duplicate == null) {
      this.pendingSubscriptions.put(msgId, sub);
      lock.lock();
      try {
        newSubscription.signal();
      } finally {
        lock.unlock();
      }
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
      this.pendingUnSubscriptions.put(subId, msg);
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
                                  final Consumer<Subscription<AccountInfo<byte[]>>> onSub,
                                  final Consumer<AccountInfo<byte[]>> consumer) {
    final var sub = this.accountSubs.get(key.toBase58());
    if (sub == null || !sub.containsKey(commitment)) {
      final var params = String.format("""
          "%s",{"encoding":"base64","commitment":"%s"}""", key, commitment.getValue());
      return queueSubscription(commitment, Channel.account, key, params, this.accountSubs, onSub, consumer);
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
                               final Consumer<Subscription<TxLogs>> onSub,
                               final Consumer<TxLogs> consumer) {
    final var sub = this.txLogSubs.get(key.toBase58());
    if (sub == null || !sub.containsKey(commitment)) {
      final var params = String.format("""
          {"mentions":["%s"]},{"commitment":"%s"}""", key, commitment.getValue());
      return queueSubscription(commitment, Channel.logs, key.toBase58(), params, this.txLogSubs, onSub, consumer);
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
                                    final Consumer<Subscription<TxResult>> onSub,
                                    final Consumer<TxResult> consumer) {
    final var sub = this.signatureSubs.get(b58TxSig);
    if (sub == null || !sub.containsKey(commitment)) {
      final var params = String.format("""
          "%s",{"commitment":"%s","enableReceivedNotification":%b}""", b58TxSig, commitment.getValue(), enableReceivedNotification);
      return queueSubscription(commitment, Channel.signature, b58TxSig, params, this.signatureSubs, onSub, consumer);
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
    return programSubscribe(this.defaultCommitment, program, null, consumer);
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
                                  final Consumer<Subscription<AccountInfo<byte[]>>> onSub,
                                  final Consumer<AccountInfo<byte[]>> consumer) {
    final var sub = this.programSubs.get(program.toBase58());
    if (sub == null || !sub.containsKey(commitment)) {
      final var filtersJson = filters == null || filters.isEmpty() ? "" : filters.stream()
          .map(Filter::toJson)
          .collect(joining(",", ",\"filters\":[", "]"));

      final var params = String.format("""
              "%s",{"commitment":"%s","encoding":"base64"%s}""",
          program, commitment.getValue(), filtersJson);
      return queueSubscription(commitment, Channel.program, program, params, this.programSubs, onSub, consumer);
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
  public boolean slotSubscribe(final Consumer<Subscription<ProcessedSlot>> onSub, final Consumer<ProcessedSlot> consumer) {
    final long msgId = this.msgId.incrementAndGet();
    final var msg = String.format("""
        {"jsonrpc":"2.0","id":%d,"method":"%s"}""", msgId, Channel.slot.subscribe());
    final var slotSub = Subscription.createSubscription(null, Channel.slot, Channel.slot.name(), msgId, msg, onSub, consumer);
    if (this.slotSub.compareAndSet(null, slotSub)) {
      this.pendingSubscriptions.put(msgId, slotSub);
      lock.lock();
      try {
        newSubscription.signal();
      } finally {
        lock.unlock();
      }
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

  private CompletableFuture<WebSocket> sendText(final WebSocket webSocket, final String msg) {
    final var future = webSocket.sendText(msg, true);
    log.log(DEBUG, msg);
    return future;
  }

  private void sendUnSubscription(final WebSocket webSocket,
                                  final Channel channel,
                                  final long subId) {
    lock.lock();
    try {
      final var msg = this.pendingUnSubscriptions.remove(subId);
      sendText(webSocket, msg == null ? createUnSubMsg(channel, subId) : msg);
      this.lastWrite.set(System.currentTimeMillis());
    } finally {
      lock.unlock();
    }
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

  @SuppressWarnings("unused")
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
                  publish(webSocket, channel, ji, paramsMark, sub -> AccountInfo.parse(sub.publicKey(), ji, context, BYTES_IDENTITY));
              case logs -> publish(webSocket, channel, ji, paramsMark, TxLogs.parse(ji, context));
              case program ->
                  publish(webSocket, channel, ji, paramsMark, AccountInfo.parseAccount(ji, context, BYTES_IDENTITY));
              case signature -> {
                final var result = TxResult.parseResult(ji, context);
                if (result != null) {
                  ji.skipRestOfObject();
                  if (ji.skipUntil("subscription") == null) {
                    ji.reset(paramsMark).skipUntil("subscription");
                  }
                  final long subId = ji.readLong();
                  @SuppressWarnings("unchecked") final var sub = (Subscription<TxResult>) this.subscriptionsBySubId.get(subId);
                  if (sub != null) {
                    sub.accept(result);
                    if (!"receivedSignature".equals(result.value())) {
                      // Server side subscription is automatically cancelled after processed message has been sent.
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
        lockAndHandlePendingSubscriptions(webSocket);
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

  private void lockAndHandlePendingSubscriptions(final WebSocket webSocket) {
    lock.lock();
    try {
      handlePendingSubscriptions(webSocket);
    } finally {
      lock.unlock();
    }
  }

  private void handlePendingSubscriptions(final WebSocket webSocket) {
    final long now = System.currentTimeMillis();
    // Mark lastWrite to try to prevent a concurrent ping.
    final long previousSend = this.lastWrite.getAndSet(now);
    if (this.pendingSubscriptions.isEmpty()) {
      if (noPendingUnSubscriptions(webSocket) && this.lastWrite.compareAndSet(now, previousSend)) {
        sendPing(webSocket);
      }
    } else {
      int numSubs = 0;
      for (final var sub : this.pendingSubscriptions.values()) {
        if (now - sub.lastAttempt() > this.timings.reConnectDelay()) {
          sub.setLastAttempt(now);
          final var sendFuture = sendText(webSocket, sub.msg());
          sendFuture.thenRun(sub);
          ++numSubs;
        }
      }
      if (noPendingUnSubscriptions(webSocket)
          && numSubs == 0
          && this.lastWrite.compareAndSet(now, previousSend)) {
        sendPing(webSocket);
      }
    }
  }

  private boolean noPendingUnSubscriptions(final WebSocket webSocket) {
    int numUnSubs = 0;
    final var iterator = this.pendingUnSubscriptions.entrySet().iterator();
    for (; iterator.hasNext(); ++numUnSubs) {
      sendText(webSocket, iterator.next().getValue());
      iterator.remove();
    }
    return numUnSubs <= 0;
  }

  @Override
  public CompletionStage<?> onPing(final WebSocket webSocket, final ByteBuffer message) {
    // A pong will be sent by the underlying WebSocket implementation.
    log.log(DEBUG, () -> new String(message.array()));
    lockAndHandlePendingSubscriptions(webSocket);
    return null;
  }

  @Override
  public CompletionStage<?> onPong(final WebSocket webSocket, final ByteBuffer message) {
    lockAndHandlePendingSubscriptions(webSocket);
    log.log(DEBUG, () -> new String(message.array()));
    return null;
  }

  private void sendPing(final WebSocket webSocket) {
    final long now = System.currentTimeMillis();
    final long millisSinceLastWrite = now - this.lastWrite.get();
    if (millisSinceLastWrite > this.timings.pingDelay()) {
      final long previousWrite = this.lastWrite.getAndSet(now);
      final var pingMsg = String.valueOf(now);
      webSocket.sendPing(ByteBuffer.wrap(pingMsg.getBytes(ISO_8859_1))).whenComplete(((_, throwable) -> {
        if (throwable != null) {
          this.lastWrite.compareAndSet(now, previousWrite);
          log.log(WARNING, String.format("Failed to ping %d to %s.", now, this.endpoint.getHost()), throwable);
        } else {
          log.log(DEBUG, "{0} to {1}.\n", pingMsg, endpoint.getHost());
        }
      }));
    }
  }

  @Override
  public CompletionStage<?> onClose(final WebSocket webSocket, final int statusCode, final String reason) {
    if (onClose == null) {
      if (reason == null || reason.isBlank()) {
        log.log(WARNING, "WebSocket connection to {0} closed with code {1,number,integer}.",
            endpoint.getHost(), statusCode
        );
      } else {
        log.log(WARNING, "WebSocket connection to {0} closed with code {1,number,integer} because ''{2}''.",
            endpoint.getHost(), statusCode, reason
        );
      }
      this.close();
    } else {
      onClose.accept(this, statusCode, reason);
    }
    return null;
  }

  @Override
  public void onError(final WebSocket webSocket, final Throwable error) {
    if (onError == null) {
      log.log(ERROR, "Error on connection to " + endpoint.getHost(), error);
      this.close();
    } else {
      onError.accept(this, error);
    }
  }

  @Override
  public void close() {
    this.msgId.set(Long.MIN_VALUE);

    final var webSocket = this.webSocket;
    if (webSocket != null) {
      if (!webSocket.isOutputClosed()) {
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "close");
      }
      this.webSocket = null;
    }

    this.executorService.shutdown();
    this.pendingSubscriptions.clear();
    this.pendingUnSubscriptions.clear();
    this.subscriptionsBySubId.clear();
    this.accountSubs.clear();
    this.txLogSubs.clear();
    this.signatureSubs.clear();
    this.programSubs.clear();
    this.slotSub.set(null);
  }
}
