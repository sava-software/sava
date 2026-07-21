import org.gradle.api.NamedDomainObjectCollection

// AGENTS.md names the mutation suites so an agent knows which one owns the code it
// touched. That list is derivable from each module's hardening registrations, so it
// drifts silently whenever a suite is added — it already did once. Fail the build
// instead of relying on a prose reminder.
//
// Apply alongside 'software.sava.build.feature.hardening'; without it this plugin is
// inert (the wiring below waits for the hardening plugin, which never arrives).
pluginManager.withPlugin("software.sava.build.feature.hardening") {
  val docsInSync = tasks.register("docsInSync") {
    group = "verification"
    description = "Fails when a registered mutation suite is not named in AGENTS.md."
    val agentsDoc = rootProject.layout.projectDirectory.file("AGENTS.md").asFile
    // The hardening extension's type lives in sava-build. Compiling against it here
    // would pull sava-build onto this build's classpath, dragging the GitHub Packages
    // credentials / includeBuild bootstrap from the root settings into a second
    // settings file — so the suite container is read reflectively instead.
    // project-qualified: inside this lambda a bare 'extensions' is the Task's own
    val hardening = project.extensions.getByName("hardening")
    val mutation = hardening.javaClass.getMethod("getMutation")
      .invoke(hardening) as NamedDomainObjectCollection<*>
    // resolved at configuration time so the task stays configuration-cache friendly;
    // task realization is deferred until after the script's hardening block has run,
    // so the container is fully populated by the time this executes
    val suiteTasks = mutation.names.map { "pitest" + it.replaceFirstChar(Char::uppercase) }
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

  // Wired into check, not just qualityGate: CI deliberately runs only check (the
  // serialized PIT suites are the cost being avoided, and this task reads two
  // files), so without this the doc drift it exists to catch would go unenforced
  // between releases.
  tasks.named("check") { dependsOn(docsInSync) }
  tasks.named("qualityGate") { dependsOn(docsInSync) }
}
