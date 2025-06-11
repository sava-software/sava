plugins {
  `kotlin-dsl`
}

repositories {
  gradlePluginPortal()
}

dependencies {
  implementation("com.autonomousapps:dependency-analysis-gradle-plugin:2.18.0")
  implementation("com.github.iherasymenko.jlink:jlink-plugin:0.7")
  implementation("com.gradle:develocity-gradle-plugin:4.0.2")
  implementation("com.gradleup.nmcp:nmcp:0.1.5")
  implementation("org.gradlex:java-module-dependencies:1.9.1")
  implementation("org.gradlex:java-module-testing:1.7")
  implementation("org.gradlex:jvm-dependency-conflict-resolution:2.4")
}
