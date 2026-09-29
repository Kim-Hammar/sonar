plugins {
    id("sonar.java-library")
}

val verifyPureJdk = tasks.register("verifyPureJdk") {
    description = "Fails when sonar-core depends on anything other than the JDK."
    val runtime: FileCollection = configurations.runtimeClasspath.get()
    inputs.files(runtime)
    doLast {
        if (!runtime.isEmpty) {
            throw GradleException("sonar-core must depend on the JDK only, found ${runtime.files}")
        }
    }
}

tasks.named("check") {
    dependsOn(verifyPureJdk)
}
