package software.sava.core.accounts.token.extensions;

public sealed interface MintTokenExtension extends TokenExtension permits
    ConfidentialMintBurn,
    ConfidentialTransferFeeConfig,
    ConfidentialTransferMint,
    DefaultAccountState,
    GroupMemberPointer,
    GroupPointer,
    InterestBearingConfig,
    MetadataPointer,
    MintCloseAuthority,
    NonTransferable,
    PausableConfig,
    PermissionedBurnConfig,
    PermanentDelegate,
    ScaledUiAmountConfig,
    TokenGroup,
    TokenGroupMember,
    TokenMetadata,
    TransferFeeConfig,
    TransferHook,
    Uninitialized {
}
