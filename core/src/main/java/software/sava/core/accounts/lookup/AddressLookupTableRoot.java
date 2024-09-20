package software.sava.core.accounts.lookup;

import software.sava.core.accounts.PublicKey;

abstract class AddressLookupTableRoot implements AddressLookupTable {

  protected final PublicKey address;

  AddressLookupTableRoot(final PublicKey address) {
    this.address = address;
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
