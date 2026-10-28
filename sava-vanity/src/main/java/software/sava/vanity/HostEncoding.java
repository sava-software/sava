package software.sava.vanity;

/// The host locale's charset decides what a password's bytes mean. A typed password is decoded by
/// the console in the charset derived from the native encoding; one in
/// `SAVA_VANITY_ENCRYPT_PASSWORD` is decoded when the JVM builds its environment map, in the native
/// encoding on Linux and in UTF-8 on macOS, which pins `sun.jnu.encoding` there. `native.encoding`
/// names the charset as the OS reports it, before the JVM's own fallback to UTF-8 for one the
/// runtime image lacks, so anything but UTF-8 means at least the console's characters differ from
/// every UTF-8 reader's. genKeys.sh and verifyKey.sh refuse before starting a JVM, by `locale
/// charmap`, a stricter probe, after parsing their arguments; this is the launchers' own rule,
/// checked before anything else runs. The advice names the POSIX knobs, where the wrappers run;
/// a host whose charset is self-consistent but not UTF-8 is refused like any other, as the
/// scripts would refuse it.
final class HostEncoding {

  static final String PROPERTY = "native.encoding";
  /// The exit status of a refused run, the scripts' for the same condition.
  static final int STATUS = 2;

  private HostEncoding() {
  }

  /// The two spellings the scripts accept, in any case.
  static boolean utf8(final String nativeEncoding) {
    return "UTF-8".equalsIgnoreCase(nativeEncoding) || "UTF8".equalsIgnoreCase(nativeEncoding);
  }

  /// The line to print when `nativeEncoding` cannot carry a password, null when it can.
  static String refusal(final String nativeEncoding) {
    return utf8(nativeEncoding) ? null
        : "FAIL: Encrypted-key passwords require a UTF-8 locale (" + PROPERTY + " is " + nativeEncoding
        + "); select an installed UTF-8 locale using LANG or LC_ALL.";
  }

  /// The refusal for this JVM's host, null when it can carry a password.
  static String refusal() {
    return refusal(System.getProperty(PROPERTY));
  }
}
