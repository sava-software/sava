plugins {
  id("software.sava.build.feature.publish-maven-central")
}

val publishedModules = setOf(
  "sava-core",
  "sava-rpc"
)

dependencies {
  for (module in publishedModules) {
    centralPortalAggregation(project(":$module"))
  }
}
