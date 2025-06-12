plugins {
  id("java")
}

tasks.withType<Javadoc>().configureEach {
  (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}
