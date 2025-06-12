// NOTE: This file is not necessary when BOM is published
dependencies.constraints {
  val libs = versionCatalogs.named("libs")
  val catalogEntries = libs.libraryAliases.map { libs.findLibrary(it).get().get() }
  catalogEntries.forEach { entry ->
    val version = entry.version
    if (version != null) {
      api(entry) { version { require(version) } }
    }
  }
}

// Temporarily until 'org.junit.jupiter:junit-jupiter-api' is in catalog (and/or BOM)
dependencies.constraints {
  api("org.junit.jupiter:junit-jupiter-api:${libs.junit.jupiter.get().version}")
}
