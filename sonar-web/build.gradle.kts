plugins {
    id("sonar.java-library")
}

dependencies {
    api(project(":sonar-app"))
    implementation(project(":sonar-batfish"))
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    testImplementation(testFixtures(project(":sonar-app")))
}

tasks.named<ProcessResources>("processResources") {
    from(layout.projectDirectory.dir("frontend/dist")) {
        into("se/kth/sonar/web/ui")
    }
}
