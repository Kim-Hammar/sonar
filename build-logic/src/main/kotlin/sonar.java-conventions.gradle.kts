import com.diffplug.gradle.spotless.SpotlessTask
import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import sonar.build.SpotlessLane

plugins {
    java
    jacoco
    checkstyle
    id("com.diffplug.spotless")
    id("net.ltgt.errorprone")
}

val catalog = the<VersionCatalogsExtension>().named("libs")

fun version(alias: String): String = catalog.findVersion(alias).get().requiredVersion

fun library(alias: String) = catalog.findLibrary(alias).get()

val javaVersion = version("java").toInt()
val warningsAsErrors = providers.gradleProperty("sonar.werror").map(String::toBoolean).getOrElse(true)

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
        vendor = JvmVendorSpec.ADOPTIUM
    }
}

dependencies {
    errorprone(library("errorprone-core"))
    testImplementation(platform(library("junit-bom")))
    testImplementation(library("junit-jupiter"))
    testImplementation(library("assertj-core"))
    testRuntimeOnly(library("junit-platform-launcher"))
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaVersion
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-Xdoclint:all,-missing"))
    if (warningsAsErrors) {
        options.compilerArgs.add("-Werror")
    }
    options.errorprone.disableWarningsInGeneratedCode = true
    options.errorprone.disable("DoNotCallSuggester")
}

tasks.withType<Jar>().configureEach {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version.toString(),
        )
    }
}

jacoco {
    toolVersion = version("jacoco")
}

val coverageScope = "${project.group}.*"

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("-da:org.batfish...")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
    extensions.configure<JacocoTaskExtension> {
        includes = listOf(coverageScope)
    }
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestReport"))
}

val spotlessLane = gradle.sharedServices.registerIfAbsent("spotless-lane", SpotlessLane::class) {
    maxParallelUsages = 1
}

tasks.withType<SpotlessTask>().configureEach {
    usesService(spotlessLane)
}

tasks.named("clean") {
    usesService(spotlessLane)
}

spotless {
    java {
        googleJavaFormat(version("google-java-format"))
        forbidWildcardImports()
        formatAnnotations()
    }
}

checkstyle {
    toolVersion = version("checkstyle")
    configDirectory = layout.settingsDirectory.dir("config/checkstyle")
    maxWarnings = 0
}
