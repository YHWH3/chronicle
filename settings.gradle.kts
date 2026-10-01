pluginManagement {
    repositories {
        google()
        // Mirror of Maven Central on GCS — reliable and not rate-limited.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
        // sherpa-onnx AAR (prebuilt native libs for the Parakeet STT engine).
        maven { url = uri("https://xdcobra.github.io/maven/") }
    }
}
rootProject.name = "chronicle"
include(":app")
