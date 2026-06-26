pluginManagement {
    repositories {
        gradlePluginPortal()
        maven { 
            url = uri("https://repo.shoresmp.net/private") 
            credentials {
                username = System.getenv("REPOSILITE_USER")
                password = System.getenv("REPOSILITE_TOKEN")
            }
        }
    }
}

rootProject.name = "CheckHacks"
