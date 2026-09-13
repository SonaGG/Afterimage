plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.fabric.loom)
    alias(libs.plugins.ploceus)
}

ploceus {
    setIntermediaryGeneration(2)
}

repositories {
    maven("https://jitpack.io") {
        content {
            includeGroupByRegex("com[.]github[.].*")
        }
        name = "JitPack"
    }
    maven("https://maven.legacyfabric.net/") { name = "legacy-fabric" }
    maven(url = "https://maven.axolotlclient.com/releases") { name = "axolotl-client" }
    mavenCentral()
    exclusiveContent {
        forRepository { mavenCentral() }
        filter { includeGroup("org.lwjgl") }
    }
}

dependencies {
    minecraft(libs.minecraft)
    mappings(loom.layered {
        mappings(ploceus.featherMappings(property("feather.build") as String))
        mappings(rootProject.file("mappings/feather-overrides.tiny"))
    })

    modImplementation(libs.legacy.lwjgl3)
    modImplementation(libs.fabric.loader)
    ploceus.dependOsl(libs.versions.osl.get())

    api(project(":recast-replay"))
    api(project(":recast-camera"))
    api(project(":recast-clip"))
    api(project(":recast-flashback"))
    api(project(":recast-editor-core"))
    api(project(":recast-render"))
    api(project(":recast-editor-imgui"))

    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
}

loom {
    accessWidenerPath.set(file("src/main/resources/recast-adapter.accesswidener"))
}

configurations.all {
    exclude(group = "org.lwjgl.lwjgl")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjvm-default=all")
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.processResources {
    inputs.property("version", project.version)
    filteringCharset = "UTF-8"
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
