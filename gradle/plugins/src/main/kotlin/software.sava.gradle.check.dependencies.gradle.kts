import com.autonomousapps.DependencyAnalysisSubExtension
import org.gradlex.javamodule.dependencies.tasks.ModuleDirectivesOrderingCheck

plugins {
  id("java")
  id("com.autonomousapps.dependency-analysis")
  id("org.gradlex.java-module-dependencies")
}

tasks.withType<ModuleDirectivesOrderingCheck>().configureEach { enabled = false }

configure<DependencyAnalysisSubExtension> {
  issues {
    // ignore 'junit-jupiter' dependencies so tht the check does not fail if a project has no tests at all
    onUnusedDependencies { excludeRegex("org.junit.jupiter:.*") }
  }
}

tasks.check {
  dependsOn(tasks.checkAllModuleInfo)
}
