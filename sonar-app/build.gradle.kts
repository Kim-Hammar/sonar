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
