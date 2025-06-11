import org.gradlex.javamodule.dependencies.tasks.ModuleDirectivesOrderingCheck

plugins {
  id("com.autonomousapps.dependency-analysis")
}

tasks.withType<ModuleDirectivesOrderingCheck>().configureEach { enabled = false }
