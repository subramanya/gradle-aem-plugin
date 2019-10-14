package com.cognifide.gradle.aem.environment.docker

import com.cognifide.gradle.aem.common.utils.Patterns

class ContainerManager(private val docker: Docker) {

    private val aem = docker.environment.aem

    val defined = mutableListOf<Container>()

    /**
     * Define container.
     */
    fun define(name: String, definition: Container.() -> Unit) {
        defined.add(Container(docker, name).apply(definition))
    }

    /**
     * Shorthand for defining container by string invocation.
     */
    operator fun String.invoke(definition: Container.() -> Unit) = define(this, definition)

    /**
     * Get defined container by name.
     */
    fun named(name: String): Container = defined.firstOrNull { it.name == name }
            ?: throw DockerException("Container named '$name' is not defined!")

    /**
     * Checks if all containers are running.
     */
    val running: Boolean get() = defined.all { it.running }

    /**
     * Allows to filter which containers will be reloaded.
     */
    var reloadFilter = aem.props.string("environment.docker.container.reloadFilter") ?: Patterns.WILDCARD

    fun resolve() {
        aem.progress {
            message = "Resolving container(s): ${defined.names}"
            aem.parallel.each(defined) { it.resolve() }
        }
    }

    fun up() {
        aem.progress {
            message = "Configuring container(s): ${defined.names}"
            aem.parallel.each(defined) { it.up() }
        }
    }

    fun reload() {
        val containers = defined.filter { Patterns.wildcard(it.name, reloadFilter) }

        aem.progress {
            message = "Reloading container(s): ${containers.names}"
            aem.parallel.each(containers) { it.reload() }
        }
    }
}
