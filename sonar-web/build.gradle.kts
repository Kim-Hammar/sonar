plugins {
    id("sonar.java-library")
}

dependencies {
    api(project(":sonar-app"))
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    testImplementation(testFixtures(project(":sonar-app")))
}

tasks.named<ProcessResources>("processResources") {
    from(layout.projectDirectory.dir("frontend/dist")) {
        into("se/kth/sonar/web/ui")
    }
}
