plugins { java }

val jmhVersion = "1.37"

dependencies {
    implementation(project(":samples"))
    implementation("org.openjdk.jmh:jmh-core:$jmhVersion")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:$jmhVersion")
}

// ---- generated "user code" that touches every message of the large schema (startup test) ----
val startupSrc = layout.buildDirectory.dir("gen/startup-java")
val genStartup by tasks.registering {
    val count = 400
    outputs.dir(startupSrc)
    doLast {
        for ((v, pkg) in listOf("Stock" to "many", "Indy" to "indy.many")) {
            val f = startupSrc.get().file("bench/startup/Touch$v.java").asFile
            f.parentFile.mkdirs()
            f.writeText(buildString {
                appendLine("package bench.startup;")
                appendLine("public final class Touch$v {")
                appendLine("  public static long touch(int n) throws Exception {")
                appendLine("    long acc = 0;")
                for (i in 0 until count) {
                    appendLine("    if ($i < n) {")
                    appendLine("      $pkg.M$i m = $pkg.M$i.newBuilder().setName(\"m$i\").setId($i).addTags(\"t\").setOn(true).build();")
                    appendLine("      byte[] b = m.toByteArray();")
                    appendLine("      $pkg.M$i p = $pkg.M$i.parseFrom(b);")
                    appendLine("      if (!p.equals(m)) throw new AssertionError();")
                    appendLine("      acc += p.hashCode() + b.length;")
                    appendLine("    }")
                }
                appendLine("    return acc;")
                appendLine("  }")
                appendLine("}")
            })
        }
    }
}
sourceSets["main"].java.srcDir(genStartup.map { startupSrc.get() })

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:none")
}

val jmh by tasks.registering(JavaExec::class) {
    description = "Run JMH. Pass extra args with -PjmhArgs='...'"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    args((findProperty("jmhArgs") as String? ?: "").split(" ").filter { it.isNotBlank() })
}

val sizeReport by tasks.registering(JavaExec::class) {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("bench.report.SizeReport")
    args(project(":samples").layout.buildDirectory.get().asFile.absolutePath)
}

val printClasspath by tasks.registering {
    doLast { println(sourceSets["main"].runtimeClasspath.asPath) }
}
