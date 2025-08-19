pluginManagement {
  repositories {
    gradlePluginPortal()
    maven {
      name = "savaGithubPackages"
      url = uri("https://maven.pkg.github.com/sava-software/sava-build")
      credentials(PasswordCredentials::class)
    }
  }
}

plugins {
  id("software.sava.build") version "0.1.28"
}

rootProject.name = "sava"

javaModules {
  directory(".") {
    group = "software.sava"
    plugin("software.sava.build.java-module")
  }
}
