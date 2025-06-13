import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL

plugins {
  id("java")
  id("org.gradlex.java-module-dependencies")
  id("org.gradlex.java-module-testing")
}

tasks.test {
  testLogging {
    events("passed", "skipped", "failed", "standardOut", "standardError")
    exceptionFormat = FULL
    showStandardStreams = true
  }
}

// addition 'requires' for the test code
testModuleInfo {
  requires("org.junit.jupiter.api")
  requires("jdk.httpserver")
  runtimeOnly("org.junit.jupiter.engine")
}
