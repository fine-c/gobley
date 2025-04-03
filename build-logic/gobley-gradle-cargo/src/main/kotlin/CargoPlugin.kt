/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package gobley.gradle.cargo

import gobley.gradle.AppleSdk
import gobley.gradle.GobleyHost
import gobley.gradle.InternalGobleyGradleApi
import gobley.gradle.PluginIds
import gobley.gradle.Variant
import gobley.gradle.android.GobleyAndroidExtensionDelegate
import gobley.gradle.cargo.dsl.CargoAndroidBuild
import gobley.gradle.cargo.dsl.CargoAndroidBuildVariant
import gobley.gradle.cargo.dsl.CargoExtension
import gobley.gradle.cargo.dsl.CargoJvmBuild
import gobley.gradle.cargo.dsl.CargoJvmBuildVariant
import gobley.gradle.cargo.dsl.CargoNativeBuild
import gobley.gradle.cargo.dsl.CargoNativeBuildVariant
import gobley.gradle.cargo.dsl.jvm
import gobley.gradle.cargo.dsl.native
import gobley.gradle.cargo.tasks.CargoCleanTask
import gobley.gradle.cargo.tasks.CargoTask
import gobley.gradle.cargo.tasks.RustUpTargetAddTask
import gobley.gradle.cargo.tasks.RustUpTask
import gobley.gradle.cargo.utils.register
import gobley.gradle.kotlin.GobleyKotlinExtensionDelegate
import gobley.gradle.rust.CrateType
import gobley.gradle.rust.targets.RustAndroidTarget
import gobley.gradle.rust.targets.RustJvmTarget
import gobley.gradle.rust.targets.RustTarget
import gobley.gradle.tasks.useGlobalLock
import gobley.gradle.utils.DependencyUtils
import gobley.gradle.utils.PluginUtils
import gobley.gradle.variant
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinWithJavaTarget
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import java.io.File

class CargoPlugin : Plugin<Project> {
    companion object {
        internal const val TASK_GROUP = "cargo"
    }

    private lateinit var cargoExtension: CargoExtension

    @OptIn(InternalGobleyGradleApi::class)
    private lateinit var kotlinExtensionDelegate: GobleyKotlinExtensionDelegate

    @OptIn(InternalGobleyGradleApi::class)
    private lateinit var androidDelegate: GobleyAndroidExtensionDelegate

    override fun apply(target: Project) {
        @OptIn(InternalGobleyGradleApi::class)
        if (!target.plugins.hasPlugin(PluginIds.GOBLEY_RUST)) {
            DependencyUtils.createCargoConfigurations(target)
        }
        cargoExtension = target.extensions.create<CargoExtension>(TASK_GROUP, target)
        cargoExtension.jvmVariant.convention(Variant.Debug)
        cargoExtension.nativeVariant.convention(Variant.Debug)
        readVariantsFromXcode()
        cargoExtension.builds.native {
            nativeVariant.convention(
                cargoExtension.nativeTargetVariantOverride.getting(rustTarget)
                    .orElse(cargoExtension.nativeVariant)
            )
        }
        cargoExtension.builds.jvm {
            jvmVariant.convention(cargoExtension.jvmVariant)
        }
        @OptIn(InternalGobleyGradleApi::class)
        target.useGlobalLock()
        target.tasks.withType<CargoTask>().configureEach {
            additionalEnvironmentPath.add(cargoExtension.toolchainDirectory)
        }
        target.tasks.withType<RustUpTask>().configureEach {
            additionalEnvironmentPath.add(cargoExtension.toolchainDirectory)
        }
        target.watchPluginChanges()
        target.afterEvaluate {
            target.checkRequiredPlugins()
            target.checkKotlinTargets()
            applyAfterEvaluate(this)
        }
    }

    private fun applyAfterEvaluate(target: Project): Unit = with(target) {
        checkRequiredCrateTypes()
        if (cargoExtension.builds.isEmpty()) {
            logger.warn("No Kotlin targets detected.")
            return
        }

        configureBuildTasks()
        configureCleanTasks()

        @OptIn(InternalGobleyGradleApi::class)
        DependencyUtils.resolveCargoDependencies(target)
    }

    @OptIn(InternalGobleyGradleApi::class)
    private fun Project.watchPluginChanges() {
        PluginUtils.withKotlinPlugin(this) { delegate ->
            kotlinExtensionDelegate = delegate
            kotlinExtensionDelegate.targets.configureEach { planBuilds() }
        }
        PluginUtils.withAndroidPlugin(this) { delegate ->
            androidDelegate = delegate
            val abiFilters = androidDelegate.abiFilters
            cargoExtension.androidTargetsToBuild.convention(project.provider {
                if (abiFilters.isNotEmpty()) {
                    abiFilters.map(::RustAndroidTarget)
                } else {
                    RustAndroidTarget.values().toList()
                }
            })
        }
    }

    private fun Project.checkRequiredPlugins() {
        @OptIn(InternalGobleyGradleApi::class)
        PluginUtils.ensurePluginIsApplied(
            this,
            PluginUtils.PluginInfo(
                "Kotlin Multiplatform",
                PluginIds.KOTLIN_MULTIPLATFORM
            ),
            PluginUtils.PluginInfo(
                "Kotlin Android",
                PluginIds.KOTLIN_ANDROID,
            ),
            PluginUtils.PluginInfo(
                "Kotlin JVM",
                PluginIds.KOTLIN_JVM,
            ),
        )
    }

    private fun KotlinTarget.planBuilds() {
        for (rustTarget in requiredRustTargets()) {
            cargoExtension.createOrGetBuild(rustTarget).kotlinTargets.add(this)
        }
    }

    private fun KotlinTarget.requiredRustTargets(): List<RustTarget> {
        return when (this) {
            is KotlinJvmTarget, is KotlinWithJavaTarget<*, *> -> GobleyHost.current.platform.supportedTargets.filterIsInstance<RustJvmTarget>()
            is KotlinAndroidTarget -> {
                // listOf(GobleyHost.current.rustTarget) is for Android local unit tests.
                listOf(GobleyHost.current.rustTarget) + RustAndroidTarget.values()
            }

            is KotlinNativeTarget -> listOf(RustTarget(konanTarget))
            else -> listOf()
        }
    }

    @OptIn(InternalGobleyGradleApi::class)
    private fun Project.checkKotlinTargets() {
        val hasJsTargets =
            kotlinExtensionDelegate.targets.any { it.platformType == KotlinPlatformType.js }
        if (hasJsTargets) {
            project.logger.warn("JS targets are added, but UniFFI KMP bindings does not support JS targets yet.")
        }

        val hasWasmTargets =
            kotlinExtensionDelegate.targets.any { it.platformType == KotlinPlatformType.wasm }
        if (hasWasmTargets) {
            project.logger.warn("WASM targets are added, but UniFFI KMP bindings does not support WASM targets yet.")
        }

        val hasAndroidJvmTargets =
            kotlinExtensionDelegate.targets.any { it.platformType == KotlinPlatformType.androidJvm }
        if (hasAndroidJvmTargets && !::androidDelegate.isInitialized) {
            throw GradleException("Android JVM targets are added, but Android Gradle Plugin is not found.")
        }
    }

    private fun checkRequiredCrateTypes() {
        val requiredCrateTypes = cargoExtension
            .builds
            .flatMap { it.kotlinTargets }
            .map { it.platformType.requiredCrateType() }
            .distinct()
        val actualCrateTypes = cargoExtension.cargoPackage.get().libraryCrateTypes
        if (!actualCrateTypes.containsAll(requiredCrateTypes)) {
            throw GradleException(
                "Crate does not have required crate types. Required: $requiredCrateTypes, actual: $actualCrateTypes"
            )
        }
    }

    private fun readVariantsFromXcode() {
        val sdkName = System.getenv("SDK_NAME") ?: return
        val sdk = AppleSdk(sdkName)

        val configuration = System.getenv("CONFIGURATION") ?: return
        val variant = Variant(configuration)

        val archs = System.getenv("ARCHS")?.split(' ')?.map(AppleSdk.Companion::Arch) ?: return
        cargoExtension.nativeTargetVariantOverride.putAll(
            archs.mapNotNull(sdk::rustTarget).associateWith { variant })
    }

    private fun Project.configureBuildTasks() {
        val androidTarget = cargoExtension.builds.firstNotNullOfOrNull { build ->
            build.kotlinTargets.firstNotNullOfOrNull { it as? KotlinAndroidTarget }
        }
        val jvmTarget = cargoExtension.builds.firstNotNullOfOrNull { build ->
            build.kotlinTargets.firstOrNull {
                it is KotlinJvmTarget || it is KotlinWithJavaTarget<*, *>
            }
        }
        for (cargoBuild in cargoExtension.builds) {
            val rustUpTargetAddTask =
                tasks.register<RustUpTargetAddTask>({ +cargoBuild.rustTarget }) {
                    group = TASK_GROUP
                    this.rustTarget.set(cargoBuild.rustTarget)
                    this.rustVersion.set(cargoExtension.rustVersion)
                }
            for (cargoBuildVariant in cargoBuild.variants) {
                val projectLayout = layout
                cargoBuildVariant.buildTaskProvider.configure {
                    nativeStaticLibsDefFile.set(
                        projectLayout.outputCacheFile(
                            this,
                            "nativeStaticLibsDefFile"
                        )
                    )
                    dependsOn(rustUpTargetAddTask)
                    if (cargoBuildVariant is CargoAndroidBuildVariant) {
                        @OptIn(InternalGobleyGradleApi::class)
                        val environmentVariables = cargoBuildVariant.rustTarget.ndkEnvVariables(
                            sdkRoot = androidDelegate.androidSdkRoot,
                            apiLevel = androidDelegate.androidMinSdk,
                            ndkVersion = androidDelegate.androidNdkVersion,
                            ndkRoot = androidDelegate.androidNdkRoot,
                        )
                        additionalEnvironment.putAll(environmentVariables)
                    }
                }
                cargoBuildVariant.checkTaskProvider.configure {
                    dependsOn(rustUpTargetAddTask)
                    if (cargoBuildVariant is CargoAndroidBuildVariant) {
                        @OptIn(InternalGobleyGradleApi::class)
                        val environmentVariables = cargoBuildVariant.rustTarget.ndkEnvVariables(
                            sdkRoot = androidDelegate.androidSdkRoot,
                            apiLevel = androidDelegate.androidMinSdk,
                            ndkVersion = androidDelegate.androidNdkVersion,
                            ndkRoot = androidDelegate.androidNdkRoot,
                        )
                        additionalEnvironment.putAll(environmentVariables)
                    }
                }
            }
            for (kotlinTarget in cargoBuild.kotlinTargets) {
                when (kotlinTarget) {
                    is KotlinJvmTarget, is KotlinWithJavaTarget<*, *> -> {
                        cargoBuild as CargoJvmBuild<*>
                        cargoBuild.variants {
                            configureJvmPostBuildTasks(
                                kotlinTarget,
                                // cargoBuild.jvmVariant is checked inside
                                this,
                                androidTarget,
                            )
                        }
                    }

                    is KotlinAndroidTarget -> {
                        if (cargoBuild is CargoJvmBuild<*>) {
                            if (jvmTarget == null) {
                                cargoBuild.variants {
                                    configureJvmPostBuildTasks(
                                        kotlinTarget,
                                        // cargoBuild.jvmVariant is checked inside
                                        this,
                                        kotlinTarget,
                                    )
                                }
                            }
                        } else {
                            cargoBuild as CargoAndroidBuild
                            cargoBuild.dynamicLibrarySearchPaths.addAll(
                                @OptIn(InternalGobleyGradleApi::class)
                                cargoBuild.rustTarget.ndkLibraryDirectories(
                                    sdkRoot = androidDelegate.androidSdkRoot,
                                    apiLevel = androidDelegate.androidMinSdk,
                                    ndkVersion = androidDelegate.androidNdkVersion,
                                    ndkRoot = androidDelegate.androidNdkRoot,
                                ),
                            )
                            Variant.values().forEach {
                                configureAndroidPostBuildTasks(cargoBuild.variant(it))
                            }
                        }
                    }

                    is KotlinNativeTarget -> {
                        cargoBuild as CargoNativeBuild<*>
                        configureNativeCompilation(
                            kotlinTarget,
                            cargoBuild.variant(cargoBuild.nativeVariant.get())
                        )
                    }
                }
            }
        }
    }

    private fun Project.configureJvmPostBuildTasks(
        // kotlinTarget can be KotlinAndroidTarget when the JVM target is not present. This is for
        // Android local unit tests.
        kotlinTarget: KotlinTarget,
        cargoBuildVariant: CargoJvmBuildVariant<*>,
        androidTarget: KotlinAndroidTarget?,
    ) {
        val buildTask = cargoBuildVariant.buildTaskProvider
        val checkTask = cargoBuildVariant.checkTaskProvider
        val findDynamicLibrariesTask = cargoBuildVariant.findDynamicLibrariesTaskProvider
        cargoBuildVariant.dynamicLibrarySearchPaths.add(
            cargoBuildVariant.profile.zip(cargoExtension.cargoPackage) { profile, cargoPackage ->
                cargoPackage.outputDirectory(profile, cargoBuildVariant.rustTarget).asFile
            }
        )
        val projectLayout = layout
        findDynamicLibrariesTask.configure {
            libraryPathsCacheFile.set(projectLayout.outputCacheFile(this, "libraryPathsCacheFile"))
        }

        val libraryFiles = project.objects.listProperty<File>().apply {
            add(buildTask.flatMap { task ->
                task.libraryFileByCrateType.map { it[CrateType.SystemDynamicLibrary]!!.asFile }
            })
            addAll(findDynamicLibrariesTask.flatMap { it.libraryPaths })
        }

        val jarTask = tasks.register<Jar>({
            +"jvmRustRuntime"
            +cargoBuildVariant
        }) {
            group = TASK_GROUP
            from(libraryFiles)
            into(cargoBuildVariant.resourcePrefix)
            val variantSuffix = when (val variant = cargoBuildVariant.variant) {
                Variant.Debug -> "-$variant"
                else -> ""
            }
            @OptIn(InternalGobleyGradleApi::class)
            archiveClassifier.set(
                when (kotlinExtensionDelegate.pluginId) {
                    PluginIds.KOTLIN_JVM -> cargoBuildVariant.resourcePrefix.map { "$it$variantSuffix" }
                    PluginIds.KOTLIN_ANDROID -> cargoBuildVariant.resourcePrefix.map {
                        "android-local-$it$variantSuffix"
                    }

                    else -> cargoBuildVariant.resourcePrefix.map {
                        when {
                            kotlinTarget is KotlinAndroidTarget -> "android-local-$it$variantSuffix"
                            else -> "${kotlinTarget.name}-$it$variantSuffix"
                        }
                    }
                }
            )
            dependsOn(buildTask, findDynamicLibrariesTask)
        }

        @OptIn(InternalGobleyGradleApi::class)
        if (
            kotlinTarget !is KotlinAndroidTarget
            && cargoBuildVariant.embedRustLibrary.get()
            && cargoBuildVariant.variant == cargoBuildVariant.build.jvmVariant.get()
        ) {
            with(kotlinExtensionDelegate.sourceSets.jvmMain) {
                dependencies {
                    runtimeOnly(files(jarTask.flatMap { it.archiveFile }))
                }
            }
        }

        if (cargoBuildVariant.embedRustLibrary.get()) {
            tasks.named("check") {
                dependsOn(checkTask)
            }
        }

        @OptIn(InternalGobleyGradleApi::class)
        if (androidTarget != null && cargoBuildVariant.androidUnitTest.get()) {
            DependencyUtils.addAndroidUnitTestRuntimeRustLibraryJar(
                this,
                cargoBuildVariant.rustTarget,
                cargoBuildVariant.variant,
                jarTask,
            )
            with(kotlinExtensionDelegate.sourceSets.androidUnitTest(cargoBuildVariant.variant)) {
                dependencies {
                    runtimeOnly(files(jarTask.flatMap { it.archiveFile }))
                }
            }
            // This is for Android Compose Preview
            if (cargoBuildVariant.variant == Variant.Debug) {
                with(kotlinExtensionDelegate.sourceSets.androidMain(Variant.Debug)) {
                    dependencies {
                        runtimeOnly(files(jarTask.flatMap { it.archiveFile }))
                    }
                }
            }
        }
    }

    private fun Project.configureAndroidPostBuildTasks(cargoBuildVariant: CargoAndroidBuildVariant) {
        val buildTask = cargoBuildVariant.buildTaskProvider
        val checkTask = cargoBuildVariant.checkTaskProvider
        val findDynamicLibrariesTask = cargoBuildVariant.findDynamicLibrariesTaskProvider
        cargoBuildVariant.dynamicLibrarySearchPaths.add(
            cargoBuildVariant.profile.zip(cargoExtension.cargoPackage) { profile, cargoPackage ->
                cargoPackage.outputDirectory(profile, cargoBuildVariant.rustTarget).asFile
            }
        )
        val projectLayout = layout
        findDynamicLibrariesTask.configure {
            libraryPathsCacheFile.set(projectLayout.outputCacheFile(this, "libraryPathsCacheFile"))
        }

        @OptIn(InternalGobleyGradleApi::class)
        if (!cargoExtension.androidTargetsToBuild.get().contains(cargoBuildVariant.rustTarget))
            return

        if (!cargoBuildVariant.embedRustLibrary.get())
            return

        val copyDestination =
            layout.buildDirectory.dir("intermediates/rust/${cargoBuildVariant.rustTarget.rustTriple}/${cargoBuildVariant.variant}")

        val copyTask = tasks.register<Copy>({
            +"android"
            +cargoBuildVariant
        }) {
            group = TASK_GROUP
            from(
                buildTask.flatMap { task -> task.libraryFileByCrateType.map { it[CrateType.SystemDynamicLibrary]!! } },
                findDynamicLibrariesTask.flatMap { it.libraryPaths },
            )
            into(copyDestination.map { it.dir(cargoBuildVariant.rustTarget.androidAbiName) })
            dependsOn(buildTask, findDynamicLibrariesTask)
        }

        tasks.withType<Jar> {
            if (name.lowercase().contains("android")) {
                if (cargoBuildVariant.variant == variant!!) {
                    inputs.dir(copyDestination)
                    dependsOn(copyTask)
                }
            }
        }

        @OptIn(InternalGobleyGradleApi::class)
        androidDelegate.addMainJniDir(this, cargoBuildVariant.variant, copyTask, copyDestination)

        tasks.named("check") {
            dependsOn(checkTask)
        }
    }

    private fun Project.configureNativeCompilation(
        kotlinTarget: KotlinNativeTarget,
        cargoBuildVariant: CargoNativeBuildVariant<*>,
    ) {
        val buildTask = cargoBuildVariant.buildTaskProvider
        val checkTask = cargoBuildVariant.checkTaskProvider

        val buildOutputFile = buildTask
            .flatMap { it.libraryFileByCrateType }
            .map { it[CrateType.SystemStaticLibrary]!! }

        kotlinTarget.compilations.getByName("main") {
            cinterops.register("rust") {
                defFile(buildTask.flatMap { it.nativeStaticLibsDefFile })
                extraOpts(
                    "-libraryPath",
                    cargoExtension.cargoPackage.zip(cargoBuildVariant.profile) { cargoPackage, profile ->
                        cargoPackage.outputDirectory(profile, cargoBuildVariant.rustTarget)
                    }.get()
                )
                project.tasks.named(interopProcessingTaskName) {
                    inputs.file(buildOutputFile)
                    dependsOn(buildTask)
                }
            }
            compileTaskProvider.configure {
                compilerOptions.optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
            }
        }

        tasks.named("check") {
            dependsOn(checkTask)
        }
    }

    private fun Project.configureCleanTasks() {
        val cleanCrate = tasks.register<CargoCleanTask>("cargoClean") {
            group = TASK_GROUP
            cargoPackage.set(cargoExtension.cargoPackage)
        }

        tasks.named<Delete>("clean") {
            dependsOn(cleanCrate)
        }
    }
}

private fun KotlinPlatformType.requiredCrateType(): CrateType? = when (this) {
    // TODO: properly handle JS and WASM targets
    KotlinPlatformType.common -> null
    KotlinPlatformType.jvm -> CrateType.SystemDynamicLibrary
    KotlinPlatformType.js -> CrateType.SystemDynamicLibrary
    KotlinPlatformType.androidJvm -> CrateType.SystemDynamicLibrary
    KotlinPlatformType.native -> CrateType.SystemStaticLibrary
    KotlinPlatformType.wasm -> CrateType.SystemStaticLibrary
}

private fun ProjectLayout.outputCacheFile(task: Task, propertyName: String): Provider<RegularFile> {
    val trimmedPropertyName = propertyName
        .substringBeforeLast("File")
        .substringBeforeLast("Cache")
    return buildDirectory.file("taskOutputCache/${task.name}/$trimmedPropertyName")
}
