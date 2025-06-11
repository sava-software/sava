plugins {
  id("java")
  id("maven-publish")
  // id("signing")
}

val gprUser = providers.gradleProperty("gpr.user.write").orElse(providers.systemProperty("GITHUB_ACTOR")).orElse("")
val gprToken = providers.gradleProperty("gpr.token.write").orElse(providers.systemProperty("GITHUB_TOKEN")).orElse("")

java {
  withJavadocJar()
  withSourcesJar()
}

//  signing {
//    sign publishing.publications.mavenJava
//  }

val vcs = "https://github.com/sava-software/sava"

publishing {
  publications.register<MavenPublication>("mavenJava") {
    from(components["java"])

    pom {
      name = project.name
      description = "Solana Java Core & RPC SDK"
      url = vcs
      licenses {
        license {
          name = "MIT License"
          url = "https://github.com/sava-software/sava/blob/main/LICENSE"
        }
      }
      developers {
        developer {
          name = "Jim"
          id = "jpe7s"
          email = "jpe7s.salt188@passfwd.com"
          organization = "Sava Software"
          organizationUrl = "https://github.com/sava-software"
        }
      }
      scm {
        connection = "scm:git:git@github.com:sava-software/sava.git"
        developerConnection = "scm:git:ssh@github.com:sava-software/sava.git"
        url = vcs
      }
    }
  }

  repositories {
    maven {
      name = "GithubPackages"
      url = uri("https://maven.pkg.github.com/sava-software/sava")
      credentials {
        username = gprUser.get()
        password = gprToken.get()
      }
    }
  }
}
