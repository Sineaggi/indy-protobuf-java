plugins { `java-library` }

dependencies {
    api("com.google.protobuf:protobuf-java:${rootProject.extra["protobufVersion"]}")
}
