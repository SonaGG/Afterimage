plugins {
    id("org.jetbrains.kotlin.jvm")
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjvm-default=all", "-Xjsr305=strict")
    }
}

java {
    withSourcesJar()
}