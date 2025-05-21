plugins {
    id("java")
}

group = "io.github.danielreker"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    implementation("org.yaml:snakeyaml:2.4")
}

tasks.test {
    useJUnitPlatform()
}