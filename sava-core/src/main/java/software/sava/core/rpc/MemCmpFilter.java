package software.sava.core.rpc;

import java.util.Locale;

record MemCmpFilter(int offset, String base58Encoded) implements Filter {

  @Override
  public String toJson() {
    return String.format(Locale.ROOT, """
        {"memcmp":{"offset":%d,"bytes":"%s"}}""", offset, base58Encoded);
  }
}
