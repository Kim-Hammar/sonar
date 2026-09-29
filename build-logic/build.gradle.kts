plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.spotless.gradle.plugin)
    implementation(libs.errorprone.gradle.plugin)
}
