plugins {
    id("streampulse.java-conventions")
    application
}

dependencies {
    implementation(libs.jackson.databind)
}

application {
    mainClass = "com.streampulse.loadgen.LoadGen"
}
