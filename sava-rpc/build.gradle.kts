plugins {
  id("software.sava.build.feature.hardening")
}

// See the equivalent block in sava-core: AGENTS.md's suite list is derivable from these
// registrations, so the build checks it rather than trusting a prose reminder.
val docsInSync = tasks.register("docsInSync") {
  group = "verification"
  description = "Fails when a registered mutation suite is not named in AGENTS.md."
  val agentsDoc = rootProject.layout.projectDirectory.file("AGENTS.md").asFile
  val suiteTasks = hardening.mutation.names.map { "pitest" + it.replaceFirstChar(Char::uppercase) }
  val projectPath = project.path
  inputs.file(agentsDoc)
  inputs.property("suites", suiteTasks)
  doLast {
    val doc = agentsDoc.readText()
    val missing = suiteTasks.filterNot(doc::contains)
    if (missing.isNotEmpty()) {
      throw GradleException(
        "AGENTS.md does not mention ${missing.size} mutation suite(s) registered by $projectPath:\n" +
            missing.joinToString("\n") { "  $it" } +
            "\nAdd them to the 'Quality gate & mutation ratchet' section, with the package each covers."
      )
    }
  }
}

tasks.named("qualityGate") { dependsOn(docsInSync) }

testModuleInfo {
  requires("jdk.httpserver")
  requires("org.junit.jupiter.api")
  runtimeOnly("org.junit.jupiter.engine")
}

hardening {
  mutation.register("responses") {
    // the hand-rolled json_iterator field predicates parse whatever an RPC node returns;
    // a compromised or buggy provider is the threat model
    targetClasses = listOf("software.sava.rpc.json.http.response.*")
    // the whitebox parser tests live inside the targeted package
    excludedClasses = listOf("software.sava.rpc.json.http.response.*Tests")
    targetTests = "software.sava.rpc.json.http.client.*Test*,software.sava.rpc.json.http.response.*Test*"
  }
}

configurations.all {
  resolutionStrategy {
    force("software.sava:json-iterator:25.3.0")
  }
}

dependencies {
  project(":sava-core")
}
