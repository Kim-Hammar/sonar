plugins {
    id("sonar.java-library")
}

dependencies {
    api(project(":sonar-core"))
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
}
