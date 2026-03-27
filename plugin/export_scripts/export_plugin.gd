@tool
extends EditorPlugin

# A class member to hold the editor export plugin during its lifecycle.
var export_plugin : AndroidExportPlugin

func _enter_tree() -> void:
	# Initialization of the plugin goes here.
	export_plugin = AndroidExportPlugin.new()
	add_export_plugin(export_plugin)


func _exit_tree() -> void:
	# Clean-up of the plugin goes here.
	remove_export_plugin(export_plugin)
	export_plugin = null


class AndroidExportPlugin extends EditorExportPlugin:
	const GRADLE_APK_EXPORT_FORMAT = 0
	const GRADLE_AAB_EXPORT_FORMAT = 1

	var _export_pack_saving_in_progress = false

	var _plugin_name = "GodotObbPlugin"

	func _supports_platform(platform: EditorExportPlatform) -> bool:
		if platform is EditorExportPlatformAndroid:
			return true
		return false

	func _get_android_libraries(platform, debug):
		if debug:
			return PackedStringArray([_plugin_name + "/bin/debug/" + _plugin_name + "-debug.aar"])
		else:
			return PackedStringArray([_plugin_name + "/bin/release/" + _plugin_name + "-release.aar"])

	func _get_android_dependencies(platform, debug):
		if debug:
			return PackedStringArray([])
		else:
			return PackedStringArray([])

	func _get_name() -> String:
		return _plugin_name

	func _get_export_options(platform: EditorExportPlatform) -> Array[Dictionary]:
		var options: Array[Dictionary] = []
		if !_supports_platform(platform):
			return options

		options.append({
			"option": {"name": "apk_expansion/enable", "type": TYPE_BOOL},
			"default_value": false
		})
		options.append({
			"option": {"name": "apk_expansion/SALT", "type": TYPE_STRING},
			"default_value": ""
		})
		options.append({
			"option": {
				"name": "apk_expansion/public_key",
				"type": TYPE_STRING,
				"hint": PROPERTY_HINT_MULTILINE_TEXT,
				"hint_string": "monospace,no_wrap"
			},
			"default_value": ""
		})

		return options

	func _get_export_option_warning(platform: EditorExportPlatform, option: String) -> String:
		if !_supports_platform(platform):
			return ""

		if option == "apk_expansion/public_key":
			var apk_expansion = get_option("apk_expansion/enable")
			var apk_expansion_pkey = get_option("apk_expansion/public_key")
			if apk_expansion and apk_expansion_pkey.is_empty():
				return "Invalid public key for APK expansion."
		elif option == "apk_expansion/enable":
			var use_gradle_build = get_option("gradle_build/use_gradle_build")
			var export_format = get_option("gradle_build/export_format")
			if use_gradle_build and export_format == GRADLE_AAB_EXPORT_FORMAT:
				return "APK expansion is not supported for AAB format."
		return ""

	func _get_export_option_visibility(platform: EditorExportPlatform, option: String) -> bool:
		var export_preset = get_export_preset()
		if export_preset and (option == "apk_expansion/enable" or option == "apk_expansion/SALT" or option == "apk_expansion/public_key"):
			return export_preset.are_advanced_options_enabled()
		return true

	func _get_valid_basename(preset: EditorExportPreset) -> String:
		var basename: String = preset.get_project_setting("application/config/name")
		basename = basename.to_lower()

		var name:= ""
		var first = true
		for character in basename:
			if  character.is_valid_int() and first:
				continue
			if character.is_valid_ascii_identifier():
				name += character
				first = false

		if name.is_empty():
			name = "noname"
		return name

	func _get_apk_expansion_fullpath(preset: EditorExportPreset, path: String) -> String:
		var version_code = get_option("version/code")
		var package_unique_name: String = get_option("package/unique_name")
		var valid_basename = _get_valid_basename(preset)
		var package_name = package_unique_name.replace("$genname", valid_basename)
		var apk_file_name = "main." + str(version_code) + "." + package_name + ".obb"
		var fullpath = path.get_base_dir().path_join(apk_file_name)
		return fullpath

	func _write_apk_expansion_command_lines(preset: EditorExportPreset, path: String) -> void:
		var apk_expansion: bool = get_option("apk_expansion/enable")
		if apk_expansion:
			var fullpath = _get_apk_expansion_fullpath(preset, path)
			var apk_expansion_public_key: String = get_option("apk_expansion/public_key")

			var command_lines = "--use_apk_expansion|"
			command_lines += "--apk_expansion_md5|" + FileAccess.get_md5(fullpath) + "|"
			command_lines += "--apk_expansion_key|" + apk_expansion_public_key.strip_edges()

			add_file("res://_obb_cl_", command_lines.to_utf8_buffer(), false)

	func _export_begin(features: PackedStringArray, is_debug: bool, path: String, flags: int) -> void:
		if _is_apk_expansion_enabled_and_valid():
			var platform = get_export_platform()
			var preset = get_export_preset()
			if not(preset) or not(platform):
				printerr("Unable to save apk expansion file!")
				return

			# Save the project to an external main pack
			var fullpath = _get_apk_expansion_fullpath(preset, path)

			_export_pack_saving_in_progress = true
			print("Saving apk expansion file to " + fullpath)
			platform.save_pack(preset, is_debug, fullpath)
			_export_pack_saving_in_progress = false

			# Add the plugin command line flags to the export files.
			# This must be done after saving the obb file otherwise the command line flags will
			# be included in the obb file instead of the generated apk.
			_write_apk_expansion_command_lines(preset, path)

	func _export_file(path: String, type: String, features: PackedStringArray) -> void:
		if _is_apk_expansion_enabled_and_valid():
			if not(_export_pack_saving_in_progress):
				# Omit the project files and resources from the generated binary.
				skip()

	func _is_apk_expansion_enabled_and_valid() -> bool:
		var apk_expansion = get_option("apk_expansion/enable")
		var apk_expansion_pkey = get_option("apk_expansion/public_key")
		var use_gradle_build = get_option("gradle_build/use_gradle_build")
		var gradle_export_format = get_option("gradle_build/export_format")
		return apk_expansion and not(apk_expansion_pkey.is_empty()) and (not(use_gradle_build) or gradle_export_format == GRADLE_APK_EXPORT_FORMAT)
