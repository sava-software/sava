plugins {
  id("software.sava.gradle.feature.jlink")
}

jlinkApplication {
  applicationName = "vanity"
  mainClass = "software.sava.vanity.Entrypoint"
  mainModule = "software.sava.vanity"
}
