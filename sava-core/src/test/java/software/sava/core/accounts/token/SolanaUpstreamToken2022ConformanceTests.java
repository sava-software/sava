package software.sava.core.accounts.token;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.sysvar.SolanaUpstreamLayoutConformanceTests;

import java.io.IOException;

/// Runs the pinned upstream Token-2022 fixture assertions inside the mutation suite that
/// owns the Token-2022 production package.
final class SolanaUpstreamToken2022ConformanceTests {

  @Test
  void token2022OrdinalsFixedLengthsAndRustAcceptanceMatch() throws IOException {
    SolanaUpstreamLayoutConformanceTests
        .assertToken2022OrdinalsFixedLengthsAndRustAcceptanceMatch();
  }

  @Test
  void tokenMetadataBorshAndTlvLengthBoundaryMatchRust() throws IOException {
    SolanaUpstreamLayoutConformanceTests
        .assertTokenMetadataBorshAndTlvLengthBoundaryMatchRust();
  }

  @Test
  void token2022NonzeroBoolBytesMatchRust() throws IOException {
    SolanaUpstreamLayoutConformanceTests
        .assertToken2022NonzeroBoolBytesMatchRust();
  }
}
