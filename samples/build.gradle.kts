import org.apache.tools.ant.filters.ReplaceTokens

plugins { `java-library` }

val protobufVersion = rootProject.extra["protobufVersion"] as String

val osClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val o = when {
        os.contains("mac") -> "osx"
        os.contains("win") -> "windows"
        else -> "linux"
    }
    val a = if (arch == "aarch64" || arch == "arm64") "aarch_64" else "x86_64"
    "$o-$a"
}

val protoc by configurations.creating
dependencies {
    protoc("com.google.protobuf:protoc:$protobufVersion:$osClassifier@exe")
}

val gen = layout.buildDirectory.dir("gen")
val synthDir = gen.map { it.dir("synth-proto") }
val stockJava = gen.map { it.dir("stock-java") }
val descriptorSet = gen.map { it.file("descriptors.pb") }
val indyClasses = gen.map { it.dir("indy-classes") }

/** Synthetic schemas: one very wide message, and a large schema of many small messages. */
val synthProtos by tasks.registering {
    val manyCount = (findProperty("manyCount") as String?)?.toInt() ?: 400
    inputs.property("manyCount", manyCount)
    outputs.dir(synthDir)
    doLast {
        val dir = synthDir.get().asFile
        dir.mkdirs()
        val types = listOf("int32", "int64", "string", "bool", "double", "uint32", "bytes", "sint64", "float", "fixed32")
        File(dir, "wide.proto").writeText(buildString {
            appendLine("syntax = \"proto3\";\npackage wide;\noption java_package = \"wide\";\noption java_multiple_files = true;")
            appendLine("message Wide {")
            for (i in 1..64) appendLine("  ${types[i % types.size]} f$i = $i;")
            appendLine("  repeated int32 r65 = 65;\n  repeated string r66 = 66;")
            appendLine("}")
        })
        File(dir, "many.proto").writeText(buildString {
            appendLine("syntax = \"proto3\";\npackage many;\noption java_package = \"many\";\noption java_multiple_files = true;")
            appendLine("enum Color { COLOR_UNSPECIFIED = 0; RED = 1; GREEN = 2; }")
            for (i in 0 until manyCount) {
                appendLine("message M$i {")
                appendLine("  string name = 1;\n  int64 id = 2;\n  repeated string tags = 3;\n  bool on = 4;")
                appendLine("  double score = 5;\n  Color color = 6;\n  optional int32 opt = 7;")
                if (i > 0) appendLine("  M${i - 1} prev = 8;\n  repeated M${i - 1} prevs = 9;")
                appendLine("}")
            }
        })
    }
}

val wktProtoJar by configurations.creating { isTransitive = false }
dependencies { wktProtoJar("com.google.protobuf:protobuf-java:$protobufVersion") }
val wktDir = gen.map { it.dir("wkt-proto") }
val wktProtos by tasks.registering(Copy::class) {
    from({ zipTree(wktProtoJar.singleFile) }) { include("google/protobuf/*.proto") }
    into(wktDir)
}

val protocGen by tasks.registering(Exec::class) {
    dependsOn(synthProtos, wktProtos)
    val protoDir = file("src/main/proto")
    inputs.dir(protoDir)
    inputs.dir(synthDir)
    inputs.files(protoc)
    outputs.dir(stockJava)
    outputs.file(descriptorSet)
    doFirst {
        stockJava.get().asFile.deleteRecursively()
        stockJava.get().asFile.mkdirs()
        protoc.singleFile.setExecutable(true)
    }
    val protos = fileTree(protoDir) { include("**/*.proto") }.files.map { it.name } + listOf("wide.proto", "many.proto")
    executable = protoc.singleFile.absolutePath
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("-I", protoDir.absolutePath, "-I", synthDir.get().asFile.absolutePath,
            "-I", wktDir.get().asFile.absolutePath,
            "--java_out=${stockJava.get().asFile.absolutePath}",
            "--descriptor_set_out=${descriptorSet.get().asFile.absolutePath}",
            "--include_imports") + protos
    })
}

val indyGen by tasks.registering(JavaExec::class) {
    dependsOn(protocGen)
    inputs.file(descriptorSet)
    inputs.files(project(":generator").sourceSets["main"].runtimeClasspath)
    outputs.dir(indyClasses)
    classpath = project(":generator").sourceSets["main"].runtimeClasspath
    mainClass.set("dev.indyproto.gen.Gen")
    doFirst { indyClasses.get().asFile.deleteRecursively() }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--descriptor-set", descriptorSet.get().asFile.absolutePath,
            "--out", indyClasses.get().asFile.absolutePath,
            "--rewrite", "sample=indy.sample", "--rewrite", "bench=indy.bench",
            "--rewrite", "wide=indy.wide", "--rewrite", "many=indy.many", "--rewrite", "shapes=indy.shapes")
    })
}

sourceSets["main"].java.srcDir(protocGen.map { stockJava.get() })

dependencies {
    api(project(":runtime"))
    api(files(indyClasses).builtBy(indyGen))
    api("com.google.protobuf:protobuf-java-util:$protobufVersion")
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    if (name == "compileJava") options.compilerArgs.add("-Xlint:none")
}

// "User code" written once against the protoc API and compiled twice: against the stock
// classes and against the indy classes. This is the source-compatibility check.
val compatVariants = mapOf("stock" to "", "indy" to "indy.")
val compatCopies = compatVariants.map { (v, prefix) ->
    tasks.register<Copy>("compatSources${v.replaceFirstChar { it.uppercase() }}") {
        from("src/compat/java")
        into(gen.map { it.dir("compat-$v") })
        filter<ReplaceTokens>("tokens" to mapOf("V" to v, "PKG" to prefix))
    }
}
sourceSets["test"].java.srcDirs(compatCopies.map { t -> t.map { it.destinationDir } })

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Dindyproto.strategy=${findProperty("strategy") ?: "hidden"}")
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
