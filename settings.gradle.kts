rootProject.name = "sava"

pluginManagement {
  // Point '-PsavaBuildLocalRepo=<path to sava-build>/build/sava-test-repo' (or set it in
  // ~/.gradle/gradle.properties) at a local sava-build checkout to build against an
  // unpublished plugin change. sava-build publishes that repo with
  //   ./gradlew publishSavaBuildTestPublicationToSavaTestRepoRepository
  // and every id below then resolves to the 0.0.0-test module regardless of the version
  // the plugins block requests. That publish is NOT automatic: re-run it after every
  // sava-build edit, or this build silently keeps using the previously published jar.
  // The useModule call is also what bypasses plugin markers, which the test repo
  // does not contain.
  val savaBuildLocalRepo = providers.gradleProperty("savaBuildLocalRepo")
    .orNull?.takeIf { it.isNotBlank() }
  if (savaBuildLocalRepo != null) {
    val metadata = settingsDir.resolve(savaBuildLocalRepo)
      .resolve("software/sava/sava-build/maven-metadata.xml")
    val age = if (metadata.isFile) {
      val minutes = (System.currentTimeMillis() - metadata.lastModified()) / 60_000
      "0.0.0-test published ${if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"} ago"
    } else {
      "NO 0.0.0-test PUBLISH FOUND — run sava-build's publish task"
    }
    // Loud on purpose: with the property set in ~/.gradle/gradle.properties, nothing in
    // this file would otherwise reveal that the versions in the plugins block are ignored.
    logger.warn(
      "sava-build: resolving 'software.sava.build*' plugins from LOCAL repo $savaBuildLocalRepo ($age)"
    )
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
  // Repo-local convention plugins (e.g. 'sava.docs-in-sync') — conventions that
  // belong to this repo rather than sava-build.
  includeBuild("gradle/plugins")
}

plugins {
  id("software.sava.build") version "21.5.17"
  id("software.sava.build.feature.jdk-provisioning") version "21.5.17"
}

javaModules {
  directory(".") {
    group = "software.sava"
    plugin("software.sava.build.java-module")
  }
}

//includeBuild("../json-iterator")
