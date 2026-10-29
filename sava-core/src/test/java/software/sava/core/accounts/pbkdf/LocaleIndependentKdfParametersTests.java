package software.sava.core.accounts.pbkdf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Key files carry the KDF parameters as JSON or properties, so their numbers must not depend on
/// the JVM's default format locale; `String.format` without a locale writes that locale's
/// digits, which a JSON reader rejects. No key is derived here. The default is JVM-wide state,
/// hence [Isolated].
@Isolated
final class LocaleIndependentKdfParametersTests {

  /// Thai digits, through the Unicode numbering-system extension.
  private static final Locale NON_ASCII_DIGITS = Locale.forLanguageTag("th-TH-u-nu-thai");

  private static String render(final Locale locale, final Supplier<String> rendering) {
    final var formatLocale = Locale.getDefault(Locale.Category.FORMAT);
    Locale.setDefault(Locale.Category.FORMAT, locale);
    try {
      return rendering.get();
    } finally {
      Locale.setDefault(Locale.Category.FORMAT, formatLocale);
    }
  }

  @Test
  void parametersAreWrittenInAsciiDigits() {
    final var argon2id = new Argon2id(65_536, 4, 3);
    final var pbkdf2 = new PBKDF2WithHmacSHA512(PBKDF2WithHmacSHA512.MIN_ITERATIONS);
    for (final Supplier<String> rendering : List.<Supplier<String>>of(
        argon2id::toJson, argon2id::toPropertiesString, pbkdf2::toJson, pbkdf2::toPropertiesString)) {
      assertEquals(render(Locale.ROOT, rendering), render(NON_ASCII_DIGITS, rendering));
    }
  }
}
