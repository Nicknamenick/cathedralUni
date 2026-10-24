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

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Runs the Cathedral agent. Use -PappArgs=\"eval 5\" to pass program arguments."
    mainClass.set("main.bot.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Xmx8g")

    val programArguments = (project.findProperty("appArgs") as String?)
        ?.trim()
        ?.split(Regex("\\s+"))
        ?.filter { it.isNotEmpty() }
        ?: emptyList()
    setArgs(programArguments)
}