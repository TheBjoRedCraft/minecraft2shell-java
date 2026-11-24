plugins {
    kotlin("jvm") version "2.1.0"
    application
}

group = "dev.bikini"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("net.java.dev.jna:jna:5.15.0")
    implementation("net.java.dev.jna:jna-platform:5.15.0")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("poc.MainKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
