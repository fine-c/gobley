/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package gobley.gradle.uniffi.tasks

import gobley.gradle.uniffi.Config
import gobley.gradle.uniffi.dsl.CustomType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import net.peanuuutz.tomlkt.Toml
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

@CacheableTask
abstract class MergeUniffiConfigTask : DefaultTask() {
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val originalConfig: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val packageName: Property<String>

    @get:Input
    @get:Optional
    abstract val cdylibName: Property<String>

    @get:Input
    @get:Optional
    abstract val kotlinMultiplatform: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val kotlinTargets: ListProperty<String>

    @get:Input
    @get:Optional
    abstract val generateImmutableRecords: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val customTypes: MapProperty<String, CustomType>

    @get:Input
    @get:Optional
    abstract val externalPackageConfigByCrateName: MapProperty<String, File>

    @get:Input
    @get:Optional
    abstract val kotlinVersion: Property<String>

    @get:Input
    @get:Optional
    abstract val disableJavaCleaner: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val useKotlinXSerialization: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val usePascalCaseEnumClass: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val jvmDynamicLibraryDependencies: ListProperty<String>

    @get:Input
    @get:Optional
    abstract val androidDynamicLibraryDependencies: ListProperty<String>

    @get:Input
    @get:Optional
    abstract val dynamicLibraryDependencies: ListProperty<String>

    @get:OutputFile
    abstract val outputConfig: RegularFileProperty

    @TaskAction
    fun mergeConfig() {
        val originalConfig = originalConfig.orNull?.asFile?.let(::loadConfig) ?: Config()
        val result = originalConfig.copy(
            packageName = originalConfig.packageName ?: packageName.orNull,
            cdylibName = originalConfig.cdylibName ?: cdylibName.orNull,
            kotlinMultiplatform = originalConfig.kotlinMultiplatform ?: kotlinMultiplatform.orNull,
            kotlinTargets = mergeSet(
                originalConfig.kotlinTargets,
                kotlinTargets.orNull,
            ),
            generateImmutableRecords = originalConfig.generateImmutableRecords
                ?: generateImmutableRecords.orNull,
            customTypes = mergeMap(
                originalConfig.customTypes,
                customTypes.map {
                    it.mapValues { entry ->
                        Config.CustomType(
                            imports = entry.value.imports.orNull,
                            typeName = entry.value.typeName.orNull,
                            intoCustom = entry.value.intoCustom.orNull,
                            fromCustom = entry.value.fromCustom.orNull,
                        )
                    }
                }.orNull,
            ),
            externalPackages = mergeMap(
                originalConfig.externalPackages,
                externalPackageConfigByCrateName.orNull?.let(::retrieveExternalPackageNames),
            ),
            kotlinTargetVersion = originalConfig.kotlinTargetVersion
                ?: kotlinVersion.orNull?.takeIf { it.isNotBlank() },
            disableJavaCleaner = originalConfig.disableJavaCleaner
                ?: disableJavaCleaner.orNull,
            generateSerializableTypes = originalConfig.generateSerializableTypes
                ?: useKotlinXSerialization.orNull,
            usePascalCaseEnumClass = originalConfig.usePascalCaseEnumClass
                ?: usePascalCaseEnumClass.orNull,
            jvmDynamicLibraryDependencies = mergeSet(
                originalConfig.jvmDynamicLibraryDependencies,
                jvmDynamicLibraryDependencies.orNull,
            ),
            androidDynamicLibraryDependencies = mergeSet(
                originalConfig.androidDynamicLibraryDependencies,
                androidDynamicLibraryDependencies.orNull,
            ),
            dynamicLibraryDependencies = mergeSet(
                originalConfig.dynamicLibraryDependencies,
                dynamicLibraryDependencies.orNull,
            )
        )
        outputConfig.get().asFile.writeText(toml.encodeToString(result), Charsets.UTF_8)
    }

    private fun loadConfig(file: File): Config {
        return toml.decodeFromString<Config>(file.readText(Charsets.UTF_8))
    }

    private fun retrieveExternalPackageNames(
        externalPackageConfigByCrateName: Map<String, File>
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for ((crateName, configFile) in externalPackageConfigByCrateName) {
            val config = loadConfig(configFile)
            if (!result.contains(crateName)) {
                if (config.packageName != null) {
                    result[crateName] = config.packageName
                }
            }
            if (config.externalPackages != null) {
                for ((externalCrateName, externalPackageName) in config.externalPackages) {
                    if (!result.contains(externalCrateName)) {
                        result[crateName] = externalPackageName
                    }
                }
            }
        }
        return result.toMap()
    }

    private fun <T> mergeMap(
        original: Map<String, T>?,
        new: Map<String, T>?,
    ): Map<String, T>? {
        if (original == null) return new
        if (new == null) return original

        val newMap = original.toMutableMap()
        for ((newKey, newValue) in new) {
            if (!newMap.contains(newKey)) {
                newMap[newKey] = newValue
            }
        }

        return newMap.toMap()
    }

    private fun mergeSet(lhs: List<String>?, rhs: List<String>?): List<String>? {
        if (lhs == null) return rhs
        if (rhs == null) return lhs
        return lhs + rhs
    }

    companion object {
        private val toml = Toml {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}