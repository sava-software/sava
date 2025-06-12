import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL

plugins {
  id("java")
}

tasks.test {
  useJUnitPlatform()
  testLogging {
    events("passed", "skipped", "failed", "standardOut", "standardError")
    exceptionFormat = FULL
    showStandardStreams = true
  }
}

dependencies {
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// access catalog for junit version
// https://github.com/gradle/gradle/issues/15383
configurations.testImplementation {
  withDependencies {
    val libs = the<VersionCatalogsExtension>().named("libs")
    add(libs.findLibrary("junit-jupiter").get().get())
  }
}
