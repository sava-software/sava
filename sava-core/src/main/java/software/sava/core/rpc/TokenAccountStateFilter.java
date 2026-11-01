package software.sava.core.rpc;

/// Agave's `tokenAccountState` filter, a unit variant of its `RpcFilterType` and so a bare JSON
/// string where the other filters are objects; see [Filter#createTokenAccountStateFilter()].
record TokenAccountStateFilter() implements Filter {

  static final TokenAccountStateFilter INSTANCE = new TokenAccountStateFilter();

  @Override
  public String toJson() {
    return "\"tokenAccountState\"";
  }
}
