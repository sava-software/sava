rootProject.name = "sava"

pluginManagement {
  repositories {
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
//  includeBuild("../sava-build")
  }
}

plugins {
  id("software.sava.build") version "21.4.0"
}

apply(plugin = "software.sava.build.feature-jdk-provisioning")

javaModules {
  directory(".") {
    group = "software.sava"
    plugin("software.sava.build.java-module")
  }
}
