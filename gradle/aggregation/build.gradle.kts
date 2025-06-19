plugins {
  id("software.sava.build.feature.publish-maven-central")
}

dependencies {
  nmcpAggregation(project(":sava-core"))
  nmcpAggregation(project(":sava-rpc"))
}

tasks.register("publishToGitHubPackages") {
  group = "publishing"
  dependsOn(":sava-core:publish", ":sava-rpc:publish")
}
