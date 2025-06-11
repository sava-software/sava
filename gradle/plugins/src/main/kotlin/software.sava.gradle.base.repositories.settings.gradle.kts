val gprUser = providers.gradleProperty("gpr.user").orElse(providers.systemProperty("GITHUB_ACTOR")).orElse("")
val gprToken = providers.gradleProperty("gpr.token").orElse(providers.systemProperty("GITHUB_TOKEN")).orElse("")

dependencyResolutionManagement {
  @Suppress("UnstableApiUsage")
  repositories {
    maven {
      url = uri("https://maven.pkg.github.com/sava-software/json-iterator")
      credentials {
        username = gprUser.get()
        password = gprToken.get()
      }
    }
    mavenCentral()
  }
}
