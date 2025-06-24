plugins {
  id("software.sava.build") version "0.1.0"
}

rootProject.name = "sava"

javaModules {
  directory(".") {
    group = "software.sava"
    plugin("software.sava.build.java-module")
  }
}

// TODO - to be removed once 'solana-version-catalog' is on Maven Central
val gprUser = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orElse("")
val gprToken = providers.gradleProperty("gpr.token").orElse(providers.environmentVariable("GITHUB_TOKEN")).orElse("")
dependencyResolutionManagement {
  @Suppress("UnstableApiUsage")
  repositories {
    maven {
      url = uri("https://maven.pkg.github.com/sava-software/solana-version-catalog")
      credentials {
        username = gprUser.get()
        password = gprToken.get()
      }
    }
  }
}
