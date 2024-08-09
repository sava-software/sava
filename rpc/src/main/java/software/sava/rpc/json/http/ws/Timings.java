package software.sava.rpc.json.http.ws;

record Timings(long reConnect, long writeOrPingDelay, long subscriptionAndPingCheckDelay) {
}
