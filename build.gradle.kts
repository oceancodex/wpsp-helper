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
    MIIElgCCAn4CCQDo83LWYj2QSTANBgkqhkiG9w0BAQsFADANMQswCQYDVQQGEwJQ
    ...
    gdZzxCN8t1EmH8kD2Yve6YKGFCRAIIzveEg=
    -----END CERTIFICATE-----
  """.trimIndent())

        privateKey.set("""
    -----BEGIN RSA PRIVATE KEY-----
    MIIJKgIBAAKCAgEAwU8awS22Rw902BmwVDDBMlTREX440BAAVM40NW3E0lJ7YTJG
    ...
    EnNBfIVFhh6khisKqTBWSEo5iS2RYJcuZs961riCn1LARztiaXL4l17oW8t+Qw==
    -----END RSA PRIVATE KEY-----
  """.trimIndent())

        password.set("8awS22%#3(4wVDDBMlTREX")
    }

    publishPlugin {
        token.set("perm:a961riC....l17oW8t+Qw==")
    }
}