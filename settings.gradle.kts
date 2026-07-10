rootProject.name = "sava"

pluginManagement {
  // Point '-PsavaBuildLocalRepo=<path to sava-build>/build/sava-test-repo' (or set it in
  // ~/.gradle/gradle.properties) at a local sava-build checkout to build against an
  // unpublished plugin change; sava-build publishes that repo with
  //   ./gradlew publishSavaBuildTestPublicationToSavaTestRepoRepository
  // and every id below then resolves to the 0.0.0-test module regardless of the
  // version the plugins block requests — which the plugin announces at the end of
  // each build, so this block stays silent. The useModule call also bypasses plugin
  // markers, which the test repo does not contain.
  val savaBuildLocalRepo = providers.gradleProperty("savaBuildLocalRepo")
    .orNull?.takeIf { it.isNotBlank() }
  if (savaBuildLocalRepo != null) {
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
//  if (settingsDir.resolve("../sava-build").isDirectory) {
//    includeBuild("../sava-build")
//  }
}

plugins {
  id("software.sava.build") version "21.4.3"
  id("software.sava.build.feature.jdk-provisioning") version "21.4.3"
}

javaModules {
  directory(".") {
    group = "software.sava"
    plugin("software.sava.build.java-module")
  }
}

//includeBuild("../json-iterator")
