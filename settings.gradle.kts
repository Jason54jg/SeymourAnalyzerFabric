pluginManagement {
	repositories {
		mavenCentral()
		gradlePluginPortal()
		maven("https://maven.fabricmc.net/") { name = "Fabric" }
		maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
		maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
	}
}

plugins {
	// https://stonecutter.kikugie.dev/blog/changes/0.9
	id("dev.kikugie.stonecutter") version "0.9.6"
	// Bridges fabric-loom (unobfuscated, 26.1+) and architectury-loom (obfuscated, <=1.21.11)
	// so a single build.gradle.kts works for every targeted Minecraft version.
	// https://codeberg.org/KikuGie/loom-back-compat
	id("dev.kikugie.loom-back-compat") version "0.4"
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
	create(rootProject) {
		versions("1.21.11")
		version("26.1.2", "26.1.2")
		version("26.2", "26.2")
		vcsVersion = "26.1.2"
	}
}

rootProject.name = "seymouranalyzer"
