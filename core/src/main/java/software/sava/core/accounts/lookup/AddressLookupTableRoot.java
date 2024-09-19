package software.sava.core.accounts.lookup;

import software.sava.core.accounts.PublicKey;

abstract class AddressLookupTableRoot implements AddressLookupTable {

  protected final PublicKey address;
  protected final byte[] discriminator;
  protected final long deactivationSlot;
  protected final long lastExtendedSlot;
  protected final int lastExtendedSlotStartIndex;
  protected final PublicKey authority;

  AddressLookupTableRoot(final PublicKey address,
                         final byte[] discriminator,
                         final long deactivationSlot,
                         final long lastExtendedSlot,
                         final int lastExtendedSlotStartIndex,
                         final PublicKey authority) {
    this.address = address;
    this.discriminator = discriminator;
    this.deactivationSlot = deactivationSlot;
    this.lastExtendedSlot = lastExtendedSlot;
    this.lastExtendedSlotStartIndex = lastExtendedSlotStartIndex;
    this.authority = authority;
  }

  @Override
  public PublicKey address() {
    return address;
  }

  @Override
  public byte[] discriminator() {
    return discriminator;
  }

  @Override
  public long deactivationSlot() {
    return deactivationSlot;
  }

  @Override
  public long lastExtendedSlot() {
    return lastExtendedSlot;
  }

  @Override
  public int lastExtendedSlotStartIndex() {
    return lastExtendedSlotStartIndex;
  }

  @Override
  public PublicKey authority() {
    return authority;
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
        Long.toUnsignedString(deactivationSlot),
        Long.toUnsignedString(lastExtendedSlot),
        lastExtendedSlotStartIndex,
        authority,
        keysToString()
    );
  }
}
