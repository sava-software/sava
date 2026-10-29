package software.sava.core.rpc;

import java.util.Locale;

record DataSizeFilter(int dataSize) implements Filter {

  @Override
  public String toJson() {
    return String.format(Locale.ROOT, """
        {"dataSize":%d}""", dataSize);
  }
}
