package software.sava.core.rpc;

record DataSizeFilter(int dataSize) implements Filter {

  @Override
  public String toJson() {
    return String.format("""
        {"dataSize":%d}""", dataSize);
  }
}
