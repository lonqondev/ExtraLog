plugins {
    java
    id("com.gradleup.shadow") version "8.3.8"
}

group = "com.lonqon.extralog"
version = "1.0.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("net.dv8tion:JDA:6.7.0") {
        exclude(module = "slf4j-api")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.shadowJar {
    archiveBaseName.set("ExtraLog")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
