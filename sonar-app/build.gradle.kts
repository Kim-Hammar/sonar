plugins {
    id("sonar.java-library")
    `java-test-fixtures`
}

dependencies {
    api(project(":sonar-core"))
    api(project(":sonar-dataset"))
    api(project(":sonar-jev"))
    implementation(project(":sonar-batfish"))
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "8g"
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
}

tasks.test {
    useJUnitPlatform { excludeTags("audit") }
}

val audit by tasks.registering(Test::class) {
    description = "Runs the dataset audits, which need ../dataset and the ground truth in ../../aros."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("audit") }
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = true
    for (name in listOf("sonar.dataset", "sonar.truth", "sonar.audit.scenarios", "sonar.audit.focus", "sonar.probe.edit", "sonar.probe.site", "sonar.probe.shown", "sonar.probe.specifications")) {
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
}

val flightRecording = providers.gradleProperty("sonar.jfr")
tasks.withType<Test>().configureEach {
    if (flightRecording.isPresent) {
        jvmArgs("-XX:StartFlightRecording=filename=${flightRecording.get()},settings=profile")
    }
}
