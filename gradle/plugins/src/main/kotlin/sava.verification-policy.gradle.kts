import java.nio.file.Files
import java.util.Properties
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

// gradle/verification-metadata.xml is a reviewed trust policy, and Gradle's
// --write-verification-metadata output is only a candidate for it. A regeneration can widen
// trust (it promotes artifact-level keys to group-wide rules), narrow it (a version attribute
// or a per-artifact key that fails the next bump), write ignored-key fallbacks for keys it
// could not find, replace the jmh/ and soak/ symlinks with diverging copies, and it drops XML
// comments. This task checks the policy's invariants so none of that can land unseen;
// gradle/verification/README.md says why each one holds. It reads only committed files: a
// verification mode passed on the command line or set in ~/.gradle is beyond its reach. The
// policy is repository-wide, so apply this from one module only. Interim: sava-build's
// verificationPolicy convention replaces this file once released, and discovers the standalone
// builds instead of listing them.
val verificationPolicy = tasks.register("verificationPolicy") {
  group = "verification"
  description = "Fails when the dependency verification policy breaks its invariants."
  val root = isolated.rootProject.projectDirectory.asFile
  val metadata = root.resolve("gradle/verification-metadata.xml")
  val keyring = root.resolve("gradle/verification-keyring.keys")
  val versionScopedKeys = root.resolve("gradle/verification/version-scoped-keys.txt")
  // The standalone jmh/ and soak/ builds read the root policy through symlinks, and each
  // build would read its own gradle.properties.
  val standaloneBuilds = listOf("jmh", "soak")
  val sharedCopies = standaloneBuilds.flatMap { build ->
    listOf(metadata, keyring).map { root.resolve("$build/gradle/${it.name}") to it }
  }
  val gradleProperties = (listOf(".") + standaloneBuilds).map { root.resolve(it).resolve("gradle.properties").normalize() }
  // No outputs, so the task runs on every invocation and reads the committed files as they are.
  doLast {
    // Local functions only: a script-level one would capture the script instance, which the
    // configuration cache cannot serialize.
    val problems = mutableListOf<String>()
    listOf(metadata, keyring, versionScopedKeys).filterNot { it.isFile }.forEach {
      problems += "${it.relativeTo(root)} is missing; gradle/verification/README.md describes the files the policy needs."
    }
    if (problems.isNotEmpty()) {
      throw GradleException("Dependency verification policy problems:\n" + problems.joinToString("\n") { "  - $it" })
    }
    val document = DocumentBuilderFactory.newInstance().run {
      isNamespaceAware = true
      setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      newDocumentBuilder().parse(metadata)
    }
    fun elements(name: String) = document.getElementsByTagNameNS("*", name).let { nodes ->
      (0 until nodes.length).map { nodes.item(it) as Element }
    }
    fun Element.attributesText() = (0 until attributes.length).map { attributes.item(it) }
      .filter { it.nodeName != "id" }
      .joinToString(" ") { "${it.nodeName}=\"${it.nodeValue}\"" }
    // Gradle reads these settings from the raw element text without trimming, a later element
    // overrides an earlier one, and a commented-out element leaves Gradle's default in force.
    // So each must be one active <configuration> child, checked on the parsed document, and
    // appear in the file only as the exact expected text, checked on the raw text, where
    // neither padding, an entity nor a comment can hide a value Gradle would read differently.
    val raw = metadata.readText()
    fun requireExactly(element: String, exact: String, why: String) {
      val active = elements(element)
      val single = active.size == 1 && (active[0].parentNode as? Element)?.localName == "configuration"
      if (!single || Regex("<$element[\\s/>]").findAll(raw).count() != 1 || !raw.contains(exact)) {
        problems += "$exact must appear once, as an active <configuration> setting written exactly so: $why"
      }
    }

    requireExactly("verify-metadata", "<verify-metadata>true</verify-metadata>", "otherwise POM and module metadata go unverified.")
    requireExactly("verify-signatures", "<verify-signatures>true</verify-signatures>",
        "a checksum alone cannot tell a publisher's artifact from a substitute.")
    requireExactly("keyring-format", "<keyring-format>armored</keyring-format>", "the reviewed keyring is ${keyring.relativeTo(root)}.")
    requireExactly("key-servers", "<key-servers enabled=\"false\"/>", "keys come from the committed keyring, never from a key server.")
    elements("ignored-key").forEach {
      problems += "<ignored-key id=\"${it.getAttribute("id")}\"> skips that key's signatures; regeneration writes it when a key is missing. " +
          "Authenticate the key and add it to ${keyring.relativeTo(root)} instead."
    }
    elements("trust").forEach {
      problems += "<trusted-artifacts> entry ${it.attributesText()} exempts artifacts from signature verification; pin or trust them by key instead."
    }
    elements("pgp").forEach { pgp ->
      val component = pgp.parentNode.parentNode as Element
      problems += "<pgp value=\"${pgp.getAttribute("value")}\"> trusts a key for " +
          "${component.getAttribute("group")}:${component.getAttribute("name")}:${component.getAttribute("version")} only. " +
          "Move it to a trusted-key scoped to the publisher's group or module, or to an exact-version rule for a key in ${versionScopedKeys.name}."
    }
    // A regeneration run with -PsavaBuildLocalRepo pins the unsigned local test publication.
    // (Key ids need no rule: Gradle refuses to read a trusted-key id shorter than 160 bits.)
    elements("component").filter { it.getAttribute("version") == "0.0.0-test" }.forEach {
      problems += "component ${it.getAttribute("group")}:${it.getAttribute("name")}:0.0.0-test pins the unsigned local test publication, " +
          "which a regeneration run with -PsavaBuildLocalRepo writes: remove it, and regenerate without the property."
    }
    listOf("md5", "sha1").forEach { digest ->
      elements(digest).forEach { element ->
        val artifact = element.parentNode as Element
        val component = artifact.parentNode as Element
        problems += "<$digest> on ${component.getAttribute("group")}:${component.getAttribute("name")}:${component.getAttribute("version")} " +
            "${artifact.getAttribute("name")} pins a digest with known collisions; an unsigned artifact is pinned by <sha256> only."
      }
    }

    // Gradle compares key ids without regard to case, and reads regex like Boolean.parseBoolean.
    val listed = versionScopedKeys.readLines().map(String::trim)
      .filter { it.isNotEmpty() && !it.startsWith("#") }
      .map { it.split(Regex("\\s+"), limit = 2) }
      .associate { it[0].uppercase() to it.getOrElse(1) { "" }.trim() }
    // A trusted-key's own attributes are a scope too, even when it also has <trusting> children.
    val coordinates = listOf("group", "name", "version", "file", "regex")
    val scopes = elements("trusted-key").flatMap { key ->
      val id = key.getAttribute("id").uppercase()
      val trusting = key.getElementsByTagNameNS("*", "trusting")
      val own = if (trusting.length == 0 || coordinates.any(key::hasAttribute)) listOf(id to key) else emptyList()
      own + (0 until trusting.length).map { id to trusting.item(it) as Element }
    }
    // The one regex shape allowed is the one Gradle writes: a literal group and its subgroups,
    // the group of at least two segments, since one would trust a whole top-level namespace.
    val groupAndSubgroups = Regex("""\^[A-Za-z0-9_-]+(\[\.\][A-Za-z0-9_-]+)+\(\$\|\(\[\.\]\.\*\)\)""")
    scopes.forEach { (id, scope) ->
      val version = scope.getAttribute("version")
      val regex = scope.getAttribute("regex").toBoolean()
      when {
        scope.getAttribute("group").isEmpty() ->
          problems += "trusted-key $id has a rule without a group${scope.attributesText().let { if (it.isEmpty()) "" else " ($it)" }}: " +
              "Gradle trusts a key for nothing without a group, name, version or file, and this policy wants a group. " +
              "Scope it to the publisher's group or module."
        regex && (!groupAndSubgroups.matches(scope.getAttribute("group")) || listOf("name", "version", "file").any(scope::hasAttribute)) ->
          problems += "trusted-key $id has a regex rule (${scope.attributesText()}) other than a literal group and its subgroups, " +
              "such as group=\"^com[.]example($|([.].*))\": write it without a regex, or in that form."
        scope.hasAttribute("file") ->
          problems += "trusted-key $id trusts ${scope.attributesText()} by file name; trust whole modules, and pin single files with a component entry."
        id in listed && (version.isEmpty() || regex) ->
          problems += "trusted-key $id trusts ${scope.attributesText()} beyond exact versions, but ${versionScopedKeys.name} " +
              "lists it (${listed[id]}). Pin the exact version(s) it signed, without a regex."
        id !in listed && version.isNotEmpty() ->
          problems += "trusted-key $id trusts ${scope.attributesText()} only at that version. Publisher keys stay version-free " +
              "so a bump signed by the same key needs no edit: drop the version, or list the key in ${versionScopedKeys.name} with the reason."
      }
    }
    (listed.keys - scopes.map { it.first }.toSet()).forEach {
      problems += "${versionScopedKeys.name} lists $it, which no trusted-key uses any more: remove the line."
    }

    gradleProperties.filter { it.isFile }.forEach { file ->
      val properties = Properties().apply { file.reader().use { load(it) } }
      listOf("org.gradle.dependency.verification", "systemProp.org.gradle.dependency.verification")
        .filter { properties.getProperty(it) != null && properties.getProperty(it).trim().lowercase() != "strict" }
        .forEach {
          problems += "${file.relativeTo(root)} sets $it=${properties.getProperty(it)}; verification stays strict for every committed build. " +
              "Only a -PsavaBuildLocalRepo run passes --dependency-verification lenient, on its own command line."
        }
    }

    sharedCopies.forEach { (copy, shared) ->
      val path = copy.toPath()
      if (!Files.isSymbolicLink(path) || !Files.exists(path) || path.toRealPath() != shared.toPath().toRealPath()) {
        problems += "${copy.relativeTo(root)} must be a symlink to ${shared.relativeTo(copy.parentFile)}. " +
            "Merge anything written into it into ${shared.relativeTo(root)}, then re-create the link."
      }
    }

    if (problems.isNotEmpty()) {
      throw GradleException(
        "Dependency verification policy problems (gradle/verification/README.md explains the rules):\n" +
            problems.joinToString("\n") { "  - $it" }
      )
    }
  }
}

// Wired into check, not just qualityGate, for the reason docs-in-sync is: CI runs only check.
// Matched by name, so no other task is realized to ask for its name.
tasks.named { it == "check" || it == "qualityGate" }.configureEach {
  dependsOn(verificationPolicy)
}
