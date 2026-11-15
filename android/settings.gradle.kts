import java.io.File

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// rustls-platform-verifier checks TLS certificates with Android's own trust store, which needs a
// small Kotlin component. The Rust crate "rustls-platform-verifier-android" carries it as a local
// Maven repository, so its place on disk is asked from Cargo (CI can set it in the environment).
fun rustlsVerifierMaven(): File {
    System.getenv("RUSTLS_VERIFIER_MAVEN")?.let { return File(it) }
    val manifest = File(settingsDir, "../core/Cargo.toml").canonicalPath
    val process = ProcessBuilder("cargo", "metadata", "--format-version", "1", "--manifest-path", manifest)
        .redirectErrorStream(false)
        .start()
    val out = process.inputStream.bufferedReader().readText()
    process.waitFor()
    val found = Regex("\"name\":\"rustls-platform-verifier-android\".*?\"manifest_path\":\"([^\"]+)\"")
        .find(out)?.groupValues?.get(1)
        ?: error("rustls-platform-verifier-android was not found in `cargo metadata` (run `cargo fetch` first)")
    return File(File(found).parentFile, "maven")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri(rustlsVerifierMaven())
            metadataSources.artifact()
        }
    }
}

rootProject.name = "MDM-Android"
include(":app")
