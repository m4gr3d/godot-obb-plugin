import com.android.build.gradle.internal.tasks.factory.dependsOn

plugins {
    alias(libs.plugins.android.library)
}

val pluginName = "GodotObbPlugin"

val pluginPackageName = "org.godotengine.plugin.obb"

base {
    archivesName.set(pluginName)
}

android {
    namespace = pluginPackageName
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        manifestPlaceholders["godotPluginName"] = pluginName
        manifestPlaceholders["godotPluginPackageName"] = pluginPackageName
        buildConfigField("String", "GODOT_PLUGIN_NAME", "\"${pluginName}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }
}

dependencies {
    compileOnly(fileTree("libs") { include("*.jar", "*.aar") })

    compileOnly(libs.godot)
    implementation(libs.androidx.appcompat)
}

// BUILD TASKS DEFINITION
val copyDebugAARToAddons by tasks.registering(Copy::class) {
    description = "Copies the generated debug AAR binary to the addons directory"
    from("build/outputs/aar")
    include("$pluginName-debug.aar")
    into("../demo/addons/$pluginName/bin/debug")
}

val copyReleaseAARToAddons by tasks.registering(Copy::class) {
    description = "Copies the generated release AAR binary to the addons directory"
    from("build/outputs/aar")
    include("$pluginName-release.aar")
    into("../demo/addons/$pluginName/bin/release")
}

val cleanAddons by tasks.registering(Delete::class) {
    delete("../demo/addons/$pluginName")
}

val copyPluginToAddons by tasks.registering(Copy::class) {
    description = "Copies the export scripts to the addons directory"

    dependsOn(cleanAddons)
    finalizedBy(copyDebugAARToAddons)
    finalizedBy(copyReleaseAARToAddons)

    from("export_scripts")
    into("../demo/addons/$pluginName")
}

tasks.named("assemble").configure {
    finalizedBy(copyPluginToAddons)
}

tasks.named<Delete>("clean").apply {
    dependsOn(cleanAddons)
}
