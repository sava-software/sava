import java.util.Properties

plugins {
  java
  id("software.sava.build.feature.jmh")
}

val gprUser = providers.gradleProperty("savaGithubPackagesUsername").orNull
val gprToken = providers.gradleProperty("savaGithubPackagesPassword").orNull

repositories {
  mavenCentral()
  if (!gprUser.isNullOrBlank() && !gprToken.isNullOrBlank()) {
    maven {
      name = "savaGithubPackages"
      url = uri("https://maven.pkg.github.com/sava-software/solana-version-catalog")
      credentials {
        username = gprUser
        password = gprToken
      }
    }
  }
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(25)
  }
}

// sava-core declares its external dependencies without versions; the parent
// build pins them through consistent resolution against the
// solana-version-catalog platform. That constraint does not cross the
// composite-build boundary, so apply the same platform here, at the same
// version the parent pins in gradle/sava.properties.
val savaProperties = Properties()
rootDir.resolve("../gradle/sava.properties").reader().use(savaProperties::load)
val solanaBOMVersion: String = savaProperties.getProperty("solanaBOMVersion")

dependencies {
  jmhImplementation(platform("software.sava:solana-version-catalog:$solanaBOMVersion"))
  jmhImplementation("software.sava:sava-core")
}
