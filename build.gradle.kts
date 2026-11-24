plugins {
    kotlin("jvm") version "2.4.20"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("net.java.dev.jna:jna:5.15.0")
    implementation("net.java.dev.jna:jna-platform:5.15.0")
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("poc.MainKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
