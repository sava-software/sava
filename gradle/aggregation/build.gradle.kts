plugins {
  id("software.sava.build.feature.publish-maven-central")
}

val idlClientModules = setOf(
  "sava-core",
  "sava-rpc"
)

dependencies {
  for (module in idlClientModules) {
    nmcpAggregation(project(":$module"))
  }
}

tasks.register("publishToGitHubPackages") {
  group = "publishing"
  val publishTasks = idlClientModules.map { ":$it:publishMavenJavaPublicationToSavaGithubPackagesPublishRepository" }
  dependsOn(publishTasks)
}
