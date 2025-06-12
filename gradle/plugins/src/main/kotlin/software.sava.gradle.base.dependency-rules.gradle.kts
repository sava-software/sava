plugins {
  id("org.gradlex.jvm-dependency-conflict-resolution")
}

// NOTE: This may refer directly to published BOM and 'gradle/versions' can be removed
jvmDependencyConflicts {
  consistentResolution {
    platform(":versions")
  }
}
