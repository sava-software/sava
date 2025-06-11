plugins {
  id("software.sava.gradle.report.develocity")
  id("software.sava.gradle.base.repositories")
  id("org.gradlex.java-module-dependencies")
}

includeBuild(".")

include(":aggregation")
project(":aggregation").projectDir = file("gradle/aggregation")
