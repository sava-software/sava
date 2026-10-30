package software.sava.core.rpc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// The slice is a literal Agave `dataSlice`: a zero length is empty data, never "whole
/// account", and empty slices canonicalize to offset zero as `normalize_data_slice` does.
final class DataSliceTests {

  @Test
  void jsonWritesOffsetThenLength() {
    assertEquals("{\"offset\":8,\"length\":32}", new DataSlice(8, 32).toJson());
    assertEquals("{\"offset\":0,\"length\":1}", new DataSlice(0, 1).toJson());
    assertEquals("{\"offset\":2147483647,\"length\":2147483647}",
        new DataSlice(Integer.MAX_VALUE, Integer.MAX_VALUE).toJson());
  }

  @Test
  void componentsKeepTheirPositions() {
    final var slice = new DataSlice(8, 32);
    assertEquals(8, slice.offset());
    assertEquals(32, slice.length());
    assertNotEquals(slice, new DataSlice(32, 8), "offset and length are not interchangeable");
    assertEquals(slice, new DataSlice(8, 32));
    assertEquals(slice.hashCode(), new DataSlice(8, 32).hashCode());
  }

  @Test
  void emptySlicesCanonicalizeToOffsetZero() {
    final var empty = new DataSlice(5, 0);
    assertEquals(0, empty.offset());
    assertEquals(0, empty.length());
    assertEquals(DataSlice.EMPTY, empty);
    assertEquals(DataSlice.EMPTY.hashCode(), empty.hashCode());
    assertEquals("{\"offset\":0,\"length\":0}", empty.toJson());
    assertEquals(new DataSlice(0, 0), new DataSlice(Integer.MAX_VALUE, 0));
  }

  @Test
  void aOneByteSliceKeepsItsOffset() {
    final var slice = new DataSlice(5, 1);
    assertEquals(5, slice.offset());
    assertEquals(1, slice.length());
  }

  @Test
  void negativeOffsetIsRejected() {
    final var e = assertThrows(IllegalArgumentException.class, () -> new DataSlice(-1, 4));
    assertTrue(e.getMessage().contains("offset"), e.getMessage());
    assertTrue(e.getMessage().contains("-1"), e.getMessage());
  }

  @Test
  void negativeLengthIsRejected() {
    final var e = assertThrows(IllegalArgumentException.class, () -> new DataSlice(0, -1));
    assertTrue(e.getMessage().contains("length"), e.getMessage());
    assertTrue(e.getMessage().contains("-1"), e.getMessage());
    assertThrows(IllegalArgumentException.class, () -> new DataSlice(3, Integer.MIN_VALUE));
  }

  @Test
  void zeroIsTheLowerBoundOfBothComponents() {
    assertDoesNotThrow(() -> new DataSlice(0, 0));
    assertDoesNotThrow(() -> new DataSlice(0, 1));
    assertDoesNotThrow(() -> new DataSlice(1, 0));
  }
}
