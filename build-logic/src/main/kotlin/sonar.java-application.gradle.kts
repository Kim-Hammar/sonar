plugins {
    id("sonar.java-conventions")
    application
}

val runtimeFlags = listOf(
    "--enable-native-access=ALL-UNNAMED",
    "--sun-misc-unsafe-memory-access=allow",
    "-XX:MaxRAMPercentage=75",
)

application {
    applicationDefaultJvmArgs = runtimeFlags
}

tasks.withType<Test>().configureEach {
    jvmArgs(runtimeFlags)
}

val launcher = javaToolchains.launcherFor(java.toolchain)

tasks.register("printJavaHome") {
    description = "Prints the home directory of the Java toolchain that runs the application."
    val home = launcher.map { it.metadata.installationPath.asFile.absolutePath }
    doLast {
        println(home.get())
    }
}
