import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
	id("java")
	id("org.jetbrains.kotlin.jvm") version "2.4.21"
	id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
	mavenCentral()
	intellijPlatform {
		defaultRepositories()
	}
}

dependencies {
	intellijPlatform {
		create(
			providers.gradleProperty("platformType"),
			providers.gradleProperty("platformVersion")
		)
		bundledPlugin("com.jetbrains.php")

		pluginVerifier()
		zipSigner()
		testFramework(TestFrameworkType.Platform)
	}
}

intellijPlatform {
	pluginConfiguration {
		id.set("com.wpsp.wpsp-helper")
		name.set("WPSP Helper")
		version.set(providers.gradleProperty("pluginVersion"))

		ideaVersion {
			sinceBuild.set(providers.gradleProperty("pluginSinceBuild"))
//			untilBuild.set(providers.gradleProperty("pluginUntilBuild").orNull)
		}
	}

	pluginVerification {
		ides {
			select {
				types = listOf(IntelliJPlatformType.PhpStorm)
				channels = listOf(ProductRelease.Channel.RELEASE)
				sinceBuild = providers.gradleProperty("pluginSinceBuild")
			}
		}
	}

	signing {
		certificateChain.set(
			"""
-----BEGIN CERTIFICATE-----
///
-----END CERTIFICATE-----
            """.trimIndent()
		)
		privateKey.set(
			"""
-----BEGIN PRIVATE KEY-----
///
-----END PRIVATE KEY-----
            """.trimIndent()
		)
		password.set("4aw...")
	}

	publishing {
		token.set("perm-...")
	}
}

java {
	toolchain {
		languageVersion.set(JavaLanguageVersion.of(21))
	}
}

tasks {
	buildSearchableOptions {
		enabled = false
	}

	withType<JavaCompile> {
		sourceCompatibility = "21"
		targetCompatibility = "21"
	}

	withType<KotlinCompile> {
		compilerOptions {
			jvmTarget.set(JvmTarget.JVM_21)
		}
	}
}