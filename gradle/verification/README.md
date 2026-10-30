# Dependency verification

Gradle checks the signature or checksum of every artifact and metadata file it resolves
before using it, settings plugins included, and stops the build when a check fails. The
mode is strict by default. Never switch it off, or to lenient, to get past a failure; the
one exception is the local sava-build flow below.

## One policy for every build

`gradle/verification-metadata.xml` (the policy) and `gradle/verification-keyring.keys` (the
public keys it names) serve every build in this repository. Gradle reads only the invoking
build's own `gradle/` directory, so `jmh/gradle/` and `soak/gradle/` hold symlinks to these
two files: `./gradlew`, `./gradlew -p jmh` and `./gradlew -p soak` enforce the same policy.
Included builds such as `gradle/plugins` are covered by the build that includes them, and
`sava-vanity`'s Docker image copies both files into its build stage.

Not covered: the wrapper distribution, which `distributionSha256Sum` in
`gradle/wrapper/gradle-wrapper.properties` pins separately, JDK downloads, GitHub Actions and
shell downloads. A verified artifact is the publisher's, not necessarily free of
vulnerabilities: keep patching dependencies, and use `verifySavaAttestations` for the
provenance of sava's own artifacts.

## What the policy trusts

- **Publisher keys**, each scoped to the group or module it signs and to no version, so a new
  release signed by the same key passes without an edit. Most of these keys also appear in
  gradle/gradle's own policy. The Sava release key `01870AD9C9DFBB1F3502D06FB89447F3AD5E2ABF`
  is the one sava-build's README documents, and the Jetty, OpenTelemetry, Apache parent and
  Jazzer keys match their publishers' key lists. Gradle's own publishing key also covers the
  Gradle sources IntelliJ downloads for editing build scripts.
- **Keys listed in [`version-scoped-keys.txt`](version-scoped-keys.txt)**, trusted only for
  the exact versions in the policy. These are the PIT and ArcMutate signers, whose publisher
  identity could not be authenticated (no publisher-published fingerprint, GitHub-registered
  key or verified key-server identity), and keys that signed old releases and must not vouch
  for new ones: expired keys, DSA-1024 keys and retired release managers' keys.
- **SHA-256 checksums** for artifacts that are not signed, such as the jmh plugin and the
  kotlin-dsl marker from the Gradle Plugin Portal and the antlr libraries PIT depends on.
- **Nothing else.** Key servers are disabled, so keys come only from the committed keyring,
  and there are no ignored keys or trusted-artifact exemptions.

`verificationPolicy`, the task sava-build's settings plugin registers on the root project
with every project's `check` and `qualityGate` depending on it, enforces these rules and the
symlinks, so a regeneration cannot change them unseen. It also refuses a committed
`gradle.properties` that relaxes the verification mode, in the root or in a standalone build,
and a keyring, version-scoped list or standalone-build links left behind without the policy
file. The root `gradle.properties` carries `org.gradle.dependency.verification=strict`, the
policy's declaration: nothing for Gradle, which defaults to strict with the file and verifies
nothing without it, and the line the guard requires, so that a deleted or uncommitted policy
fails `check`. It reads only committed files, so a mode passed on the command line or set in
`~/.gradle/gradle.properties` is beyond its reach. sava-build's README ("The guard") lists
every rule; this repository carried its own copy of the task until sava-build 21.8.0 shipped
the guard.

## Updating after a dependency, plugin or tool change

1. Run the change through all three builds; CI runs only the root one.

   ```sh
   ./gradlew --no-configuration-cache check resolveVerificationTools
   ./gradlew -p jmh jmhJar
   ./gradlew -p soak classes jar soakModulePath
   ```

   `resolveVerificationTools`, registered by sava-build's hardening feature in each hardened
   project, resolves the PIT, ArcMutate and Jazzer artifacts that `check` never touches,
   without running a mutation or fuzz campaign. Without the configuration cache: a reused
   entry resolves nothing, and so verifies nothing. ArcMutate is on that classpath
   only where `arcmutate-licence.txt` is present, so run this step on a licensed machine: on
   another, the ArcMutate rule goes unexercised and a licensed `pitest<Suite>` gate is the
   first to refuse a bump.

2. A "Dependency verification failed" report names each refused artifact and the reason.
   - **A PIT or ArcMutate bump**: check the release, then change the `version` on that key's
     rule.
   - **A new release signed by any other key in `version-scoped-keys.txt`** (an expired,
     DSA-1024 or retired release manager's key): stop and find out why an old key signed a
     new release. Do not extend its version. An existing old release that a dependency
     change brings into the graph under one of these keys can be added as another exact
     version once checked.
   - **A new signing key**: confirm the fingerprint from the publisher (its KEYS file, README
     or site), never from a key server alone, then add a `trusted-key` scoped to the
     publisher's group or module, by its 40-hex fingerprint (Gradle refuses a shorter id), and
     add the public key to the keyring: step 3 downloads a missing key while writing and
     `--export-keys` writes it there.
   - **An unsigned artifact**: review it, then pin its SHA-256 in a `component` entry.

3. Gradle can draft these entries. Run it on a clean tree from the checkout root (from
   `-p jmh` or `-p soak` it replaces the keyring symlink with a copy), then keep only what
   you reviewed:

   ```sh
   ./gradlew --no-configuration-cache --write-verification-metadata pgp,sha256 --export-keys \
     build resolveVerificationTools
   ```

   The result is a candidate. On a tool bump it widens the version-scoped key to every
   version, and it can narrow a publisher key to the one version it saw, as a rule with a
   version or as a per-artifact `<pgp>` entry. `verificationPolicy` rejects all of these and
   names the fix, as it does for ignored-key fallbacks, exemptions and a replaced symlink.
   Gradle never removes a stale entry, and it downloads missing keys from the default key
   servers while writing, even with key servers disabled for verification (only `--offline`
   stops it), so authenticate any key it adds. Run it without `-PsavaBuildLocalRepo`: with the
   property it pins the unsigned `0.0.0-test` publication, which `verificationPolicy` rejects.

4. A warm cache can hide parent-POM metadata that a cold one fetches. Validate a new parent
   or BOM once with an empty `GRADLE_USER_HOME`: copy only the `org.gradle.java.*` lines of
   your `gradle.properties`, and pass package credentials as `ORG_GRADLE_PROJECT_`
   environment variables. Offline use needs an online run first; cached signature files
   alone do not prove an offline run can resolve them. CI's fork pull requests build offline
   from the cache that main's last build saved, so the first one after a policy change waits
   for main's next build to warm that cache with the signature files.

5. Sync IntelliJ as well. It downloads sources through the same policy, including artifacts
   no command-line build resolves: the Gradle sources zip, Groovy's sources and the jmh
   plugin's unsigned sources jar, whose pin moves with the plugin. A refusal shows in the
   sync output.

A **wrapper bump** also moves the unsigned `org.gradle.kotlin.kotlin-dsl.gradle.plugin`
marker, whose version the wrapper decides: replace its pin in the same commit as
`distributionSha256Sum`.

## Unpublished sava-build

The `0.0.0-test` artifacts that `-PsavaBuildLocalRepo` resolves are unsigned local build
output, so the strict default refuses them. Pass `--dependency-verification lenient` with
the property; it reports the refusals instead of failing. Use it for that flow only
(sava-build's `tools/local-fuzz.sh` passes it for every consumer run and fails if anything
else is refused), and never add trust for `0.0.0-test` to the policy (`verificationPolicy`
rejects a `component` pin of it, which a regeneration run with the property writes). Keep the
flag with the property: with the property in `~/.gradle/gradle.properties`, every command needs
the flag.
Never put `org.gradle.dependency.verification` itself in `~/.gradle/gradle.properties`:
it would relax every build on the machine, where `verificationPolicy` cannot see it.

## Engine controls

`VerificationControls.java` exercises Gradle's verification engine with disposable keys,
artifacts and a loopback repository: checksum and signature refusals, untrusted and missing
keys, key scoping, artifact-level pins, policy ownership in composite builds, and refusal
before settings code runs. Re-run it after a Gradle wrapper bump:

```sh
java --source 25 gradle/verification/VerificationControls.java --work-dir <new directory>
```

It needs GnuPG and the installed wrapper distribution, uses private Gradle and GnuPG homes,
reads no credentials and writes no shared cache. It tests the engine, not this repository's
policy; `verificationPolicy` and the builds above do that.

See the [Gradle manual](https://docs.gradle.org/current/userguide/dependency_verification.html)
and the "Verifying sava artifacts" section of sava-build's README.
