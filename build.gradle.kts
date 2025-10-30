plugins {
    id("org.jetbrains.intellij") version "1.17.4"
    kotlin("jvm") version "1.9.24"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories { mavenCentral() }

intellij {
    type.set(providers.gradleProperty("platformType")) // PhpStorm
    version.set(providers.gradleProperty("platformVersion"))
    plugins.set(listOf("com.jetbrains.php"))

    // 🔥 Thêm dòng này để tự động tương thích các bản IDE mới hơn
//    updateSinceUntilBuild.set(false)

    // (Tuỳ chọn) Tắt buildSearchableOptions để giảm thời gian build
//    instrumentCode.set(false)
}

tasks {
    patchPluginXml {
        sinceBuild.set(providers.gradleProperty("pluginSinceBuild"))
        untilBuild.set(providers.gradleProperty("pluginUntilBuild").orNull)
    }
    buildSearchableOptions { enabled = false }
    runIde {
        autoReloadPlugins.set(true)
    }
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    signPlugin {
        certificateChain.set("""
-----BEGIN CERTIFICATE-----
///
-----END CERTIFICATE-----
  """.trimIndent())

        privateKey.set("""
-----BEGIN PRIVATE KEY-----
///
-----END PRIVATE KEY-----
  """.trimIndent())

        password.set("4aw...")
    }

    publishPlugin {
        token.set("perm-...")
    }
}