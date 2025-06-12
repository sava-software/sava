plugins {
  id("maven-publish")
  id("com.gradleup.nmcp.aggregation")
}

nmcpAggregation {
  centralPortal {
    username = providers.environmentVariable("MAVEN_CENTRAL_USERNAME")
    password = providers.environmentVariable("MAVEN_CENTRAL_PASSWORD")
    publishingType = "USER_MANAGED" // "AUTOMATIC"
  }
}
