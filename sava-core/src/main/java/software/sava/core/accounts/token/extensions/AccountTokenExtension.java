package software.sava.core.accounts.token.extensions;

public sealed interface AccountTokenExtension extends TokenExtension permits
    ConfidentialTransferAccount,
    ImmutableOwner,
    MemoTransfer,
    CpiGuard,
    NonTransferableAccount,
    TransferHookAccount,
    PausableAccount {
}
