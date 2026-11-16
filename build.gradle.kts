plugins {
    java
}

allprojects {
    group = "dev.indyproto"
    version = "0.1.0-SNAPSHOT"
}

val protobufVersion by extra("4.36.2")

subprojects {
    apply(plugin = "java")
    repositories { mavenCentral() }
    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(25)
        options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-Xlint:-this-escape"))
    }
}
