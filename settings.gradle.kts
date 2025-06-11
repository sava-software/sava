pluginManagement {
  includeBuild("gradle/plugins")
}
plugins {
  id("software.sava.gradle.build")
}

rootProject.name = "sava"

javaModules {
  directory(".") {
    group = "software.sava"
    plugin("software.sava.gradle.java-module")

    module("core") { artifact = "sava-core" }
    module("examples") { artifact = "sava-examples" }
    module("rpc") { artifact = "sava-rpc" }
    module("vanity") { artifact = "sava-vanity" }
  }
  versions("gradle/versions")
}

dependencyResolutionManagement {
  versionCatalogs {
    register("libs") {
      from("software.sava:solana-version-catalog:0.9.0")
    }
  }
}
