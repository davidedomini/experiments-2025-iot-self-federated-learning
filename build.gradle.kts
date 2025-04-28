import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.configurationcache.extensions.capitalized
import org.jetbrains.kotlin.util.collectionUtils.listOfNonEmptyScopes
import java.awt.GraphicsEnvironment
import java.io.ByteArrayOutputStream
import java.nio.file.Files

plugins {
    application
    scala
    alias(libs.plugins.gitSemVer)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.qa)
    alias(libs.plugins.multiJvmTesting)
    alias(libs.plugins.taskTree)
}

repositories {
    mavenCentral()
}

val usesJvm: Int = File(File(projectDir, "docker/sim"), "Dockerfile")
    .readLines()
    .first { it.isNotBlank() }
    .let {
        Regex("FROM\\s+eclipse-temurin:(\\d+)\\s*$").find(it)?.groups?.get(1)?.value
            ?: throw IllegalStateException("Cannot read information on the JVM to use.")
    }
    .toInt()

multiJvm {
    jvmVersionForCompilation.set(usesJvm)
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation(libs.bundles.alchemist)
    implementation(libs.scalapy)
    implementation(libs.scala.csv)
    if (!GraphicsEnvironment.isHeadless()) {
        implementation("it.unibo.alchemist:alchemist-swingui:${libs.versions.alchemist.get()}")
    }
}

develocity {
    buildScan {
        termsOfUseUrl = "https://gradle.com/terms-of-service"
        termsOfUseAgree = "yes"
        publishing.onlyIf { it.buildResult.failures.isNotEmpty() }
    }
}

// Heap size estimation for batches
val maxHeap: Long? by project
val heap: Long = maxHeap ?: if (System.getProperty("os.name").lowercase().contains("linux")) {
    ByteArrayOutputStream().use { output ->
        exec {
            executable = "bash"
            args = listOf("-c", "cat /proc/meminfo | grep MemAvailable | grep -o '[0-9]*'")
            standardOutput = output
        }
        output.toString().trim().toLong() / 1024
    }.also { println("Detected ${it}MB RAM available.") } * 9 / 10
} else {
    // Guess 16GB RAM of which 2 used by the OS
    14 * 1024L
}
val taskSizeFromProject: Int? by project
val taskSize = taskSizeFromProject ?: 512
val threadCount = maxOf(1, minOf(Runtime.getRuntime().availableProcessors(), heap.toInt() / taskSize))

val alchemistGroup = "Run Alchemist"
/*
 * This task is used to run all experiments in sequence
 */
val runAllGraphic by tasks.register<DefaultTask>("runAllGraphic") {
    group = alchemistGroup
    description = "Launches all simulations with the graphic subsystem enabled"
}
val runAllBatch by tasks.register<DefaultTask>("runAllBatch") {
    group = alchemistGroup
    description = "Launches all experiments"
}

val pythonVirtualEnvName = "env"

val createVirtualEnv by tasks.register<Exec>("createVirtualEnv") {
    group = alchemistGroup
    description = "Creates a virtual environment for Python"
    commandLine("python3", "-m", "venv", pythonVirtualEnvName)
}

val createPyTorchNetworkFolder by tasks.register<Exec>("createPyTorchNetworkFolder") {
    group = alchemistGroup
    description = "Creates a folder for PyTorch networks"
    commandLine("mkdir", "-p", "networks")
}

val createPyTorchNetworkBaselineFolder by tasks.register<Exec>("createPyTorchNetworkBaselineFolder") {
    group = alchemistGroup
    description = "Creates a folder for PyTorch networks"
    commandLine("mkdir", "-p", "networks-baseline")
}

val createDataFolderForTest by tasks.register<Exec>("createDataFolderForTest") {
    group = alchemistGroup
    description = "Creates a folder for data"
    commandLine("mkdir", "-p", "data-test")
}

val createDataFolderForTestBaseline by tasks.register<Exec>("createDataFolderForTestBaseline") {
    group = alchemistGroup
    description = "Creates a folder for data"
    commandLine("mkdir", "-p", "data-test-baseline")
}

val installPythonDependencies by tasks.register<Exec>("installPythonDependencies") {
    group = alchemistGroup
    description = "Installs Python dependencies"
    dependsOn(createVirtualEnv, createPyTorchNetworkFolder, createPyTorchNetworkBaselineFolder, createDataFolderForTest, createDataFolderForTestBaseline)
    when (Os.isFamily(Os.FAMILY_WINDOWS)) {
        true -> commandLine("$pythonVirtualEnvName\\Scripts\\pip", "install", "-r", "requirements.txt")
        false -> commandLine("$pythonVirtualEnvName/bin/pip", "install", "-r", "requirements.txt")
    }
}

val buildCustomDependency by tasks.register<Exec>("buildCustomDependency") {
    group = alchemistGroup
    description = "Builds custom Python dependencies"
    dependsOn(installPythonDependencies)
    workingDir("python")
    when (Os.isFamily(Os.FAMILY_WINDOWS)) {
        true -> commandLine("$pythonVirtualEnvName\\Scripts\\python", "setup.py", "sdist", "bdist_wheel")
        false -> commandLine("../$pythonVirtualEnvName/bin/python", "setup.py", "sdist", "bdist_wheel")
    }
}

val installCustomDependency by tasks.register<Exec>("installCustomDependency") {
    group = alchemistGroup
    description = "Installs custom Python dependencies"
    dependsOn(buildCustomDependency)
    when (Os.isFamily(Os.FAMILY_WINDOWS)) {
        true -> commandLine("$pythonVirtualEnvName\\Scripts\\pip", "install", "-e", "python")
        false -> commandLine("$pythonVirtualEnvName/bin/pip", "install", "-e", "python")
    }
}

/*
 * Scan the folder with the simulation files, and create a task for each one of them.
 */
File(rootProject.rootDir.path + "/src/main/yaml").listFiles()
    ?.filter { it.extension == "yml" }
    ?.sortedBy { it.nameWithoutExtension }
    ?.forEach {
        fun basetask(name: String, additionalConfiguration: JavaExec.() -> Unit = {}) = tasks.register<JavaExec>(name) {
            group = alchemistGroup
            description = "Launches graphic simulation ${it.nameWithoutExtension}"
            mainClass.set("it.unibo.alchemist.Alchemist")
            classpath = sourceSets["main"].runtimeClasspath
            args("run", it.absolutePath)
            jvmArgs(
                when (Os.isFamily(Os.FAMILY_WINDOWS)) {
                    true -> "-Dscalapy.python.programname=$pythonVirtualEnvName\\Scripts\\python"
                    false -> "-Dscalapy.python.programname=$pythonVirtualEnvName/bin/python"
                },
                "-Xmx${1024}m",
                "-Dscalapy.python.library=/home/dom/.pyenv/versions/3.10.2/lib/libpython3.10.so"
            )
            javaLauncher.set(
                javaToolchains.launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(usesJvm))
                },
            )
            if (System.getenv("CI") == "true") {
                args("--override", "terminate: { type: AfterTime, parameters: [2] } ")
            } else {
                this.additionalConfiguration()
            }
            dependsOn(installCustomDependency)
        }
        val capitalizedName = it.nameWithoutExtension.capitalized()
        val graphic by basetask("run${capitalizedName}Graphic") {
            val monitor = if (capitalizedName.lowercase().contains("baseline")) "Centralized" else "Distributed"
            val defaultParameters =
                if (capitalizedName.lowercase().contains("baseline"))
                    "[0.0, 0, 0, false]"
                else
                    "[0.0, 0, 0, 0, false, 0.0]"
            args(
                "--override",
                """
                   monitors: 
                        - type: SwingGUI
                          parameters: { graphics: effects/${it.nameWithoutExtension}.json }
                        - type: it.unibo.alchemist.model.monitors.${monitor}TestSetEvaluation
                          parameters: $defaultParameters
                """.trimIndent(),
                "--override",
                "launcher: { parameters: { batch: [], autoStart: false } }",
            )
        }
        runAllGraphic.dependsOn(graphic)
        val batch by basetask("run${capitalizedName}Batch") {
            description = "Launches batch experiments for $capitalizedName"
            maxHeapSize = "${minOf(heap.toInt(), Runtime.getRuntime().availableProcessors() * taskSize)}m"
            File("data").mkdirs()
            val batch = if(capitalizedName.contains("Baseline")) {
                "seed, areas"
            } else if (capitalizedName.contains("Movement")) {
                "seed"
            } else {
                "seed, areas, lossThreshold"
            }
            args("--override",
                """
                launcher: {
                    parameters: {
                        batch: [ $batch ],
                        showProgress: true,
                        autoStart: true,
                        parallelism: 1,
                    }
                }
            """.trimIndent())
        }
        runAllBatch.dependsOn(batch)
    }

