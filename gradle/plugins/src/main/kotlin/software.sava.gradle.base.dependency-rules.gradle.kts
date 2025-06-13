plugins {
  id("org.gradlex.jvm-dependency-conflict-resolution")
}

@Suppress("UnstableApiUsage")
val bomVersion = providers.fileContents(isolated.rootProject.projectDirectory
  .file("gradle/solana-version-catalog-version.txt")).asText.get().trim()

jvmDependencyConflicts {
  consistentResolution {
    platform("software.sava:solana-version-catalog:$bomVersion")
  }
}
