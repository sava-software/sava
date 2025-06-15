package software.sava.core.rpc;

record MemCmpFilter(int offset, String base58Encoded) implements Filter {

  @Override
  public String toJson() {
    return String.format("""
        {"memcmp":{"offset":%d,"bytes":"%s"}}""", offset, base58Encoded);
  }
}
