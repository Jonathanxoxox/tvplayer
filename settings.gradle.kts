pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // A libmpv built on this PC (OwnTV_libmpv's buildscripts/wsl_build.sh), only while
        // the build command carries -Powntv.libmpvLocalRepo=<folder> (never a file) — core's :player-core then asks
        // for version "local". Core's settings carry the same lines.
        providers.gradleProperty("owntv.libmpvLocalRepo").orNull?.let { dir ->
            maven {
                name = "LocalLibmpv"
                url = uri(file(dir))
                content { includeVersion("tv.own.owntv", "libmpv", "local") }
            }
        }
        google()
        mavenCentral()
        // Mayo TV's own Maven repository: the core fork's gh-pages branch, read straight from GitHub
        // (public, no login). Here for tv.own.owntv:libmpv, the mpv engine that :player-core brings in.
        // Nothing is fetched from the original OwnTV repositories (6 Nov 2026).
        maven {
            name = "MayoCore"
            url = uri("https://raw.githubusercontent.com/Jonathanxoxox/OwnTV_Core/gh-pages/maven")
            content { includeGroup("tv.own.owntv") }
        }
    }
}

// The core is always built from the fork's source (Jonathanxoxox/OwnTV_Core, see core-fork.properties):
// Gradle substitutes tv.own.owntv:core and :player-core with that build, so a core change on the fork
// reaches the app with no publish step. CI sets owntv.corePath through tools/ci/core-fork.sh; locally
// put it in ~/.gradle/gradle.properties, never here:  owntv.corePath=C:/path/to/OwnTV_Core
// Without it the build stops, rather than quietly resolving a published core that is not Mayo TV's.
val corePath = providers.gradleProperty("owntv.corePath").orNull?.takeIf { it.isNotBlank() }
    ?: throw GradleException(
        "Mayo TV builds against its core fork only: set owntv.corePath to a checkout of " +
            "Jonathanxoxox/OwnTV_Core (CI: tools/ci/core-fork.sh).",
    )
includeBuild(corePath)

rootProject.name = "OwnTV"
include(":app")
// Baseline-profile generator. Test-only module: it ships nothing to users, it records
// the cold-start journey on a device and writes the profile :app packages.
include(":baselineprofile")
 