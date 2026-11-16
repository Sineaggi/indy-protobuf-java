plugins { application }

dependencies {
    implementation(project(":runtime"))
}

application {
    mainClass.set("dev.indyproto.gen.Gen")
}
