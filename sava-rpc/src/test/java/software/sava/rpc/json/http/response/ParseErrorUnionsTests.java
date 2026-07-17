package software.sava.rpc.json.http.response;

import org.junit.jupiter.api.Test;
import systems.comodal.jsoniter.JsonIterator;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

// The FieldMatcher-backed union parsers couple each variant to its position in
// the matcher's declaration order. These tests round-trip every permitted
// variant by name — driven by the sealed interface itself, so a new variant is
// automatically covered — ensuring an index slip cannot survive.
final class ParseErrorUnionsTests {

  @Test
  void everyTransactionErrorStringVariantRoundTrips() {
    final var objectForms = Set.of(
        TransactionError.InstructionError.class,
        TransactionError.DuplicateInstruction.class,
        TransactionError.InsufficientFundsForRent.class,
        TransactionError.ProgramExecutionTemporarilyRestricted.class,
        TransactionError.Unknown.class
    );
    final var variants = TransactionError.class.getPermittedSubclasses();
    int checked = 0;
    for (final var variant : variants) {
      if (objectForms.contains(variant)) {
        continue;
      }
      final var name = variant.getSimpleName();
      final var error = TransactionError.parseError(JsonIterator.parse('"' + name + '"'));
      assertSame(variant, error.getClass(), name);
      ++checked;
    }
    assertEquals(variants.length - objectForms.size(), checked);
  }

  @Test
  void everyIxErrorStringVariantRoundTrips() {
    final var objectForms = Set.of(
        IxError.Custom.class,
        IxError.Unknown.class
    );
    final var variants = IxError.class.getPermittedSubclasses();
    int checked = 0;
    for (final var variant : variants) {
      if (objectForms.contains(variant)) {
        continue;
      }
      final var name = variant.getSimpleName();
      final var error = IxError.parseError(JsonIterator.parse('"' + name + '"'));
      assertSame(variant, error.getClass(), name);
      ++checked;
    }
    assertEquals(variants.length - objectForms.size(), checked);
  }

  @Test
  void objectVariantsAndUnknownFallbacks() {
    var error = TransactionError.parseError(JsonIterator.parse("{\"InstructionError\":[3,\"InvalidSeeds\"]}"));
    assertEquals(new TransactionError.InstructionError(3, IxError.InvalidSeeds.INSTANCE), error);

    error = TransactionError.parseError(JsonIterator.parse("{\"InstructionError\":[0,{\"Custom\":42}]}"));
    assertEquals(new TransactionError.InstructionError(0, new IxError.Custom(42)), error);

    error = TransactionError.parseError(JsonIterator.parse("{\"InstructionError\":[1,{\"BorshIoError\":\"oops\"}]}"));
    assertEquals(new TransactionError.InstructionError(1, new IxError.BorshIoError("oops")), error);

    error = TransactionError.parseError(JsonIterator.parse("{\"DuplicateInstruction\":5}"));
    assertEquals(new TransactionError.DuplicateInstruction(5), error);

    error = TransactionError.parseError(JsonIterator.parse("{\"InsufficientFundsForRent\":{\"account_index\":2}}"));
    assertEquals(new TransactionError.InsufficientFundsForRent(2), error);

    error = TransactionError.parseError(JsonIterator.parse("{\"ProgramExecutionTemporarilyRestricted\":{\"account_index\":4}}"));
    assertEquals(new TransactionError.ProgramExecutionTemporarilyRestricted(4), error);

    error = TransactionError.parseError(JsonIterator.parse("\"BrandNewError\""));
    assertEquals(new TransactionError.Unknown("BrandNewError"), error);

    error = TransactionError.parseError(JsonIterator.parse("{\"BrandNewError\":{\"x\":1}}"));
    assertEquals(new TransactionError.Unknown("BrandNewError"), error);

    var ixError = IxError.parseError(JsonIterator.parse("\"BorshIoError\""));
    assertEquals(new IxError.BorshIoError(null), ixError);

    ixError = IxError.parseError(JsonIterator.parse("\"BrandNewIxError\""));
    assertEquals(new IxError.Unknown("BrandNewIxError"), ixError);

    ixError = IxError.parseError(JsonIterator.parse("{\"BrandNewIxError\":7}"));
    assertEquals(new IxError.Unknown("BrandNewIxError"), ixError);
  }
}
