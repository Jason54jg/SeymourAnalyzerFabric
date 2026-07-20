plugins {
	// Applies architectury-loom (obfuscated, <=1.21.11) or fabric-loom (unobfuscated, >=26.1)
	// depending on the Minecraft version of the active subproject.
	id("dev.kikugie.loom-back-compat")
}

// DO NOT set group = ...! loom-back-compat / stonecutter derive it per subproject.
version = "${property("mod.version")}+${sc.current.version}"
base.archivesName = property("mod.id") as String

repositories {
	maven("https://maven.shedaniel.me/") { name = "Shedaniel" }
	maven("https://maven.terraformersmc.com/releases/") { name = "TerraformersMC" }
}

val requiredJava: JavaVersion = when {
	sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
	sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
	else -> JavaVersion.VERSION_21
}

dependencies {
	minecraft("com.mojang:minecraft:${sc.current.version}")
	// No-op on unobfuscated (>=26.1) versions; applies official mappings on older ones.
	loomx.applyMojangMappings()

	modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
	modImplementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")
	modImplementation("me.shedaniel.cloth:cloth-config-fabric:${property("deps.cloth_config")}")
	// Optional integration - not bundled/required, see fabric.mod.json's "recommends" and
	// ModMenuIntegration's javadoc for why this is safe when Mod Menu isn't installed.
	modImplementation("com.terraformersmc:modmenu:${property("deps.modmenu")}")

	compileOnly("org.projectlombok:lombok:${property("deps.cloth_config_lombok")}")
	annotationProcessor("org.projectlombok:lombok:${property("deps.cloth_config_lombok")}")
}

loom {
	runConfigs.all {
		preferGradleTask = true
		generateRunConfig = true
		runDirectory = rootProject.file("run")
	}
}

java {
	withSourcesJar()
	targetCompatibility = requiredJava
	sourceCompatibility = requiredJava

	toolchain {
		languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
	}
}

// Captured eagerly at script-evaluation time - property() inside a lazy tasks{} configuration
// closure fails to resolve stonecutter's per-version properties for non-active subprojects
// (only reproduces when actually building/configuring resources for a version other than the
// one open in the IDE, e.g. via a full multi-version build - plain compileJava never hits it).
val modId = property("mod.id") as String
val modName = property("mod.name") as String
val modVersion = property("mod.version") as String
val modMcCompat = property("mod.mc_compat") as String
val mixinJava = "JAVA_${requiredJava.majorVersion}"

tasks {
	processResources {
		val propsMap = mapOf(
			"id" to modId,
			"name" to modName,
			"version" to modVersion,
			"minecraft" to modMcCompat
		)
		propsMap.forEach { (k, v) -> inputs.property(k, v) }

		filesMatching("fabric.mod.json") { expand(propsMap) }

		inputs.property("java", mixinJava)
		filesMatching("*.mixins.json") { expand("java" to mixinJava) }
	}

	register<Copy>("buildAndCollect") {
		group = "build"
		description = "Builds the mod jar and copies it to build/libs/<mod version>/"

		inputs.property("version", modVersion)
		from(loomx.modJar.flatMap { it.archiveFile }, loomx.modSourcesJar.flatMap { it.archiveFile })
		into(rootProject.layout.buildDirectory.file("libs/$modVersion"))
	}
}

if (stonecutter.current.isActive) {
	rootProject.tasks.register("buildActive") {
		group = "project"
		dependsOn(tasks.named("buildAndCollect"))
	}
}
