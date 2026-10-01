plugins {
    //kotlin("jvm") version "2.0.0"
    kotlin("jvm") version "2.2.20"
}

group = "de.minimax"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.werthersechte:cathedral-ai:2.0.1")
}

kotlin {
    jvmToolchain(21)
}