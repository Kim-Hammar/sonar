import sonar.build.VerifyBatfishJar

plugins {
    id("sonar.java-library")
}

val batfishJar = layout.settingsDirectory.file(providers.gradleProperty("sonar.batfish.jar"))

val verifyBatfishJar = tasks.register<VerifyBatfishJar>("verifyBatfishJar") {
    description = "Verifies the embedded Batfish jar before anything compiles against it."
    jar.from(batfishJar)
    expectedSha256 = providers.gradleProperty("sonar.batfish.sha256")
    expectedBundledVersions.put("com.fasterxml.jackson.core:jackson-databind", libs.versions.jackson.get())
    stamp = layout.buildDirectory.file("batfish/verified.sha256")
}

dependencies {
    api(project(":sonar-core"))
    implementation(files(batfishJar).builtBy(verifyBatfishJar))
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
}
