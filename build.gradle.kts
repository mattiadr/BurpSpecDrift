plugins {
	kotlin("jvm") version "2.4.10"
	kotlin("plugin.serialization") version "2.4.10"
	id("com.gradleup.shadow") version "9.4.1"
}

group = "com.mattiadr"
version = "1.0"

repositories {
	mavenCentral()
}

dependencies {
	implementation(kotlin("stdlib"))
	compileOnly("net.portswigger.burp.extensions:montoya-api:2026.7")
	implementation("io.swagger.parser.v3:swagger-parser:2.1.22")
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
}

kotlin {
	jvmToolchain(21)
}

tasks {
	shadowJar {
		archiveClassifier = ""
		archiveVersion = ""
		// Merge META-INF/services so swagger-parser's ServiceLoader finds every
		// SwaggerParserExtension (e.g. the Swagger 2.0 -> OpenAPI 3.0 converter).
		mergeServiceFiles()
	}
}
