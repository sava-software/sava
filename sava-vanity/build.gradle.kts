plugins {
  id("software.sava.build.feature.jlink")
}

jlinkApplication {
  applicationName = "sava-vanity"
  mainClass = "software.sava.vanity.Entrypoint"
  mainModule = "software.sava.vanity"
  noHeaderFiles = true
  noManPages = true
  generateCdsArchive = true
  stripDebug = false
  compress = "zip-6"
  vm = "server"
}

dependencies {
  project(":sava-core")
}
