// Standalone soak build. Mirrors ../jmh: an independent Gradle build that includes the
// library build from '..', so the composite substitutes 'software.sava:sava-rpc' and
// 'software.sava:sava-core' — including the copies ravina-solana asks for transitively —
// with the local projects.
//
// Deliberately applies NO sava-build plugin, so this build adds no fourth pin to the three
// that AGENTS.md says must move together (root settings x2 + jmh/build.gradle.kts). That is
// also why there is no pluginManagement block: only the built-in 'java'/'application'
// plugins are used here, and 'includeBuild("..")' configures the root build's own
// pluginManagement (including its -PsavaBuildLocalRepo toggle) for the whole composite.
// A '-PsavaBuildLocalRepo=<abs path>' passed on this build's command line therefore still
// reaches the root build; prefer an absolute value, since a relative one resolves against
// each build's own settings dir.
rootProject.name = "sava-soak"

includeBuild("..")
