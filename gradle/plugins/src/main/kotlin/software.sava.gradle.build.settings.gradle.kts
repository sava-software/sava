plugins {
  id("software.sava.gradle.base.repositories")
}

@Suppress("UnstableApiUsage")
gradle.lifecycle.beforeProject {
  if (path != ":") {
    group = "software.sava"
    apply(plugin = "software.sava.gradle.java-module")
  }
}
