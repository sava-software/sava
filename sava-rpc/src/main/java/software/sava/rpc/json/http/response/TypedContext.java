package software.sava.rpc.json.http.response;

public record TypedContext<T>(Context context, T data) {
}
