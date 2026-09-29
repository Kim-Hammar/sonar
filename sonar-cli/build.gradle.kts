plugins {
    id("sonar.java-application")
}

dependencies {
    implementation(project(":sonar-app"))
    implementation(project(":sonar-web"))
    implementation(libs.picocli)
    testImplementation(testFixtures(project(":sonar-app")))
}

application {
    mainClass = "se.kth.sonar.cli.SonarCli"
    applicationName = "sonar"
}

tasks.named<Tar>("distTar") {
    enabled = false
}

tasks.named<Zip>("distZip") {
    entryCompression = ZipEntryCompression.STORED
}
