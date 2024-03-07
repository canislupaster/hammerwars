import java.nio.file.Path

val exposedVersion: String by project

repositories {
    mavenCentral()
}

buildscript {
    repositories {
        mavenCentral()
    }

    dependencies {
        classpath("io.jooby:jooby-gradle-plugin:3.0.7")
    }
}

plugins {
    kotlin("jvm") version "1.9.22"
    id("gg.jte.gradle") version("3.1.9")
    kotlin("plugin.serialization") version "1.9.22"
    application
}

group = "win.hammerwars"
version = "1.0-SNAPSHOT"

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    implementation("gg.jte:jte-kotlin:3.1.9")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0")

    implementation("io.jooby:jooby:3.0.7")
    implementation("io.jooby:jooby-kotlin:3.0.7")
    implementation("io.jooby:jooby-netty:3.0.7")
    implementation("io.jooby:jooby-jte:3.0.7")

    implementation("org.slf4j:slf4j-simple:2.0.12")
    implementation("org.slf4j:slf4j-api:2.0.12")

    implementation("com.microsoft.azure:msal4j:1.14.2")

    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-json:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation("org.xerial:sqlite-jdbc:3.45.0.0")
}

kotlin {
    jvmToolchain(17)
}

jte {
    sourceDirectory.set(Path.of("./src/main/templates"))
    targetDirectory.set(Path.of("./jte-classes"))
    generate()
}

application {
    mainClass="win.hammerwars.MainKt"
}

apply(plugin="jooby")

tasks.withType<io.jooby.gradle.RunTask> {
    mainClass = "win.hammerwars.MainKt"
}