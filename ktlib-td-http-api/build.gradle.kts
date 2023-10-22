dependencies {
    api(project(":ktlib-td"))

    api("com.github.pengrad:java-telegram-bot-api:6.9.1") {
        exclude("com.squareup.okhttp3")
        exclude("com.google.code.gson", "gson")
    }

    api("com.google.code.gson:gson:2.9.0")
}
