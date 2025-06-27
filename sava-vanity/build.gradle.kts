plugins {
  id("software.sava.build.feature.jlink")
}

jlinkApplication {
  applicationName = "vanity"
  mainClass = "software.sava.vanity.Entrypoint"
  mainModule = "software.sava.vanity"
  noManPages = true
  vm = "server"
}
