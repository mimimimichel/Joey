pluginManagement {
    repositories {
        google()
        // Miroir de Maven Central (le dépôt principal limite parfois les téléchargements)
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Miroir de Maven Central (le dépôt principal limite parfois les téléchargements)
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        // Seulement pour la bibliothèque USB-série (Roomba)
        maven("https://jitpack.io") { content { includeGroup("com.github.mik3y") } }
    }
}
rootProject.name = "Joey"
include(":app")
