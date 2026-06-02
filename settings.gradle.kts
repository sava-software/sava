rootProject.name = "sava"

pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    val gprUser = providers.gradleProperty("savaGithubPackagesUsername").orNull
    val gprToken = providers.gradleProperty("savaGithubPackagesPassword").orNull
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
  id("software.sava.build") version "21.3.15"
}

apply(plugin = "software.sava.build.feature-jdk-provisioning")

javaModules {
  directory(".") {
    group = "software.sava"
    plugin("software.sava.build.java-module")
  }
}
