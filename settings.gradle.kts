pluginManagement {
  includeBuild("gradle/plugins")
}
plugins {
  id("software.sava.gradle.build")
}

rootProject.name = "sava"

include(":sava-core")
project(":sava-core").projectDir = file("core")
include(":sava-rpc")
project(":sava-rpc").projectDir = file("rpc")
include(":sava-examples")
project(":sava-examples").projectDir = file("examples")
include(":sava-vanity")
project(":sava-vanity").projectDir = file("vanity")

dependencyResolutionManagement {
  versionCatalogs {
    register("libs") {
      from("software.sava:solana-version-catalog:0.9.0")
    }
  }
}
