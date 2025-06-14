plugins {
  id("maven-publish")
  id("com.gradleup.nmcp.aggregation")
}

nmcpAggregation {
  centralPortal {
    username = providers.environmentVariable("MAVEN_CENTRAL_TOKEN")
    password = providers.environmentVariable("MAVEN_CENTRAL_SECRET")
    publishingType = "USER_MANAGED" // "AUTOMATIC"
  }
}
