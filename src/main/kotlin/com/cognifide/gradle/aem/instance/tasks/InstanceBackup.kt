package com.cognifide.gradle.aem.instance.tasks

import com.cognifide.gradle.aem.AemDefaultTask
import com.cognifide.gradle.aem.AemException
import com.cognifide.gradle.aem.common.instance.InstanceException
import com.cognifide.gradle.common.utils.Formats
import java.io.File
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

open class InstanceBackup : AemDefaultTask() {

    private val manager get() = aem.localInstanceManager.backup

    /**
     * Determines what need to be done (backup zipped and uploaded or something else).
     */
    @Internal
    var mode: Mode = Mode.of(aem.prop.string("instance.backup.mode")
            ?: Mode.ZIP_AND_UPLOAD.name)

    @TaskAction
    fun backup() {
        when (mode) {
            Mode.ZIP_ONLY -> zip()
            Mode.ZIP_AND_UPLOAD -> {
                val zip = zip()
                upload(zip, false)
            }
            Mode.UPLOAD_ONLY -> {
                val zip = manager.local ?: throw InstanceException("No instance backup to upload!")
                upload(zip, true)
            }
        }
    }

    private fun zip(): File {
        val file = manager.create(aem.localInstances)
        common.notifier.lifecycle("Instance(s) backed up", "File: ${file.name}, Size: ${Formats.fileSize(file)}")
        return file
    }

    private fun upload(file: File, verbose: Boolean) {
        val uploaded = manager.upload(file, verbose)
        if (uploaded) {
            common.notifier.lifecycle("Instance backup uploaded", "File: ${file.name}")
        }
    }

    init {
        description = "Turns off local instance(s), archives to ZIP file, then turns on again."
    }

    enum class Mode {
        ZIP_ONLY,
        ZIP_AND_UPLOAD,
        UPLOAD_ONLY;

        companion object {
            fun of(name: String) = values().find { it.name.equals(name, true) }
                    ?: throw AemException("Unsupported instance backup mode: $name")
        }
    }

    companion object {
        const val NAME = "instanceBackup"
    }
}
