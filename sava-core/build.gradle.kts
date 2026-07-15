testModuleInfo {
  requires("org.junit.jupiter.api")
  runtimeOnly("org.junit.jupiter.engine")
}

// Base58 hardening tools, detached from the module graph on purpose.
val pitest = configurations.create("pitest")
// pitest-entry 1.19.1 bundles an ASM that cannot read Java 25 class files; these jars must precede it on the classpath
val pitestAsm = configurations.create("pitestAsm")
val jazzer = configurations.create("jazzer")

dependencies {
  pitest("org.pitest:pitest-command-line:1.19.1")
  pitest("org.pitest:pitest-junit5-plugin:1.2.2")
  pitestAsm("org.ow2.asm:asm:9.10.1")
  pitestAsm("org.ow2.asm:asm-tree:9.10.1")
  pitestAsm("org.ow2.asm:asm-commons:9.10.1")
  pitestAsm("org.ow2.asm:asm-util:9.10.1")
  jazzer("com.code-intelligence:jazzer:0.24.0")
}

val sourceSetContainer = extensions.getByType<SourceSetContainer>()

// PIT silently excludes classpath roots whose path contains "pitest", so this directory must not mention it
val pitestClassesDir = layout.buildDirectory.dir("mutation-classes")

// PIT's shaded ASM cannot read Java 25 class files; feed it the same sources compiled to Java 21 bytecode.
val compileForPitest = tasks.register<JavaCompile>("compileForPitest") {
  source(sourceSetContainer["main"].java, sourceSetContainer["test"].java)
  exclude("**/module-info.java")
  modularity.inferModulePath = false
  // external jars only: the project's own Java 25 outputs are recompiled from source instead
  classpath = files(tasks.named<JavaCompile>("compileTestJava").map { task ->
    task.classpath.filter { !it.absolutePath.contains("${File.separator}build${File.separator}") }
  })
  destinationDirectory = pitestClassesDir
  options.release = 21
}

tasks.register<JavaExec>("pitestEncoding") {
  group = "verification"
  description = "PIT mutation testing of the encoding package against its tests."
  dependsOn(compileForPitest)
  mainClass = "org.pitest.mutationtest.commandline.MutationCoverageReport"
  classpath = pitestAsm + pitest
  val buildDirPath = layout.buildDirectory.get().asFile.absolutePath
  val pitestClassesPath = pitestClassesDir.get().asFile.absolutePath
  val classPathArg = files(
    pitestClassesDir,
    configurations["testRuntimeClasspath"]
  ).elements.map { locations ->
    "--classPath=" + locations
        .map { it.asFile.absolutePath }
        // keep the Java 21 classes and external jars; drop this project's Java 25 outputs
        .filter { it == pitestClassesPath || !it.startsWith(buildDirPath) }
        .joinToString(",")
  }
  val sourceDirsArg = "--sourceDirs=" + layout.projectDirectory.dir("src/main/java").asFile.absolutePath
  val reportDirArg = "--reportDir=" + layout.buildDirectory.dir("reports/pitest").get().asFile.absolutePath
  argumentProviders.add {
    listOf(
      classPathArg.get(),
      "--targetClasses=software.sava.core.encoding.Base58,software.sava.core.encoding.ByteUtil,software.sava.core.encoding.CompactU16Encoding,software.sava.core.encoding.Jex",
      "--targetTests=software.sava.core.encoding.*Test*",
      sourceDirsArg,
      reportDirArg,
      "--mutators=STRONGER",
      "--outputFormats=HTML,CSV",
      "--timestampedReports=false",
      "--threads=4"
    )
  }
}

tasks.register<JavaExec>("fuzzBase58") {
  group = "verification"
  description = "Coverage-guided fuzzing of the Base58 codec with Jazzer; -PmaxFuzzTime=<seconds> (default 60)."
  // Jazzer's ASM also cannot instrument Java 25 class files, so fuzz the Java 21 compile
  dependsOn(compileForPitest)
  mainClass = "com.code_intelligence.jazzer.Jazzer"
  classpath = jazzer + files(pitestClassesDir)
  val maxFuzzTime = providers.gradleProperty("maxFuzzTime").getOrElse("60")
  val corpusDir = layout.buildDirectory.dir("fuzz/base58-corpus").get().asFile
  doFirst {
    corpusDir.mkdirs()
  }
  args(
    "--target_class=software.sava.core.encoding.Base58Fuzz",
    "-max_total_time=$maxFuzzTime",
    corpusDir.absolutePath
  )
}
