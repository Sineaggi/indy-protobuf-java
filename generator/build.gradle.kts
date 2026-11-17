plugins { application }

dependencies {
    implementation(project(":runtime"))
}

application {
    mainClass.set("dev.indyproto.gen.Gen")
}

// protoc plugin: `protoc --plugin=protoc-gen-indy=<script> --indy_out=DIR foo.proto`.
// A self-contained jar (java -jar protoc-gen-indy.jar reads a CodeGeneratorRequest on stdin) ...
val protocGenJar by tasks.registering(Jar::class) {
    archiveFileName.set("protoc-gen-indy.jar")
    destinationDirectory.set(layout.buildDirectory.dir("protoc-gen"))
    manifest { attributes("Main-Class" to "dev.indyproto.gen.Plugin") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets["main"].output)
    val runtimeClasspath = configurations["runtimeClasspath"]
    inputs.files(runtimeClasspath)
    from({ runtimeClasspath.map { if (it.isDirectory) it else zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
    }
}

// ... and a protoc-gen-indy / protoc-gen-indy.bat launcher next to `gen` in installDist's bin/.
val protocGenScripts by tasks.registering(CreateStartScripts::class) {
    applicationName = "protoc-gen-indy"
    mainClass.set("dev.indyproto.gen.Plugin")
    outputDir = layout.buildDirectory.dir("protoc-gen-scripts").get().asFile
    classpath = tasks.jar.get().outputs.files + configurations["runtimeClasspath"]
}
distributions.main { contents { into("bin") { from(protocGenScripts) } } }
