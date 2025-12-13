package software.sava.core.accounts.token.extensions;

public sealed interface MintTokenExtension extends TokenExtension permits
    ConfidentialMintBurn,
    ConfidentialTransferFeeAmount,
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
    PermanentDelegate,
    ScaledUiAmountConfig,
    TokenGroup,
    TokenGroupMember,
    TokenMetadata,
    TransferFeeAmount,
    TransferFeeConfig,
    TransferHook,
    Uninitialized {
}
