import org.gradle.buildconfiguration.tasks.UpdateDaemonJvm

tasks.named<UpdateDaemonJvm>("updateDaemonJvm") {
    languageVersion = JavaLanguageVersion.of(libs.versions.java.get())
    vendor = JvmVendorSpec.ADOPTIUM
}
