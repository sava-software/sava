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

// remove automatically added compile time dependencies for strict dependency analysis
configurations.testImplementation {
  withDependencies {
    removeIf { it.group == "org.junit.jupiter" && it.name == "junit-jupiter" }
  }
}

configurations.testCompileOnly {
  extendsFrom(configurations.compileOnly.get())
}
