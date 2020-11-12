package com.cognifide.gradle.aem.common.instance

import com.cognifide.gradle.aem.AemExtension
import org.gradle.api.provider.Property

/**
 * System service related options.
 */
class ServiceOptions(private val aem: AemExtension) {

    val user = aem.obj.string {
        convention("aem")
        aem.prop.string("localInstance.service.user")?.let { set(it) }
    }

    val group = aem.obj.string {
        convention("aem")
        aem.prop.string("localInstance.service.group")?.let { set(it) }
    }

    /**
     * Controls number of file descriptors allowed.
     */
    val limitNoFile = aem.obj.int {
        convention(20000)
        aem.prop.int("localInstance.service.limitNoFile")?.let { set(it) }
    }

    val startCommand = aem.obj.string {
        convention("sh gradlew -i --console=plain instanceUp")
        aem.prop.string("localInstance.service.startCommand")?.let { set(it) }
    }

    val stopCommand = aem.obj.string {
        convention("sh gradlew -i --console=plain instanceDown")
        aem.prop.string("localInstance.service.stopCommand")?.let { set(it) }
    }

    val statusCommand = aem.obj.string {
        convention("sh gradlew -q --console=plain instanceStatus")
        aem.prop.string("localInstance.service.statusCommand")?.let { set(it) }
    }

    val profileCommand = aem.obj.string {
        convention(". /etc/profile")
        aem.prop.string("localInstance.service.profileCommand")?.let { set(it) }
    }

    private fun combineProfileCommand(command: Property<String>) = profileCommand.map { profileValue ->
        val commandValue = command.orNull
        mutableListOf<String>().apply {
            if (!profileValue.isNullOrBlank()) add(profileValue)
            if (!commandValue.isNullOrBlank()) add(commandValue)
        }.joinToString(" && ").ifBlank { null }
    }

    val opts get() = mapOf(
            "user" to user.orNull,
            "group" to group.orNull,
            "limitNoFile" to limitNoFile.orNull,
            "startCommand" to combineProfileCommand(startCommand),
            "stopCommand" to combineProfileCommand(stopCommand),
            "statusCommand" to combineProfileCommand(statusCommand)
    )
}
