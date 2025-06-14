plugins {
  id("java")
}

tasks.withType<Javadoc>().configureEach {
  val standardOption = options as StandardJavadocDocletOptions
  standardOption.addStringOption("Xdoclint:none", "-quiet")
  standardOption.addBooleanOption("html5", true)
}
