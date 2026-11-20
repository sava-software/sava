# Dependency verification

Gradle checks the signature or checksum of every artifact and metadata file it resolves
before using it, settings plugins included, and stops the build when a check fails. The
mode is strict by default. Never switch it off, or to lenient, to get past a failure; the
exceptions are the local sava-build flow and the one run that resolves a refused sibling
`includeBuild`, both below.

## One policy for every build

`gradle/verification-metadata.xml` (the policy) and `gradle/verification-keyring.keys` (the
public keys it names) serve every build in this repository. Gradle reads only the invoking
build's own `gradle/` directory, so `jmh/gradle/` and `soak/gradle/` hold symlinks to these
two files: `./gradlew`, `./gradlew -p jmh` and `./gradlew -p soak` enforce the same policy.
Included builds such as `gradle/plugins` are covered by the build that includes them, and
`sava-vanity`'s Docker image copies the policy, the keyring and the version-scoped list into
its build stage, where the guard gates its jlink `image` task.

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
  Jazzer keys match their publishers' key lists; seven keys (jmh, javaparser, gson, airlift,
  spock, assertj and kotlinx) rest on gradle/gradle's corroboration alone, and the
  version-scoped list's header names them. Gradle's
  own publishing key also covers the Gradle sources IntelliJ downloads for editing build
  scripts. What counts as a publisher source, and when gradle/gradle's corroboration stands in
  for one, is sava-build's README ("The policy and what it trusts").
- **Keys listed in [`version-scoped-keys.txt`](version-scoped-keys.txt)**, trusted only for
  the exact versions in the policy. These are the PIT and ArcMutate signers, for whose
  publisher no source was found, and keys that signed old releases and must not vouch for new
  ones: expired keys, DSA-1024 keys and retired release managers' keys.
- **SHA-256 checksums** for artifacts that are not signed, such as the jmh plugin's jar,
  module metadata, marker POM and sources jar and the kotlin-dsl marker from the Gradle
  Plugin Portal, and the antlr libraries PIT depends on. Since sava-build 21.8.5 the root
  build resolves neither the jmh plugin nor jmh-core's chain; only `-p jmh` does, so a trim
  judged from the root build alone would drop entries the jmh build still needs.
- **Nothing else.** Key servers are disabled, so keys come only from the committed keyring,
  and there are no ignored keys or trusted-artifact exemptions.

No ordinary build resolves the dependency-analysis plugin either. Since sava-build 21.8.8,
which stopped shipping it, the root `settings.gradle.kts` puts the plugin on the settings
classpath only under `-PdependencyAudit`, for the release checklist's
`checkModuleDirectivesScope` (`AGENTS.md`, "Where the gate runs"). The policy keeps the
plugin's chain for that run. Measured at the 21.8.9 adoption (plugin 3.20.4) from an
empty Gradle home, the audit run alone meets the rules for `com.autonomousapps`,
`com.squareup.okio`, `com.squareup.moshi` and `com.squareup:kotlinpoet`/`kotlinpoet-jvm`
(one Square key), `dev.zacsweers.moshix`, `com.github.ben-manes.caffeine:caffeine`,
`com.google.guava` and `com.google.j2objc:j2objc-annotations`, and the exact-version rules
for `com.google.guava:guava-parent:26.0-android` and `org.sonatype.oss:oss-parent:9`, whose
keys are on the version-scoped list; the chain's errorprone, jspecify and Kotlin modules
fall under rules the ordinary builds still meet through other versions or modules. A trim
judged without
an audit run would drop the audit's rules, and the release checklist would be the first to
meet the refusal; a write run without the property keeps them, since Gradle never removes
an entry, but neither drafts nor proves anything of that chain.

`verificationPolicy`, the task sava-build's settings plugin registers on the root project
with every project's `check`, `qualityGate` and jlink `image` task depending on it, enforces
these rules and the symlinks, so a regeneration cannot change them unseen. It also refuses a
committed `gradle.properties` that relaxes the verification mode, in the root or in a
standalone build, and a keyring, version-scoped list or standalone-build links left behind
without the policy
file. The root `gradle.properties` carries `org.gradle.dependency.verification=strict`, the
policy's declaration: nothing for Gradle, which defaults to strict with the file and verifies
nothing without it, and the line the guard requires, so that a deleted or uncommitted policy
fails `check`. It reads only committed files, so a mode passed on the command line or set in
`~/.gradle/gradle.properties` is beyond its reach. sava-build's README ("The guard") lists
every rule; this repository carried its own copy of the task until sava-build 21.8.0 shipped
the guard.

## Updating after a dependency, plugin or tool change

Aircompressor 3.14's parent imports Jetty 12.1.14 BOMs signed by Olivier Lamy's
`716EE302674CDBB2E660E1B44DB5EA09F2E3C800` key. Its fingerprint is authenticated by
[Jetty's release-specific key list](https://github.com/jetty/jetty.project/blob/jetty-12.1.14/KEYS.txt);
the policy scopes it to `jetty-bom` and `jetty-ee11-bom` only.

1. Run the change through all three builds, and through the root build's audit run, the only
   one that resolves the dependency-analysis plugin; CI runs only the root build's `check`.

   ```sh
   ./gradlew --no-configuration-cache check resolveVerificationTools
   ./gradlew --no-configuration-cache -PdependencyAudit checkModuleDirectivesScope
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
   - **A new release signed by a key that must not vouch** (an expired, DSA-1024 or retired
     release manager's key): stop and find out why an old key signed a new release. Do not
     extend its version. An existing old release that a dependency change brings into the
     graph under one of these keys can be added as another exact version once checked. A
     key that a trim dropped comes back from git history, its exact-version rule, its list
     line and its keyring block together, never from a write run: step 3 consults key
     servers while writing and drafts the key as an exact-version rule without its list line
     or as per-artifact `<pgp>` entries, which `verificationPolicy` refuses. Its notice puts
     this restore first since sava-build 21.8.4; the widening fixes it offers after it, drop
     the version or trust the publisher's group or module, are for a current key the
     publisher confirms or gradle/gradle corroborates, and taken for an old release
     manager's or a weak key they would hand it the whole module. A chain trimmed in
     a commit of its own comes back alone:

     ```sh
     git show <trim commit> -- gradle/verification-metadata.xml gradle/verification-keyring.keys \
       gradle/verification/version-scoped-keys.txt | git apply -R
     ```

     The JMH 1.36 chain (commons-math3 3.2, commons-parent 28, apache 13), which only a
     `-PjmhVersion=1.36` run of the jmh build meets, left this way after sava-build 21.8.3
     moved the harness to 1.37. A library signer the list holds as unauthenticated, should
     one ever join the two tool signers, follows the tool bump above instead.
   - **A new signing key**: confirm the fingerprint from the publisher (its KEYS file, README
     or site, or a maintainer's GitHub-registered key), never from a key server alone, then
     add a `trusted-key` scoped to the publisher's group or module, by its 40-hex fingerprint
     (Gradle refuses a shorter id), and add the public key to the keyring: step 3 downloads a
     missing key while writing and `--export-keys` writes it there. With no publisher source,
     sava-build's README (step 2 of "Adopting and updating") says when gradle/gradle's
     corroboration keeps the key version-free and when it goes on the list instead.
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
   A `<sha256 ... origin="Generated by Gradle"/>` with no `reason` can be a writer artifact
   rather than a finding. Drop it only once that file's own detached signature is shown to
   verify under a trusted-key rule the policy already has; a missing reason shows nothing,
   and a file without a signature of its own is a pin to review like any other. Show it
   directly: the cached file whose SHA-256 equals the drafted value, its `.asc` (the Gradle
   cache keeps it under the same version, in a directory of its own), the committed keyring,
   and a rule whose scope covers the key that signed, primary or subkey. GnuPG will not import
   Gradle's exported keyring, which carries no user-ID self-signatures; an OpenPGP library
   such as the Bouncy Castle in Gradle's distribution reads it as it is. A strict run without
   the line shows it only if that run resolved that version and file name, went on to read
   artifact files, and no other entry pins the file. Gradle queues each check as it resolves
   and runs the queue only when the build next reads an artifact's file, with nothing at the
   end of the build, so a task that only prints the graph proves nothing. The 21.8.9
   adoption's cold runs, which compile, resolved `jetty-bom` 12.1.14, which says nothing
   about the 12.1.15 that was drafted. By Gradle 9.9.1's source, the writer
   drafts such a line when one metadata file reaches it twice, from the metadata cache and
   from a fresh parse: a write run that is the first build to parse a POM whose parent or
   imported BOM is already cached, or a module cached from one repository and parsed fresh
   from another. At sava-build 21.8.8 and 21.8.9
   `check` realizes `recommendModuleVersions`, whose newest-release classpath only a write
   run resolves, so a write can draft such lines again whenever a newer release appears
   upstream (sava-build `f503b19` stops the realization, and `f6c69fd` puts the rule in its
   README's step 3). The 2026-11-20 entry in `HARDENING_NOTES.md` records the reproduction.
   Gradle never removes a stale entry, and it downloads missing keys from the default key
   servers while writing, even with key servers disabled for verification (only `--offline`
   stops it), so authenticate any key it adds. Run it without `-PsavaBuildLocalRepo`: with
   the property it pins the unsigned `0.0.0-test` publication, which `verificationPolicy`
   rejects. A write run sees the dependency-analysis plugin's chain only with
   `-PdependencyAudit` added (`build` then includes the scope check), so a draft that has to
   cover that chain, a new plugin version's above all, is a second run with the property.
   An entry no build resolves any more, the audit run counted among the builds, is trimmed
   by hand: its rule, its list line and the keyring block no remaining rule names, cut with
   one adjacent blank line (the one before it for the file's last block; the export drops a
   blank line left at the end), then proved by write runs from the root, without and with
   `-PdependencyAudit`, `-p jmh` and `-p soak` that change nothing, each standalone
   build's keyring link re-created afterwards, and by step 4's cold run. The root
   build has not resolved the JMH Gradle plugin since sava-build 21.8.5, so the plugin's
   jar, module metadata and marker POM are drafted only by the same write run from `-p jmh`
   with its step-1 task, `jmhJar` (a new harness version's chain by that run with
   `-PjmhVersion=<v>` added; a chain trimmed in its own commit, such as JMH 1.36's, comes
   back from git history as step 2 says), after which `jmh/gradle/verification-keyring.keys`
   is re-created as a link. No write run drafts the plugin's sources jar: an IntelliJ sync
   of `jmh/` is the only thing that resolves it.

4. A warm cache can hide parent-POM metadata that a cold one fetches. Validate a new parent
   or BOM, and every trim (an entry removed by hand because no build resolves what it
   covered), with an empty `GRADLE_USER_HOME`, running every step-1 command, the audit run
   among them: copy only the `org.gradle.java.*` lines of your `gradle.properties` into it,
   keep the environment variables those lines name (`org.gradle.java.installations.fromEnv=JAVA_HOME`
   needs `JAVA_HOME`, which `env -i` drops), and pass package credentials as
   `ORG_GRADLE_PROJECT_` environment variables. A cold run resolves and verifies every
   class path and re-runs some compile and generator tasks and, in the audit run,
   dependency-analysis tasks, but task history lives in each build's own `.gradle`
   directory, so the tests stay up-to-date, and the run proves resolution, not a test run.
   Offline use needs an online run first; cached signature files alone do not
   prove an offline run can resolve them. CI's fork pull requests build offline from the
   cache that main's last build saved, so the first one after a policy change waits for
   main's next build to warm that cache with the signature files.

5. After a policy edit, run `./gradlew verificationKeyStatus` from the root. It asks the
   network, so it sits outside `check`: it fetches every key the policy trusts from
   keys.openpgp.org and keyserver.ubuntu.com, reads revocation and expiry from the copies
   they hold, re-reads gradle/gradle's policy for the keys the version-scoped list's header
   trusts on corroboration alone, and writes `build/reports/verification/key-status.tsv`.
   A revoked key still trusted without a version, or a key a server left undecided, fails
   it. An expired key, a revoked key already at exact versions, a key no server holds, an
   expired or revoked signing subkey of a current key, a key whose expiry no server copy
   tells, a withdrawn corroboration, and a gradle/gradle policy that could not be fetched
   to check corroboration warn. Gradle itself checks neither expiry nor revocation, which is
   why the task exists. A subkey warning concerns the publisher's key only: a rule that names
   the primary does not trust that subkey's signatures, so the build refuses them (the
   controls below exercise a current subkey; by Gradle's source a subkey's state does not
   enter that check). The release checklist runs it beside `hardeningCertify`.

No step needs the IDE: no IntelliJ sync is owed after a policy change, and a session does
not ask for one. A sync resolves through the same policy, so it can refuse an artifact and
never admit one to a build. The known artifacts only a sync resolves are the Gradle sources
zip, under the `gradle:gradle` rule, Groovy's sources, under the `org.apache.groovy` key,
and, in a sync of `jmh/` opened as its own Gradle project, the jmh plugin's unsigned
sources jar, whose pin moves with the plugin (the root build has not resolved the plugin
since sava-build 21.8.5). A trim keeps those entries. A session that removes or narrows one
of them, moves the plugin's pins without the sources jar's, or bumps the wrapper says so in
its report; a refusal shows in the next sync's output. sava-build's README (the closing
paragraph of "Adopting and updating") says the same for every consumer.

A **wrapper bump** also moves the unsigned `org.gradle.kotlin.kotlin-dsl.gradle.plugin`
marker, whose version the wrapper decides: replace its pin in the same commit as
`distributionSha256Sum`.

## Unpublished sava-build

The `0.0.0-test` artifacts that `-PsavaBuildLocalRepo` resolves are unsigned local build
output, so the strict default refuses them. Pass `--dependency-verification lenient` with
the property; it reports the refusals instead of failing. Use it for that flow, and for the
one run that resolves an `includeBuild` of a sibling repository the policy refuses, and
nowhere else (sava-build's `tools/local-fuzz.sh` passes it for every consumer run and fails
if anything else is refused), and never add trust for `0.0.0-test` to the policy
(`verificationPolicy` rejects a `component` pin of it, which a regeneration run with the
property writes). Keep the flag with the property: with the property in
`~/.gradle/gradle.properties`, every command needs
the flag.
Never put `org.gradle.dependency.verification` itself in `~/.gradle/gradle.properties`:
it would relax every build on the machine, where `verificationPolicy` cannot see it.

## Engine controls

`VerificationControls.java` exercises Gradle's verification engine with disposable keys,
artifacts and a loopback repository: checksum and signature refusals, untrusted and missing
keys, key scoping, a subkey's signature trusted only through a rule that names the subkey,
artifact-level pins, policy ownership in composite builds, and refusal before settings code
runs. Re-run it after a Gradle wrapper bump:

```sh
java --source 25 gradle/verification/VerificationControls.java --work-dir <new directory>
```

It needs GnuPG and the installed wrapper distribution, uses private Gradle and GnuPG homes,
reads no credentials and writes no shared cache. It tests the engine, not this repository's
policy; `verificationPolicy` and the builds above do that.

See the [Gradle manual](https://docs.gradle.org/current/userguide/dependency_verification.html)
and the "Verifying sava artifacts" section of sava-build's README.
