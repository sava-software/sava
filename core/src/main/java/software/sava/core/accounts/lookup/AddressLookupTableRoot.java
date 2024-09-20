package software.sava.core.accounts.lookup;

import software.sava.core.accounts.PublicKey;

abstract class AddressLookupTableRoot implements AddressLookupTable {

  protected final PublicKey address;
  protected final byte[] data;

  AddressLookupTableRoot(final PublicKey address, final byte[] data) {
    this.address = address;
    this.data = data;
  }

  @Override
  public final int write(final byte[] out, final int offset) {
    System.arraycopy(out, offset, data, 0, data.length);
    return data.length;
  }

  @Override
  public byte[] data() {
    return data;
  }

  @Override
  public PublicKey address() {
    return address;
  }

  protected abstract String keysToString();

  public final String toString() {
    return String.format("""
            {
              "address": "%s",
              "deactivationSlot": %s,
              "lastExtendedSlot": %s,
              "lastExtendedSlotStartIndex": %d,
              "authority": "%s",
              "accounts": "%s"
            }""",
        address,
        Long.toUnsignedString(deactivationSlot()),
        Long.toUnsignedString(lastExtendedSlot()),
        lastExtendedSlotStartIndex(),
        authority(),
        keysToString()
    );
  }
}
