package software.sava.core.accounts.lookup;

import software.sava.core.accounts.PublicKey;

import java.util.*;

final class AddressLookupTableWithReverseLookup extends AddressLookupTableRoot {

  private final byte[] discriminator;
  private final long deactivationSlot;
  private final long lastExtendedSlot;
  private final int lastExtendedSlotStartIndex;
  private final PublicKey authority;
  private final Map<PublicKey, PublicKey> distinctAccounts;
  private final PublicKey[] accounts;
  private final AccountIndexLookupTableEntry[] reverseLookupTable;

  AddressLookupTableWithReverseLookup(final PublicKey address,
                                      final byte[] discriminator,
                                      final long deactivationSlot,
                                      final long lastExtendedSlot,
                                      final int lastExtendedSlotStartIndex,
                                      final PublicKey authority,
                                      final Map<PublicKey, PublicKey> distinctAccounts,
                                      final PublicKey[] accounts,
                                      final AccountIndexLookupTableEntry[] reverseLookupTable,
                                      final byte[] data) {
    super(address, data);
    this.discriminator = discriminator;
    this.deactivationSlot = deactivationSlot;
    this.lastExtendedSlot = lastExtendedSlot;
    this.lastExtendedSlotStartIndex = lastExtendedSlotStartIndex;
    this.authority = authority;
    this.accounts = accounts;
    this.reverseLookupTable = reverseLookupTable;
    this.distinctAccounts = distinctAccounts;
  }

  @Override
  public int offset() {
    return 0;
  }

  @Override
  public int length() {
    return data.length;
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
  public int indexOf(final PublicKey publicKey) {
    return AccountIndexLookupTableEntry.lookupAccountIndex(this.reverseLookupTable, publicKey);
  }

  @Override
  public boolean containKey(final PublicKey publicKey) {
    return distinctAccounts.containsKey(publicKey);
  }

  @Override
  public byte indexOfOrThrow(final PublicKey publicKey) {
    return AccountIndexLookupTableEntry.lookupAccountIndexOrThrow(this.reverseLookupTable, publicKey);
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
    if (obj == null || obj.getClass() != this.getClass()) return false;
    var that = (AddressLookupTableWithReverseLookup) obj;
    return Objects.equals(this.address, that.address) &&
        Arrays.equals(this.discriminator, that.discriminator) &&
        this.deactivationSlot == that.deactivationSlot &&
        this.lastExtendedSlot == that.lastExtendedSlot &&
        this.lastExtendedSlotStartIndex == that.lastExtendedSlotStartIndex &&
        Objects.equals(this.authority, that.authority) &&
        Arrays.equals(this.accounts, that.accounts) &&
        Arrays.equals(this.reverseLookupTable, that.reverseLookupTable);
  }

  @Override
  public int hashCode() {
    return Objects.hash(address, Arrays.hashCode(discriminator), deactivationSlot, lastExtendedSlot, lastExtendedSlotStartIndex, authority, Arrays.hashCode(accounts), Arrays.hashCode(reverseLookupTable));
  }
}
