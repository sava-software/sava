plugins {
  id("software.sava.gradle.feature.publish-maven-central")
}

dependencies {
  nmcpAggregation(project(":sava-core"))
  nmcpAggregation(project(":sava-rpc"))
}
