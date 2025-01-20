![Sava](assets/images/solana_java_cup.svg)

# Sava [![Build](https://github.com/sava-software/sava/actions/workflows/gradle.yml/badge.svg)](https://github.com/sava-software/sava/actions/workflows/gradle.yml) [![Release](https://github.com/sava-software/sava/actions/workflows/release.yml/badge.svg)](https://github.com/sava-software/sava/actions/workflows/release.yml)

### Features

- HTTP and WebSocket JSON RPC Clients.
- Transaction (de)serialization.
    - Legacy
    - V0
- Utilities for elliptic curve Ed25519 and Solana accounts.
- Borsh (de)serialization.

### Requirements

- The latest generally available JDK. This project will continue to move to the latest and will not maintain
  versions released against previous JDK's.

### Dependencies

- [Core](core/src/main/java/module-info.java)
    - java.base
    - [Bouncy Castle](https://www.bouncycastle.org/download/bouncy-castle-java/#latest)

- [RPC](rpc/src/main/java/module-info.java)
    - software.sava.core
    - java.net.http
    - [JSON Iterator](https://github.com/comodal/json-iterator?tab=readme-ov-file#json-iterator)

### Add Dependency

Create
a [GitHub user access token](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens#creating-a-personal-access-token-classic)
with read access to GitHub Packages.

Then add the following to your Gradle build script.

```groovy
repositories {
  maven {
    url = "https://maven.pkg.github.com/sava-software/sava"
    credentials {
      username = GITHUB_USERNAME
      password = GITHUB_PERSONAL_ACCESS_TOKEN
    }
  }
}

dependencies {
  implementation "software.sava:sava-core:$VERSION"
  implementation "software.sava:sava-rpc:$VERSION"
}
```

### Contribution

Unit tests are needed and welcomed. Otherwise, please open a [discussion](https://github.com/sava-software/sava/discussions), issue, or send an email before working on a pull request.

### Disclaimer

In addition to the MIT License, this project is under active development and breaking changes are to be expected.

### [Examples](https://github.com/sava-software/sava/tree/main/examples/src/main/java/software/sava/examples)

#### Get & Parse Accounts

More parsers are available for programs which have Anchor IDL's in the [anchor-programs project](https://github.com/sava-software/anchor-programs).

```java
try (final var httpClient = HttpClient.newHttpClient()) {

    final var rpcClient = SolanaRpcClient.createClient(SolanaNetwork.MAIN_NET.getEndpoint(), httpClient);
    
    final var accountInfoFuture = rpcClient.getAccountInfo(
        PublicKey.fromBase58Encoded("SPc3dYPMXGM6Do5zakpUESxBLadBNDWQAJ6ww6QZALT"), 
        AddressLookupTable.FACTORY
    );
    
    final var accountInfo = accountInfoFuture.join();
    final AddressLookupTable table = accountInfo.data();
    System.out.println(table);
}
```

#### Memory Compare Filter Program Accounts

Retrieve all lookup tables which are active and frozen.

Note: You will need access to an RPC node which has getProgramAccounts enabled, such as [Helius](helius.dev).

```java
try (final var httpClient = HttpClient.newHttpClient()) {

    final var rpcClient = SolanaRpcClient.createClient(
        URI.create("https://mainnet.helius-rpc.com/?api-key="),
        httpClient
    );
    
    final byte[] stillActive = new byte[Long.BYTES];
    ByteUtil.putInt64LE(stillActive, 0, Clock.MAX_SLOT);
    final var activeFilter = Filter.createMemCompFilter(DEACTIVATION_SLOT_OFFSET, stillActive);
    final var noAuthorityFilter = Filter.createMemCompFilter(AUTHORITY_OPTION_OFFSET, new byte[]{0});
    
    final var accountInfoFuture = rpcClient.getProgramAccounts(
        SolanaAccounts.MAIN_NET.addressLookupTableProgram(),
        List.of(
            // Filter all active frozen tables.
            activeFilter,
            noAuthorityFilter
        ),
        AddressLookupTable.FACTORY
    );
    
    final var accountInfoList = accountInfoFuture.join();
    accountInfoList.stream()
        .map(AccountInfo::data)
        .forEach(System.out::println);
        
    System.out.format("Retrieved %d tables which are active and frozen.%n", accountInfoList.size());  
}
```

#### Stream Program Accounts

##### Token Accounts

```java 
final var solanaAccounts = SolanaAccounts.MAIN_NET;
final var tokenProgram = solanaAccounts.tokenProgram();
final var tokenOwner = PublicKey.fromBase58Encoded("");
try (final var httpClient = HttpClient.newHttpClient()) {
  final var webSocket = SolanaRpcWebsocket.build()
      .uri(SolanaNetwork.MAIN_NET.getWebSocketEndpoint())
      .webSocketBuilder(httpClient)
      .commitment(Commitment.CONFIRMED)
      .solanaAccounts(solanaAccounts)
      .onOpen(ws -> System.out.println("Websocket connected to " + ws.endpoint()))
      .create();

  webSocket.programSubscribe(
      tokenProgram,
      List.of(
          Filter.createDataSizeFilter(TokenAccount.BYTES),
          Filter.createMemCompFilter(TokenAccount.OWNER_OFFSET, tokenOwner)
      ),
      accountInfo -> {
        final var tokenAccount = TokenAccount.read(accountInfo.pubKey(), accountInfo.data());
        System.out.println(tokenAccount);
      });

  webSocket.connect();
}
```

##### Address Lookup Tables

Subscribe to the Address Lookup Table Program.

```java
try (final var httpClient = HttpClient.newHttpClient()) {

    final var webSocket = SolanaRpcWebsocket.build()
        .uri(SolanaNetwork.MAIN_NET.getWebSocketEndpoint())
        .webSocketBuilder(httpClient)
        .commitment(Commitment.CONFIRMED)
        .onClose((_, statusCode, reason) -> System.out.format("%d: %s%n", statusCode, reason))
        .onError((_, throwable) -> throwable.printStackTrace())
        .create();

    webSocket.programSubscribe(SolanaAccounts.MAIN_NET.addressLookupTableProgram(), accountInfo -> {
        final var table = AddressLookupTable.read(accountInfo.pubKey(), accountInfo.data());
        //  {
        //    "address": "Fuy3RVz8u2pNaeGLHG7wp8eBg7JA3vzBcDB82PXTGAHz",
        //    "deactivationSlot": 18446744073709551615,
        //    "lastExtendedSlot": 283587161,
        //    "lastExtendedSlotStartIndex": 0,
        //    "authority": "C5vPh26poBM8mYbdEnDMWGMhcrQrb84eULChZKkExDG1",
        //    "accounts": "[675MHPyK53QJUNWyjYb3gyse5podtFRdsj61ssRcPEUr, C5vPh26poBM8mYbdEnDMWGMhcrQrb84eULChZKkExDG1, 5hvPX4JoMwKCoYQomQRF4jutJ3JDraj2rGoWsvUZGtyN, 2TzAT9jnyAxna1N2hASrPXftHyVU9mByCHMNKTLtRX82]"
        //  }
        System.out.println(table);
    });
    
    webSocket.connect();
}
```

## Private Key Pair Parsing

If the base58 encoded public key is configured via `address` it will be used to validate the public key derived 
from the private key.

### Usage

```java
var jsonConfig = "";
var ji = JsonIterator.parse(jsonConfig);
var signer = PrivateKeyEncoding.fromJsonPrivateKey(ji);
```

### JSON Configuration

#### Solana CLI Generated Key Pair JSON Array

By default, if only an array is configured, the assumption is that it is a key pair generated by the Solana CLI.

```json
[45,8,240,...,23,243,113]
```
#### JSON Array

```json
{
  "pubKey": "<BASE58_ENCODED_PUBLIC_KEY>",
  "encoding": "jsonKeyPairArray",
  "secret": []
}
```
#### Key Pair

Encoded 64 byte private/public key pair.  Derived public key will be validated.

* base64KeyPair
* base58KeyPair

```json
{
  "pubKey": "<BASE58_ENCODED_PUBLIC_KEY>",
  "encoding": "base64KeyPair",
  "secret": "asdf=="
}
```

#### Private Key Only

Encoded 32 byte private key.

* base64PrivateKey
* base58PrivateKey
