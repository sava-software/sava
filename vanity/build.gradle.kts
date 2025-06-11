plugins {
  alias(libs.plugins.jlink)
}

dependencies {
  implementation(libs.sava.json.iterator)
  implementation(project(":sava-core"))
}

afterEvaluate {
  jlink {
    imageName = project.name
    options.addAll(listOf(
        "--bind-services",
        "--no-man-pages",
        "--vm=server",
        "--ignore-signing-information"
    ))
    enableCds()
  }
}
