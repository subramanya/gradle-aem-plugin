package com.cognifide.gradle.aem.common

import com.cognifide.gradle.aem.base.*
import com.cognifide.gradle.aem.base.vlt.VltException
import com.cognifide.gradle.aem.base.vlt.VltFilter
import com.cognifide.gradle.aem.bundle.BundleJar
import com.cognifide.gradle.aem.bundle.BundlePlugin
import com.cognifide.gradle.aem.common.file.FileOperations
import com.cognifide.gradle.aem.common.http.HttpClient
import com.cognifide.gradle.aem.instance.*
import com.cognifide.gradle.aem.pkg.PackagePlugin
import com.cognifide.gradle.aem.pkg.tasks.Compose
import com.fasterxml.jackson.annotation.JsonIgnore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.bundling.Jar

open class AemExtension(@Internal val project: Project) {

    @Internal
    val logger = project.logger

    /**
     * Allows to read project property specified in command line and system property as a fallback.
     */
    @Internal
    val props = PropertyParser(this)

    /**
     * Project name convention prefixes used to determine default:
     *
     * - bundle install subdirectory
     * - CRX package base name
     * - OSGi bundle JAR base name
     *
     * in case of multi-project build and assembly packages.
     */
    @get:Internal
    @get:JsonIgnore
    val projectPrefixes: List<String> = listOf("aem.", "aem-", "aem_")

    /**
     * Project name with skipped convention prefixes.
     */
    @get:Internal
    @get:JsonIgnore
    val projectName: String
        get() = project.name.run {
            var n = this; projectPrefixes.forEach { n = n.removePrefix(it) }; n
        }

    /**
     * Base name used as default for CRX packages being created by compose or collect task
     * and also for OSGi bundle JARs.
     */
    @get:Internal
    @get:JsonIgnore
    val baseName: String
        get() = Formats.normalizeSeparators(if (project == project.rootProject) {
            project.rootProject.name
        } else {
            "${project.rootProject.name}.$projectName"
        }, ".")

    /**
     * Determines current environment to be used in e.g package deployment.
     */
    @Input
    val environment: String = props.string("aem.env") ?: run { System.getenv("AEM_ENV") ?: "local" }

    /**
     * Toggles parallel CRX package deployments and instance synchronization.
     */
    @Internal
    val parallel = props.boolean("aem.parallel") ?: true

    /**
     * Collection of common AEM configuration properties like instance definitions. Contains default values for tasks.
     */
    @Nested
    val config = BaseConfig(this)

    /**
     * Provides API for displaying interactive notification during running build tasks.
     */
    @Internal
    val notifier = NotifierFacade.of(this)

    /**
     * Provides API for easier creation of tasks (e.g in sequence) in the matter of Gradle task configuration avoidance.
     */
    @Internal
    val tasks = TaskFactory(project)

    /**
     * Provides API for performing actions affecting multiple instances at once.
     */
    @Internal
    val actions = ActionPerformer(this)

    private val bundleMap = mutableMapOf<String, BundleJar>()

    /**
     * Contains OSGi bundle configuration used in case of composing CRX package.
     */
    @Nested
    val bundles: Map<String, BundleJar> = bundleMap

    /**
     * Collection of all java packages from all projects applying bundle plugin.
     */
    @get:Internal
    val javaPackages: List<String>
        get() = project.allprojects.filter {
            it.plugins.hasPlugin(BundlePlugin.ID)
        }.flatMap { subproject ->
            AemExtension.of(subproject).bundles.values.mapNotNull { it.javaPackage }
        }

    @get:Internal
    val instances: List<Instance>
        get() = instanceFilter()

    fun instances(consumer: (Instance) -> Unit) = parallelWith(instances, consumer)

    fun instances(filter: String, consumer: (Instance) -> Unit) = parallelWith(instanceFilter(filter), consumer)

    fun instance(urlOrName: String): Instance {
        return config.parseInstance(urlOrName)
    }

    @get:Internal
    val instanceAny: Instance
        get() {
            val cmdInstanceArg = props.string("aem.instance")
            if (!cmdInstanceArg.isNullOrBlank()) {
                return instance(cmdInstanceArg)
            }

            return instanceNamed(Instance.FILTER_ANY)
        }

    fun instanceNamed(desiredName: String? = props.string("aem.instance.name"), defaultName: String = "$environment-*"): Instance {
        val nameMatcher: String = desiredName ?: defaultName

        val namedInstance = instanceFilter(nameMatcher).firstOrNull()
        if (namedInstance != null) {
            return namedInstance
        }

        throw InstanceException("Instance named '$nameMatcher' is not defined.")
    }

    fun instanceFilter(nameMatcher: String = props.string("aem.instance.name") ?: "$environment-*"): List<Instance> {
        val all = config.instances.values

        // Specified by command line should not be filtered
        val cmd = all.filter { it.environment == Instance.ENVIRONMENT_CMD }
        if (cmd.isNotEmpty()) {
            return cmd
        }

        // Defined by build script, via properties or defaults are filterable by name
        return all.filter { instance ->
            when {
                props.flag("aem.instance.authors") -> {
                    Patterns.wildcard(instance.name, "$environment-${InstanceType.AUTHOR}*")
                }
                props.flag("aem.instance.publishers") -> {
                    Patterns.wildcard(instance.name, "$environment-${InstanceType.PUBLISH}*")
                }
                else -> Patterns.wildcard(instance.name, nameMatcher)
            }
        }
    }

    @get:Internal
    val instanceAuthors: List<Instance>
        get() = instanceFilter().filter { it.type == InstanceType.AUTHOR }

    fun instanceAuthors(consumer: (Instance) -> Unit) = parallelWith(instanceAuthors, consumer)

    @get:Internal
    val instancePublishers: List<Instance>
        get() = instanceFilter().filter { it.type == InstanceType.PUBLISH }

    fun instancePublishers(consumer: Instance.() -> Unit) = parallelWith(instancePublishers, consumer)

    @get:Internal
    val instanceLocals: List<LocalInstance>
        get() = instances.filterIsInstance(LocalInstance::class.java)

    fun instanceLocals(consumer: LocalInstance.() -> Unit) = parallelWith(instanceLocals, consumer)

    @get:Internal
    val instanceHandles: List<LocalHandle>
        get() = instanceLocals.map { LocalHandle(project, it) }

    fun instanceHandles(consumer: LocalHandle.() -> Unit) = parallelWith(instanceHandles, consumer)

    @get:Internal
    val instanceRemotes: List<RemoteInstance>
        get() = instances.filterIsInstance(RemoteInstance::class.java)

    fun instanceRemotes(consumer: RemoteInstance.() -> Unit) = parallelWith(instanceRemotes, consumer)

    fun packages(consumer: (File) -> Unit) = parallelWith(packages, consumer)

    @get:Internal
    val packages: List<File>
        get() = project.tasks.withType(Compose::class.java)
                .map { it.archivePath }

    fun packagesDependent(task: Task): List<File> {
        return task.taskDependencies.getDependencies(task)
                .filterIsInstance(Compose::class.java)
                .map { it.archivePath }
    }

    fun sync(synchronizer: InstanceSync.() -> Unit) = sync(instances, synchronizer)

    fun sync(instances: Collection<Instance>, synchronizer: InstanceSync.() -> Unit) {
        parallelWith(instances) { this.sync.apply(synchronizer) }
    }

    fun syncPackages(synchronizer: InstanceSync.(File) -> Unit) = syncPackages(instances, packages, synchronizer)

    fun syncPackages(
        instances: Collection<Instance>,
        packages: Collection<File>,
        synchronizer: InstanceSync.(File) -> Unit
    ) {
        packages.forEach { pkg -> // single AEM instance dislikes parallel package installation
            parallelWith(instances) { // but same package could be in parallel deployed on different AEM instances
                sync.apply { synchronizer(pkg) }
            }
        }
    }

    fun <T> http(consumer: HttpClient.() -> T) = HttpClient(project).run(consumer)

    fun config(configurer: BaseConfig.() -> Unit) {
        config.apply(configurer)
    }

    @get:Internal
    val compose: Compose
        get() = compose(Compose.NAME)

    fun compose(taskName: String) = project.tasks.getByName(taskName) as Compose

    @get:Internal
    val composes: List<Compose>
        get() = project.tasks.withType(Compose::class.java).toList()

    fun bundle(configurer: BundleJar.() -> Unit) = bundle(JavaPlugin.JAR_TASK_NAME, configurer)

    fun bundle(jarTaskName: String, configurer: BundleJar.() -> Unit) {
        project.tasks.withType(Jar::class.java)
                .named(jarTaskName)
                .configure { bundle(it, configurer) }
    }

    @get:Internal
    val bundle: BundleJar
        get() = bundle(JavaPlugin.JAR_TASK_NAME)

    fun bundle(jarTaskName: String) = bundle(project.tasks.getByName(jarTaskName) as Jar)

    fun bundle(jar: Jar, configurer: BundleJar.() -> Unit = {}): BundleJar {
        return bundleMap.getOrPut(jar.name) { BundleJar(this, jar) }.apply(configurer)
    }

    fun notifier(configurer: NotifierFacade.() -> Unit) {
        notifier.apply(configurer)
    }

    fun tasks(configurer: TaskFactory.() -> Unit) {
        tasks.apply(configurer)
    }

    fun retry(configurer: Retry.() -> Unit): Retry {
        return retry().apply(configurer)
    }

    fun retry(): Retry = Retry.none()

    fun <T> progress(options: ProgressIndicator.() -> Unit, action: ProgressIndicator.() -> T): T {
        return ProgressIndicator(project).apply(options).launch(action)
    }

    @get:Internal
    val filter: VltFilter
        get() {
            val cmdFilterRoots = props.list("aem.filter.roots") ?: listOf()
            if (cmdFilterRoots.isNotEmpty()) {
                logger.debug("Using Vault filter roots specified as command line property: $cmdFilterRoots")
                return VltFilter.temporary(project, cmdFilterRoots)
            }

            val cmdFilterPath = props.string("aem.filter.path") ?: ""
            if (cmdFilterPath.isNotEmpty()) {
                val cmdFilter = FileOperations.find(project, config.packageVltRoot, cmdFilterPath)
                        ?: throw VltException("Vault check out filter file does not exist at path: $cmdFilterPath" +
                                " (or under directory: ${config.packageVltRoot}).")
                logger.debug("Using Vault filter file specified as command line property: $cmdFilterPath")
                return VltFilter(cmdFilter)
            }

            val conventionFilterFiles = listOf(
                    "${config.packageVltRoot}/${VltFilter.CHECKOUT_NAME}",
                    "${config.packageVltRoot}/${VltFilter.BUILD_NAME}"
            )
            val conventionFilterFile = FileOperations.find(project, config.packageVltRoot, conventionFilterFiles)
            if (conventionFilterFile != null) {
                logger.debug("Using Vault filter file found by convention: $conventionFilterFile")
                return VltFilter(conventionFilterFile)
            }

            logger.debug("None of Vault filter files found by CMD properties or convention.")

            return VltFilter.temporary(project, listOf())
        }

    fun filter(file: File) = VltFilter(file)

    fun filter(path: String) = filter(project.file(path))

    fun <A, B : Any> parallelMap(iterable: Iterable<A>, mapper: (A) -> B): Collection<B> {
        return parallelMap(iterable, { true }, mapper)
    }

    fun <A, B : Any> parallelMap(iterable: Iterable<A>, filter: (A) -> Boolean, mapper: (A) -> B): List<B> {
        if (!parallel) {
            return iterable.filter(filter).map(mapper)
        }

        return runBlocking(Dispatchers.Default) {
            iterable.map { value -> async { value.takeIf(filter)?.let(mapper) } }.mapNotNull { it.await() }
        }
    }

    fun <A> parallelWith(iterable: Iterable<A>, callback: A.() -> Unit) {
        if (!parallel) {
            return iterable.forEach { it.apply(callback) }
        }

        return runBlocking(Dispatchers.Default) {
            iterable.map { value -> async { value.apply(callback) } }.forEach { it.await() }
        }
    }

    fun temporaryDir(task: Task) = temporaryDir(task.name)

    fun temporaryDir(name: String) = AemTask.temporaryDir(project, name)

    init {
        project.gradle.projectsEvaluated { _ ->
            if (project.plugins.hasPlugin(BundlePlugin.ID)) {
                bundle // forces default jar to be configured
            }
            bundles.values.forEach { it.projectsEvaluated() }
        }
    }

    companion object {

        const val NAME = "aem"

        private val PLUGIN_IDS = listOf(PackagePlugin.ID, BundlePlugin.ID, InstancePlugin.ID, BasePlugin.ID)

        fun of(project: Project): AemExtension {
            return project.extensions.findByType(AemExtension::class.java)
                    ?: throw AemException("${project.displayName.capitalize()} must have at least one of following plugins applied: $PLUGIN_IDS")
        }
    }
}