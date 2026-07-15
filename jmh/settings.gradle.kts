pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
  // Local-dev harness: the sava-build conventions (feature.jmh) come from the
  // sibling checkout, matching the local JDK pin in gradle.properties.
  includeBuild("../../sava-build")
}

rootProject.name = "sava-jmh"

includeBuild("..")
