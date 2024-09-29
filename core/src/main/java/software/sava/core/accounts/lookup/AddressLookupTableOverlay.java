package software.sava.core.accounts.lookup;

import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.ByteUtil;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;

final class AddressLookupTableOverlay extends AddressLookupTableRoot {

  private final int offset;
  private final int length;

  AddressLookupTableOverlay(final PublicKey address, final byte[] data, final int offset, final int length) {
    super(address, data);
    this.offset = offset;
    this.length = length;
  }

  @Override
  public int offset() {
    return offset;
  }

  @Override
  public int length() {
    return length;
  }

  private int from() {
    return offset + LOOKUP_TABLE_META_SIZE;
  }

  private int to() {
    return offset + length;
  }

  @Override
  public byte[] discriminator() {
    final byte[] discriminator = new byte[4];
    System.arraycopy(data, offset, discriminator, 0, 4);
    return discriminator;
  }

  @Override
  public long deactivationSlot() {
    return ByteUtil.getInt64LE(data, offset + DEACTIVATION_SLOT_OFFSET);
  }

  @Override
  public long lastExtendedSlot() {
    return ByteUtil.getInt64LE(data, offset + LAST_EXTENDED_OFFSET);
  }

  @Override
  public int lastExtendedSlotStartIndex() {
    return data[offset + LAST_EXTENDED_SLOT_START_INDEX_OFFSET] & 0xFF;
  }

  @Override
  public PublicKey authority() {
    return data[offset + AUTHORITY_OPTION_OFFSET] == 0
        ? null
        : readPubKey(data, offset + AUTHORITY_OFFSET);
  }

  @Override
  public AddressLookupTable withReverseLookup() {
    final int numAccounts = numAccounts();
    final var distinctAccounts = HashMap.<PublicKey, PublicKey>newHashMap(numAccounts);
    final var accounts = new PublicKey[numAccounts];
    final var reverseLookupTable = new AccountIndexLookupTableEntry[numAccounts];
    for (int i = 0, from = from(), to = to(); from < to; ++i, from += PUBLIC_KEY_LENGTH) {
      final var pubKey = readPubKey(data, from);
      final var previous = distinctAccounts.putIfAbsent(pubKey, pubKey);
      if (previous == null) {
        accounts[i] = pubKey;
        reverseLookupTable[i] = new AccountIndexLookupTableEntry(pubKey.toByteArray(), i);
      } else {
        accounts[i] = previous;
        reverseLookupTable[i] = new AccountIndexLookupTableEntry(previous.toByteArray(), i);
      }
    }
    Arrays.sort(reverseLookupTable);
    return new AddressLookupTableWithReverseLookup(
        address,
        discriminator(),
        deactivationSlot(),
        lastExtendedSlot(),
        lastExtendedSlotStartIndex(),
        authority(),
        distinctAccounts,
        accounts,
        reverseLookupTable,
        data.length == length
            ? data
            : Arrays.copyOfRange(data, offset, length)
    );
  }

  @Override
  public int numUniqueAccounts() {
    final int numAccounts = numAccounts();
    final var distinctAccounts = HashSet.<PublicKey>newHashSet(numAccounts);
    int numUnique = 0;
    for (int i = 0, from = from(), to = to(); from < to; ++i, from += PUBLIC_KEY_LENGTH) {
      final var pubKey = readPubKey(data, from);
      if (distinctAccounts.add(pubKey)) {
        ++numUnique;
      }
    }
    return numUnique;
  }

  @Override
  public Set<PublicKey> uniqueAccounts() {
    final int numAccounts = numAccounts();
    final var distinctAccounts = HashSet.<PublicKey>newHashSet(numAccounts);
    for (int i = 0, from = from(), to = to(); from < to; ++i, from += PUBLIC_KEY_LENGTH) {
      final var pubKey = readPubKey(data, from);
      distinctAccounts.add(pubKey);
    }
    return distinctAccounts;
  }

  @Override
  public PublicKey account(final int index) {
    return readPubKey(data, from() + (index << 5));
  }

  @Override
  public int indexOf(final PublicKey publicKey) {
    final byte[] bytes = publicKey.toByteArray();
    for (int from = from(), to = from + PUBLIC_KEY_LENGTH, numAccounts = numAccounts(), i = 0; i < numAccounts; ++i) {
      if (Arrays.equals(
          bytes, 0, PUBLIC_KEY_LENGTH,
          data, from, to)) {
        return i;
      }
      from = to;
      to += PUBLIC_KEY_LENGTH;
    }
    return Integer.MIN_VALUE;
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
    return (length - LOOKUP_TABLE_META_SIZE) >> 5;
  }

  @Override
  protected String keysToString() {
    final int to = this.to();
    return IntStream.iterate(from(), i -> i < to, i -> i + PUBLIC_KEY_LENGTH)
        .mapToObj(i -> readPubKey(data, i))
        .map(PublicKey::toBase58)
        .collect(Collectors.joining(", ", "[", "]"));
  }

  @Override
  public boolean equals(final Object obj) {
    return obj instanceof AddressLookupTableOverlay overlay
        && address.equals(overlay.address)
        && Arrays.equals(data, overlay.data);
  }

  @Override
  public int hashCode() {
    return Objects.hash(address, Arrays.hashCode(data));
  }
}
