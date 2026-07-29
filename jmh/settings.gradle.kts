pluginManagement {
  // Same local-dev toggle as the root build (see ../settings.gradle.kts): point
  // '-PsavaBuildLocalRepo=<path to sava-build>/build/sava-test-repo' at a published
  // local test repo to bench against an unpublished sava-build change. The publish is
  // NOT automatic — re-run sava-build's publish task after every edit there. This
  // block stays silent about it: this build applies no sava-build settings plugin,
  // but 'includeBuild("..")' configures the root's, whose end-of-build notice covers
  // the whole composite. Prefer an absolute property value — a relative one is
  // resolved against each build's own settings dir, so this build and the root would
  // read two different repos.
  val savaBuildLocalRepo = providers.gradleProperty("savaBuildLocalRepo")
    .orNull?.takeIf { it.isNotBlank() }
  if (savaBuildLocalRepo != null) {
    // Only the local path needs this: the test repo carries no plugin markers, so the
    // id has to be rewritten to the module. The published path resolves through the
    // marker sava-build publishes for every id (21.5.17+), from the version in
    // build.gradle.kts's plugins block.
    resolutionStrategy.eachPlugin {
      if (requested.id.id.startsWith("software.sava.build")) {
        useModule("software.sava:sava-build:0.0.0-test")
      }
    }
  }
  repositories {
    if (savaBuildLocalRepo != null) {
      maven(url = savaBuildLocalRepo)
    }
    gradlePluginPortal()
    mavenCentral()
    // sava-build publishes its markers and module to GitHub Packages only (neither is
    // on the Plugin Portal or Maven Central), so the same credentials the root build
    // needs are required here.
    val gprUser = providers.gradleProperty("savaGithubPackagesUsername")
      .orNull?.takeIf { it.isNotBlank() }
    val gprToken = providers.gradleProperty("savaGithubPackagesPassword")
      .orNull?.takeIf { it.isNotBlank() }
    if (gprUser != null && gprToken != null) {
      maven {
        name = "savaGithubPackages"
        url = uri("https://maven.pkg.github.com/sava-software/sava-build")
        credentials {
          username = gprUser
          password = gprToken
        }
      }
    }
  }
}

rootProject.name = "sava-jmh"

includeBuild("..")
