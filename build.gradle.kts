import java.net.URI

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.6.20"
    id("org.jetbrains.intellij") version "1.5.2"
}

group = "com.github.joehaivo"
version = "1.0.3"

repositories {
    mavenCentral()
    google()
}

// Configure Gradle IntelliJ Plugin - read more: https://github.com/JetBrains/gradle-intellij-plugin
intellij {
//    version.set("2021.2")
//    type.set("IC") // Target IDE Platform
    localPath.set("/Applications/Android Studio.app/Contents")
    plugins.set(
        listOf(
//            "Kotlin",
//            "android",
//            "git4idea",
            "java",
//            "org.jetbrains.kotlin",
//            "org.intellij.groovy",
//            "org.jetbrains.android"
        )
    )
}

dependencies {
//    compileOnly("com.github.adedayo.intellij.sdk:dom-openapi:142.1")
}

tasks {
    instrumentCode {
        compilerVersion.set("211.7628.21")
    }
    patchPluginXml {
        pluginDescription.set("""
            Remove the ButterKnife annotation(@BindView、@OnClick) from the Java file and convert it to 'findViewById' code. <br><br>
            移除Java文件中的ButterKnife依赖(@BindView、@OnClick)，将其转化为'findViewById'代码。<br>
            <a href="https://github.com/Joehaivo/RemoveButterKnife">Github README for more detail >></a><br>
        """)
        changeNotes.set("""
           2022.7.15: add `RemoveButterknife` menu.<br> 
        """)
        sinceBuild.set("201.*")
        untilBuild.set("222.*")
    }
    buildSearchableOptions {
        enabled = false
    }
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "11"
        targetCompatibility = "11"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "11"
    }

    patchPluginXml {
        sinceBuild.set("212")
        untilBuild.set("222.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
