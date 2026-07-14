package software.sava.core.accounts.token.extensions;

public sealed interface AccountTokenExtension extends TokenExtension permits
    ConfidentialTransferAccount,
    ConfidentialTransferFeeAmount,
    TransferFeeAmount,
    ImmutableOwner,
    MemoTransfer,
    CpiGuard,
    NonTransferableAccount,
    TransferHookAccount,
    PausableAccount {
}
