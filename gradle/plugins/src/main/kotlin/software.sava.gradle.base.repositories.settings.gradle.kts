val gprUser =
  providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orElse("")
val gprToken =
  providers.gradleProperty("gpr.token").orElse(providers.environmentVariable("GITHUB_TOKEN")).orElse("")

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
    mavenCentral()
  }
}
