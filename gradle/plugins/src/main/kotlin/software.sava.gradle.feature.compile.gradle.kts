plugins {
  id("java")
}

val jlv = JavaLanguageVersion.of(providers.gradleProperty("javaVersion").getOrElse("24"))

java {
  toolchain.languageVersion = jlv
}
