plugins {
  id("org.gradlex.jvm-dependency-conflict-resolution")
}

jvmDependencyConflicts {
  consistentResolution {
    platform(":versions")
  }
}
