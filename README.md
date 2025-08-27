![Sava](assets/images/solana_java_cup.svg)

# Sava [![Gradle Check](https://github.com/sava-software/sava/actions/workflows/build.yml/badge.svg)](https://github.com/sava-software/sava/actions/workflows/build.yml) [![Publish Release](https://github.com/sava-software/sava/actions/workflows/publish.yml/badge.svg)](https://github.com/sava-software/sava/actions/workflows/publish.yml)

## Documentation

User documentation lives at [sava.software](https://sava.software/).

* [Dependency Configuration](https://sava.software/quickstart)
* [Core](https://sava.software/libraries/core): Common Solana cryptography and serialization utilities.
* [RPC](https://sava.software/libraries/rpc): HTTP and WebSocket Clients.

## Contributions

Please note that all contributions require agreeing to
the [Sava Engineering, Inc. CLA](https://gist.github.com/jpe7s/09546e42783187c6d04f38e04184ecfa).

Tests are needed and welcomed. Otherwise, [please reach out](https://github.com/sava-software) before working on a pull
request.

### RPC Tests

A mini framework for testing RPC calls is provided to make it as easy as possible to test the calls you rely on.
See [RoundTripRpcRequestTests](sava-rpc/src/test/java/software/sava/rpc/json/http/client/RoundTripRpcRequestTests.java)
for example usage.

* If you plan to add several tests, create a new class to avoid merge conflicts.
* If you feel there is already enough response test coverage, you can [skip it by only providing the expected request
  JSON.](https://github.com/sava-software/sava/blob/55e41207d932708affd05be54168f6bfb6105ec6/sava-rpc/src/test/java/software/sava/rpc/json/http/client/RoundTripRpcRequestTests.java#L30)
  * See [ParseRpcResponseTests](sava-rpc/src/test/java/software/sava/rpc/json/http/client/ParseRpcResponseTests.java)
    for additional response parsing tests.
* If the response JSON is large, add it to the [resource](sava-rpc/src/test/resources/rpc_response_data) directory as a
  JSON file.
  * If the JSON file is larger than 1MB, apply zip compression to it.
  * If it is large because it is a collection of items, consider trimming the list down to at least two items.

#### Capture Request JSON

* Start a test with the desired call, the test will fail with the difference between the expected and actual requests.

```java

@Test
void getHealth() {
  registerRequest("{}");
  rpcClient.getHealth().join();
}
```

```text
SEVERE: Expected request body does not match the actual. Note: The JSON RPC "id" does not matter.
 - expected: {}
 - actual:   {"jsonrpc":"2.0","id":123,"method":"getHealth"}
```

* Or, enable debug logging by using a [logging.properties](logging.properties) file and pass it to the VM via:

``` 
-Djava.util.logging.config.file=logging.properties
```

#### Capture Response JSON

```java
var rpcClient = SolanaRpcClient.createClient(
    rpcEndpoint,
    httpClient,
    response -> {
      final var json = new String(response.body());
      System.out.println(json); // Write to a file if large.
      return true;
    }
);
```

#### Validation

Reference the [official Solana RPC documentation](https://solana.com/docs/rpc/http) to verify that the expected
parameters are passed in the request, and if applicable, all the desired response data is parsed correctly.

## Build

[Generate a classic token](https://github.com/settings/tokens) with the `read:packages` scope needed to access
dependencies hosted on GitHub Package Repository.

#### ~/.gradle/gradle.properties

```properties
savaGithubPackagesUsername=GITHUB_USERNAME
savaGithubPackagesPassword=GITHUB_TOKEN
```

```shell
./gradlew check
```
