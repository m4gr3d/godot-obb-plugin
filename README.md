## Godot OBB Android plugin

Support for the Play store obb API in Godot [was deprecated and removed](https://github.com/godotengine/godot/pull/118283)
in Godot 4.7 and the logic was moved to this plugin.

The API itself was deprecated by Google in [August 2021](https://developer.android.com/google/play/expansion-files)
in favor of [Android App Bundle](https://developer.android.com/guide/app-bundle),
[Play feature delivery](https://developer.android.com/guide/playcore/feature-delivery) and
[Play asset delivery](http://developer.android.com/guide/playcore/asset-delivery).
As such, this plugin exists primarily to support existing projects in the Play store that still
have a dependency on the Play store obb API.

**Note:**

This plugin requires **Godot 4.7 or newer**

### Building the plugin

After cloning this project, run the following command in the project root directory to build it:
```
./gradlew assemble
```

This will generate and export the plugin in the `demo/addons/GodotObbPlugin` directory.


### Installing and using the plugin

- Download the latest release from the [Releases](https://github.com/m4gr3d/godot-obb-plugin/releases) page.
- Unzip and copy the plugin to your project's `addons` folder, creating one if necessary:
```
<your_project>/addons/GodotObbPlugin
```
- In the Godot editor:
  - Go to: **Project > Project Settings > Plugins**
  - Enable **GodotObbPlugin**
- The plugin requires `gradle builds` so if you haven't already, follow [these instructions](https://docs.godotengine.org/en/stable/tutorials/export/android_gradle_build.html) 
to set up and enable **Gradle build**
- Enable `apk_expansion` for your project's Android export presets:
  - Go to: **Project > Export**
  - Select (or create) an Android preset
  - Navigate to the bottom of the **Options** screen to enable and configure the obb plugin's 
    options

