package software.sava.rpc.json.http.ws;

public record Timings(long reConnectDelay,
                      long pingDelay,
                      long subscriptionAndPingCheckDelay) {
}
