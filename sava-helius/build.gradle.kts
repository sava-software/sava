testModuleInfo {
  requires("org.junit.jupiter.api")
  runtimeOnly("org.junit.jupiter.engine")
}

dependencies {
  project(":sava-core")
  project(":sava-rpc")
}

plugins {
  id("software.sava.build.feature.jlink")
}

jlinkApplication {
  applicationName = "sava-helius"
  mainClass = "software.sava.helius.demo.Entrypoint"
  mainModule = "software.sava.helius"
  noManPages = true
  generateCdsArchive = true
  vm = "server"
}
