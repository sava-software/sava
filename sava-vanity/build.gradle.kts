import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import software.sava.build.jlink.JlinkImageTask

plugins {
  id("software.sava.build.feature.jlink")
}

testModuleInfo {
  requires("org.junit.jupiter.api")
  runtimeOnly("org.junit.jupiter.engine")
}

// On Linux the JDK encodes a child's command line and environment with the charset of the test
// JVM's own locale (sun.jnu.encoding), which no system property can override; under LC_ALL=C a
// non-ASCII test password would reach the launcher as '?'. macOS always uses UTF-8.
val linux = providers.systemProperty("os.name").map { it.startsWith("Linux") }
tasks.withType<Test>().configureEach {
  if (linux.get()) {
    environment("LC_ALL", "C.UTF-8")
  }
}

// The launcher tests drive verifyKey.sh and genKeys.sh against process stubs, so both scripts
// are inputs of the test task; an edit re-runs it instead of restoring a cached result. Their
// terminal cases open a pseudo-terminal through the FFM linker from the patched test module.
tasks.test {
  inputs.files("verifyKey.sh", "genKeys.sh")
  // The launcher tests exercise the host shell and tools too, so a different bash or kernel
  // re-runs them instead of restoring a cached result.
  inputs.property("hostShell", providers.exec { commandLine("/bin/bash", "--version") }.standardOutput.asText.map { it.lineSequence().first() })
  inputs.property("hostSystem", providers.exec { commandLine("uname", "-sr") }.standardOutput.asText.map(String::trim))
  jvmArgs("--enable-native-access=software.sava.vanity")
  useJUnitPlatform {
    excludeTags("runtime")
  }
}

val image = tasks.named<JlinkImageTask>("image")

tasks.register<Test>("testVerifyKeyRuntime") {
  group = "verification"
  description = "Exercises saved-key recovery through the built JVM and optional Docker runtime."
  // The 'runtime'-tagged class comes from the test source set but runs on the class path: it
  // drives external JVMs and containers, so module boundaries add nothing, and the gradlex
  // whitebox wiring reaches only the suite's own test task.
  val test = sourceSets.test.get()
  testClassesDirs = test.output.classesDirs
  classpath = test.runtimeClasspath
  modularity.inferModulePath = false
  useJUnitPlatform {
    includeTags("runtime")
  }
  jvmArgs("--enable-native-access=ALL-UNNAMED")
  dependsOn(image)
  inputs.file("verifyKey.sh")
  val imageDirectory = image.flatMap { it.output }
  inputs.dir(imageDirectory)
  // Locals only: a lambda that reaches a script-level property cannot enter the configuration cache.
  val dockerImage = providers.gradleProperty("verifyKeyDockerImage")
  val timeoutSeconds = providers.gradleProperty("verifyKeyRuntimeTimeout")
  jvmArgumentProviders.add(CommandLineArgumentProvider {
    listOfNotNull(
      "-Dsava.vanity.image=" + imageDirectory.get().asFile.absolutePath,
      dockerImage.orNull?.let { "-Dsava.vanity.dockerImage=$it" },
      timeoutSeconds.orNull?.let { "-Dsava.vanity.runtimeTimeoutSeconds=$it" }
    )
  })
  // A Docker tag's content can change under the same name and the run is a measurement of
  // the built image, so it is never skipped as UP-TO-DATE or restored from the build cache.
  doNotTrackState("Exercises external runtimes whose state Gradle cannot track.")
  testLogging {
    events("passed", "skipped", "failed")
    exceptionFormat = TestExceptionFormat.FULL
  }
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
  // No bound provider this tool needs is loaded at run time (jdk.localedata's was, for the %,d
  // grouping on non-English hosts; the addModules comment records dropping it). The plugin's
  // default since 21.7.0, explicit so the image task stops printing what binding would have
  // linked; HARDENING_NOTES.md (21.7.1) records which modules that is and why none is needed.
  bindServices = false
  addModules = listOf(
    // Passwords are decoded in the host locale's charset, the console's and the environment
    // variable's alike. The shell wrappers hold local encrypted runs to UTF-8 and Docker runs
    // decode under the image's C.UTF-8; this module keeps a launcher run directly under a legacy
    // multi-byte locale (Shift_JIS, GBK, EUC-KR) from decoding them as UTF-8, the JVM's fallback
    // when the locale's charset is not in the image; under C or POSIX the JVM decodes ASCII only,
    // with or without it (HARDENING_NOTES.md says which tests hold what). About 2.7M.
    "jdk.charsets"
    // jdk.localedata stays out: the only locale-sensitive output is the %,d progress count, whose
    // grouping separator follows CLDR root (1,000) on every host.
  )
}
