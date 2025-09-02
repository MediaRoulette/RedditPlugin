plugins {
    id("java")
    id("com.gradleup.shadow") version "9.0.2"
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

group = "me.mediaroulette.reddit"
version = "1.0-SNAPSHOT"

dependencies {
    shadow("com.github.MediaRoulette:MediaRoulette:v1.0.76")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveClassifier.set("")
    manifest {
        attributes(
            "Main-Class" to "me.mediaroulette.reddit.Main",
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version
        )
    }
    mergeServiceFiles()
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
