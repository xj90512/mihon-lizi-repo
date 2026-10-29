import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

plugins {
    id("com.android.application") version "9.4.0"
}

android {
    namespace = "eu.kanade.tachiyomi.extension.zh.lizi"
    compileSdk = 36

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.zh.lizi"
        minSdk = 21
        targetSdk = 36

        // versionName 的 "1.6" 前缀会被 mihon 解析为 extensions-lib 版本
        // （ExtensionLoader.SUPPORTED_LIB_VERSIONS = [1.4, 1.6]），不要改。
        versionCode = 3
        versionName = "1.6.3"
    }

    signingConfigs {
        create("release") {
            val signingFile = System.getenv("LIZI_KEYSTORE")
            if (signingFile != null) {
                storeFile = file(signingFile)
                storePassword = System.getenv("LIZI_STORE_PASSWORD")
                keyAlias = System.getenv("LIZI_KEY_ALIAS") ?: "lizi"
                keyPassword = System.getenv("LIZI_KEY_PASSWORD") ?: System.getenv("LIZI_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (System.getenv("LIZI_KEYSTORE") != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            // 保持与 release 同包名，避免出现 .debug 后缀导致 mihon 识别成两个扩展
            applicationIdSuffix = ""
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/*.version",
            "kotlin/**",
            "DebugProbesKt.bin",
        )
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // 全部 compileOnly：运行时由 mihon 宿主提供（扩展 apk 里不打包这些库）
    compileOnly("com.github.mihonapp:extensions-lib:1.6.0-rc1")
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    compileOnly("io.reactivex:rxjava:1.3.8")
    // mihon 宿主自带 injekt（source-api 的 ConfigurableSource 用它取 Context）。
    // 注意：extensions-lib 里的 mihonx.* 是 compileOnly 桩，宿主并不提供实现，禁止使用。
    compileOnly("uy.kohesive.injekt:injekt-core:1.16.1")
}

// Tachimanga runs JVM classes. Avoid DEX conversion and new JVM opcodes.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-Xlambdas=class")
    }
}

/** Native JVM packaging follows the extension host format, without dex2jar. */
abstract class CreateExtensionJarTask : DefaultTask() {
    @get:InputFiles abstract val jars: ListProperty<RegularFile>
    @get:InputFiles abstract val dirs: ListProperty<Directory>
    @get:InputFile abstract val manifestFile: RegularFileProperty
    @get:InputFiles abstract val apkDir: DirectoryProperty
    @get:OutputFile abstract val outputJar: RegularFileProperty

    @TaskAction fun create() {
        val apk = apkDir.get().asFile.walkTopDown().single { it.extension == "apk" }
        val output = outputJar.get().asFile
        output.parentFile.mkdirs()
        val written = hashSetOf<String>()
        JarOutputStream(output.outputStream().buffered()).use { out ->
            fun put(name: String, data: ByteArray) {
                if (!written.add(name)) return
                out.putNextEntry(ZipEntry(name).apply { time = 0L })
                out.write(data)
                out.closeEntry()
            }
            put("AndroidManifest.xml", manifestFile.get().asFile.readBytes())
            for (dir in dirs.get()) {
                val root = dir.asFile
                root.walkTopDown().filter { it.isFile && it.extension == "class" }.sortedBy { it.path }.forEach {
                    put(it.relativeTo(root).invariantSeparatorsPath, it.readBytes())
                }
            }
            for (jar in jars.get()) JarFile(jar.asFile).use { src ->
                src.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") }.sortedBy { it.name }.forEach {
                    put(it.name, src.getInputStream(it).use { stream -> stream.readBytes() })
                }
            }
            JarFile(apk).use { src ->
                src.entries().asSequence().filter {
                    !it.isDirectory && (it.name.startsWith("res/") || it.name == "resources.arsc")
                }.sortedBy { it.name }.forEach {
                    put(it.name, src.getInputStream(it).use { stream -> stream.readBytes() })
                }
            }
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val task = tasks.register<CreateExtensionJarTask>("createReleaseExtensionJar") {
            manifestFile.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            apkDir.set(variant.artifacts.get(SingleArtifact.APK))
            outputJar.set(layout.buildDirectory.file("outputs/jar/tachiyomi-zh.lizi-v${android.defaultConfig.versionName}.jar"))
        }
        variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT).use(task)
            .toGet(ScopedArtifact.CLASSES, CreateExtensionJarTask::jars, CreateExtensionJarTask::dirs)
    }
}
