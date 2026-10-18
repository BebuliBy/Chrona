import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.10"
    application
}

group = "by.bebuliby.chrona"
version = "0.1.0"

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:3.3.3")
    implementation("io.ktor:ktor-server-netty-jvm:3.3.3")
    implementation("io.ktor:ktor-server-status-pages-jvm:3.3.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.postgresql:postgresql:42.7.8")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
    testImplementation("com.h2database:h2:2.3.232")
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.3.3")
}

application { mainClass.set("by.bebuliby.chrona.backend.MainKt") }
tasks.test { useJUnitPlatform(); testLogging { events("passed", "skipped", "failed") } }
