package software.sava.core.accounts.lookup;

import software.sava.core.accounts.PublicKey;

import java.util.*;

final class AddressLookupTableWithReverseLookup extends AddressLookupTableRoot {

  private final byte[] discriminator;
  private final long deactivationSlot;
  private final long lastExtendedSlot;
  private final int lastExtendedSlotStartIndex;
  private final PublicKey authority;
  private final Map<PublicKey, Integer> distinctAccounts;
  private final PublicKey[] accounts;

  AddressLookupTableWithReverseLookup(final PublicKey address,
                                      final byte[] discriminator,
                                      final long deactivationSlot,
                                      final long lastExtendedSlot,
                                      final int lastExtendedSlotStartIndex,
                                      final PublicKey authority,
                                      final Map<PublicKey, Integer> distinctAccounts,
                                      final PublicKey[] accounts,
                                      final byte[] data) {
    super(address, data);
    this.discriminator = discriminator;
    this.deactivationSlot = deactivationSlot;
    this.lastExtendedSlot = lastExtendedSlot;
    this.lastExtendedSlotStartIndex = lastExtendedSlotStartIndex;
    this.authority = authority;
    this.accounts = accounts;
    this.distinctAccounts = distinctAccounts;
  }

  @Override
  public AddressLookupTable withReverseLookup() {
    return this;
  }

  @Override
  public PublicKey account(final int index) {
    return accounts[index];
  }

  @Override
  public boolean containKey(final PublicKey publicKey) {
    return distinctAccounts.containsKey(publicKey);
  }

  private static final Integer NOT_PRESENT = -1;

  @Override
  public int indexOf(final PublicKey publicKey) {
    return distinctAccounts.getOrDefault(publicKey, NOT_PRESENT);
  }

  @Override
  public byte indexOfOrThrow(final PublicKey publicKey) {
    final int index = indexOf(publicKey);
    if (index < 0) {
      throw new IllegalStateException(String.format("Could not find %s in lookup table.", publicKey.toBase58()));
    } else {
      return (byte) index;
    }
  }

  @Override
  public int numAccounts() {
    return accounts.length;
  }

  @Override
  public int numUniqueAccounts() {
    return distinctAccounts.size();
  }

  @Override
  public Set<PublicKey> uniqueAccounts() {
    return Collections.unmodifiableSet(distinctAccounts.keySet());
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

  @Override
  protected String keysToString() {
    return Arrays.toString(accounts);
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (obj instanceof AddressLookupTableWithReverseLookup that) {
      return address.equals(that.address) &&
          Arrays.equals(this.discriminator, that.discriminator) &&
          this.deactivationSlot == that.deactivationSlot &&
          this.lastExtendedSlot == that.lastExtendedSlot &&
          this.lastExtendedSlotStartIndex == that.lastExtendedSlotStartIndex &&
          Objects.equals(this.authority, that.authority) &&
          Arrays.equals(this.accounts, that.accounts);
    } else {
      return false;
    }
  }

  @Override
  public int hashCode() {
    return address.hashCode();
  }
}
