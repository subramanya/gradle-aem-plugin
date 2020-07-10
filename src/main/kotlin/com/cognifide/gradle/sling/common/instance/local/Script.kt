package com.cognifide.gradle.sling.common.instance.local

import com.cognifide.gradle.sling.common.instance.LocalInstance
import com.cognifide.gradle.sling.common.instance.LocalInstanceException
import java.io.File
import org.buildobjects.process.ExternalProcessFailureException
import org.buildobjects.process.ProcBuilder
import org.buildobjects.process.ProcResult
import org.buildobjects.process.TimeoutException
import org.gradle.process.internal.streams.SafeStreams

@Suppress("SpreadOperator", "TooGenericExceptionCaught")
class Script(val instance: LocalInstance, val shellCommand: List<String>, val wrapper: File, val bin: File) {

    private val logger = instance.localManager.sling.logger

    val commandLine: List<String> get() = shellCommand + listOf(wrapper.absolutePath)

    val commandString: String get() = commandLine.joinToString(" ")

    val command: String get() = commandLine.first()

    val args: List<String> get() = commandLine.subList(1, commandLine.size)

    /**
     * @see <https://superuser.com/questions/198525/how-can-i-execute-a-windows-command-line-in-background>
     */
    fun executeVerbosely(options: ProcBuilder.() -> Unit = {}) {
        try {
            logger.info("Executing script '$commandString' at directory '${instance.dir}'")

            ProcBuilder(command, *args.toTypedArray())
                    .withWorkingDirectory(instance.dir)
                    .withExpectedExitStatuses(0)
                    .withInputStream(SafeStreams.emptyInput())
                    .withOutputStream(SafeStreams.systemOut())
                    .withErrorStream(SafeStreams.systemOut())
                    .apply(options)
                    .run()
        } catch (e: Exception) {
            throw handleException(e)
        }
    }

    fun executeQuietly(options: ProcBuilder.() -> Unit = {}): ProcResult = try {
        logger.debug("Executing script '$commandString' at directory '${instance.dir}'")

        ProcBuilder(command, *args.toTypedArray())
                .withWorkingDirectory(instance.dir)
                .ignoreExitStatus()
                .apply(options)
                .run()
    } catch (e: Exception) {
        throw handleException(e)
    }

    private fun handleException(e: Exception): LocalInstanceException {
        return when (e) {
            is ExternalProcessFailureException -> LocalInstanceException("Local instance script failure: $this! " +
                    "Error: '${e.stderr}', exit code: '${e.exitValue}'", e)
            is TimeoutException -> LocalInstanceException("Local instance script timeout: $this! Cause: '${e.message}'", e)
            else -> LocalInstanceException("Local instance script failure: $this! Cause: '${e.message}'", e)
        }
    }

    override fun toString(): String = "Script(command=$commandString)"
}
