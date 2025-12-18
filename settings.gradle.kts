rootProject.name = "sava"

pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    val gprUser = providers.gradleProperty("savaGithubPackagesUsername")
      .orElse(providers.environmentVariable("ORG_GRADLE_PROJECT_savaGithubPackagesUsername"))
      .orElse(providers.environmentVariable("GITHUB_ACTOR"))
      .orNull
    val gprToken = providers.gradleProperty("savaGithubPackagesPassword")
      .orElse(providers.environmentVariable("ORG_GRADLE_PROJECT_savaGithubPackagesPassword"))
      .orElse(providers.environmentVariable("GITHUB_TOKEN"))
      .orNull
    if (!gprUser.isNullOrBlank() && !gprToken.isNullOrBlank()) {
      maven {
        url = uri("https://maven.pkg.github.com/sava-software/sava-build")
        credentials {
          username = gprUser
          password = gprToken
        }
      }
    }
//  includeBuild("../sava-build")
  }
}

plugins {
  id("software.sava.build") version "21.3.3"
}

apply(plugin = "software.sava.build.feature-jdk-provisioning")

javaModules {
  directory(".") {
    group = "software.sava"
    plugin("software.sava.build.java-module")
  }
}
