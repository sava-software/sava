package software.sava.core.accounts.token;

public enum ExtensionType {

  /// Used as padding if the account size would otherwise be 355, same as a multisig
  Uninitialized,
  /// Includes transfer fee rate info and accompanying authorities to withdraw and set the fee
  TransferFeeConfig,
  /// Includes withheld transfer fees
  TransferFeeAmount,
  /// Includes an optional mint close authority
  MintCloseAuthority,
  /// Auditor configuration for confidential transfers
  ConfidentialTransferMint,
  /// State for confidential transfers
  ConfidentialTransferAccount,
  /// Specifies the default Account::state for new Accounts
  DefaultAccountState,
  /// Indicates that the Account owner authority cannot be changed
  ImmutableOwner,
  /// Require inbound transfers to have memo
  MemoTransfer,
  /// Indicates that the tokens from this mint can't be transferred
  NonTransferable,
  /// Tokens accrue interest over time,
  InterestBearingConfig,
  /// Locks privileged token operations from happening via CPI
  CpiGuard,
  /// Includes an optional permanent delegate
  PermanentDelegate,
  /// Indicates that the tokens in this account belong to a non-transferable mint
  NonTransferableAccount,
  /// Mint requires a CPI to a program implementing the "transfer hook" interface
  TransferHook,
  /// Indicates that the tokens in this account belong to a mint with a transfer hook
  TransferHookAccount,
  /// Includes encrypted withheld fees and the encryption public that they are encrypted under
  ConfidentialTransferFeeConfig,
  /// Includes confidential withheld transfer fees
  ConfidentialTransferFeeAmount,
  /// Mint contains a pointer to another account (or the same account) that holds metadata
  MetadataPointer,
  /// Mint contains token-metadata
  TokenMetadata,
  /// Mint contains a pointer to another account (or the same account) that holds group configurations
  GroupPointer,
  /// Mint contains token group configurations
  TokenGroup,
  /// Mint contains a pointer to another account (or the same account) that holds group member configurations
  GroupMemberPointer,
  /// Mint contains token group member configurations
  TokenGroupMember
}
