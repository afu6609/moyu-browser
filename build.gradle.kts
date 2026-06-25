plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "com.moyu"
version = "1.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Build against the IDE that is already installed on this machine.
        // This avoids downloading a ~1GB platform SDK. Point this at YOUR IDEA install
        // if you move the project to another machine.
        local("D:/idea/IntelliJ IDEA 2024.2")
    }

    // JNA is bundled inside the IDE (util-8.jar) -> compileOnly, do NOT ship it.
    compileOnly("net.java.dev.jna:jna:5.14.0")
    compileOnly("net.java.dev.jna:jna-platform:5.14.0")
}

intellijPlatform {
    // No settings UI -> skip the headless IDE run that indexes searchable options.
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            // open-ended: keep loading on future IDE builds (incl. 2026.1 / build 261)
            untilBuild = provider { null }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
