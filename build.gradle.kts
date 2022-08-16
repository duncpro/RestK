plugins {
    kotlin("jvm") version "1.6.21"
    id("org.jetbrains.dokka") version "1.6.21"
    jacoco
    `maven-publish`
    java
}

group = "com.duncpro.restk"
version = "1.0-SNAPSHOT-20"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    api("com.duncpro:jroute:1.0-SNAPSHOT-12")
    testImplementation("io.ktor:ktor-client-cio:2.1.0")
    testImplementation("io.ktor:ktor-client-core-jvm:2.1.0")
    testImplementation("org.slf4j:slf4j-simple:1.7.36")
    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.6.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")
}

tasks.check {
    finalizedBy(jacocoTestReport)
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}

val jacocoTestReport by tasks.getting(JacocoReport::class) {
    classDirectories.setFrom(sourceSets.main.get().output)
    sourceDirectories.setFrom(sourceSets.main.get().allSource.srcDirs)
    executionData.setFrom(fileTree(project.rootDir.absolutePath).include("**/build/jacoco/*.exec"))
    reports {
        xml.isEnabled = true
        html.isEnabled = false
    }
    sourceSets {
        add(main.get())
    }
}

java.sourceCompatibility = JavaVersion.VERSION_11
java.targetCompatibility = JavaVersion.VERSION_11

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}