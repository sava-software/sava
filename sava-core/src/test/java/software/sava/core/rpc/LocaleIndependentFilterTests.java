package software.sava.core.rpc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import software.sava.core.accounts.PublicKey;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Filters are request parameters, so their numbers must be ASCII digits whatever the JVM's
/// default format locale; `String.format` without a locale writes that locale's digits. The
/// default is JVM-wide state, hence [Isolated].
@Isolated
final class LocaleIndependentFilterTests {

  /// Thai digits, through the Unicode numbering-system extension.
  private static final Locale NON_ASCII_DIGITS = Locale.forLanguageTag("th-TH-u-nu-thai");

  @Test
  void filterJsonIsWrittenInAsciiDigits() {
    final var key = PublicKey.fromBase58Encoded("GovaE4iu227srtG2s3tZzB4RmWBzw8sTwrCLZz7kN7rY");
    final var formatLocale = Locale.getDefault(Locale.Category.FORMAT);
    final String dataSize;
    final String memCmp;
    final String dataSlice;
    Locale.setDefault(Locale.Category.FORMAT, NON_ASCII_DIGITS);
    try {
      dataSize = Filter.createDataSizeFilter(165).toJson();
      memCmp = Filter.createMemCompFilter(32, key).toJson();
      dataSlice = new DataSlice(8, 32).toJson();
    } finally {
      Locale.setDefault(Locale.Category.FORMAT, formatLocale);
    }
    assertEquals("{\"dataSize\":165}", dataSize);
    assertEquals("{\"memcmp\":{\"offset\":32,\"bytes\":\"" + key.toBase58() + "\"}}", memCmp);
    assertEquals("{\"offset\":8,\"length\":32}", dataSlice);
  }
}
