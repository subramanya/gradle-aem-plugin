package com.cognifide.gradle.aem.pkg.resolver

import aQute.bnd.osgi.Jar
import com.cognifide.gradle.aem.base.BaseExtension
import com.cognifide.gradle.aem.base.vlt.VltFilter
import com.cognifide.gradle.aem.instance.Bundle
import com.cognifide.gradle.aem.internal.file.FileOperations
import com.cognifide.gradle.aem.internal.file.resolver.FileResolution
import com.cognifide.gradle.aem.pkg.PackageFileFilter
import com.cognifide.gradle.aem.pkg.PackagePlugin
import java.io.File
import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.gradle.util.GFileUtils
import org.zeroturnaround.zip.ZipUtil

class PackageResolution(group: PackageGroup, id: String, action: (FileResolution) -> File) : FileResolution(group, id, action) {

    private val resolver = group.resolver

    private val aem = BaseExtension.of(resolver.project)

    override fun process(file: File): File {
        val origin = super.process(file)

        return when (FilenameUtils.getExtension(file.name)) {
            "jar" -> wrap(origin)
            "zip" -> origin
            else -> throw PackageException("File $origin must have *.jar or *.zip extension")
        }
    }

    private fun wrap(jar: File): File {
        val pkgName = jar.nameWithoutExtension
        val pkg = File(dir, "$pkgName.zip")
        if (pkg.exists()) {
            aem.logger.info("CRX package wrapping OSGi bundle already exists: $pkg")
            return pkg
        }

        aem.logger.info("Wrapping OSGi bundle to CRX package: $jar")

        val pkgRoot = File(dir, pkgName)
        val pkgPath = "${resolver.bundlePath}/${jar.name}"
        val vaultDir = File(pkgRoot, PackagePlugin.VLT_PATH)

        // Copy package template files
        GFileUtils.mkdirs(vaultDir)
        FileOperations.copyResources(PackagePlugin.VLT_PATH, vaultDir)

        // Expand package properties
        val bundle = Jar(jar)
        val description = bundle.manifest.mainAttributes.getValue(Bundle.ATTRIBUTE_DESCRIPTION) ?: ""
        val symbolicName = bundle.manifest.mainAttributes.getValue(Bundle.ATTRIBUTE_SYMBOLIC_NAME)
        val group = symbolicName.substringBeforeLast(".")
        val version = bundle.manifest.mainAttributes.getValue(Bundle.ATTRIBUTE_VERSION)
        val filters = listOf(VltFilter.rootElementForPath(pkgPath))
        val bundleProps = PackageFileFilter.FILE_PROPERTIES + mapOf<String, Any>(
                "compose.vaultName" to symbolicName,
                "compose.vaultGroup" to group,
                "compose.vaultFilters" to filters,
                "project.group" to group,
                "project.name" to symbolicName,
                "project.version" to version,
                "project.description" to description
        )
        val overrideProps = resolver.bundleProperties(bundle)
        val effectiveProps = bundleProps + overrideProps

        FileOperations.amendFiles(vaultDir, listOf("**/${PackagePlugin.VLT_PATH}/*.xml")) { file, content ->
            aem.props.expand(content, effectiveProps, file.absolutePath)
        }

        // Copy bundle to install path
        val pkgJar = File(pkgRoot, "jcr_root$pkgPath")

        GFileUtils.mkdirs(pkgJar.parentFile)
        FileUtils.copyFile(jar, pkgJar)

        // ZIP all to CRX package
        ZipUtil.pack(pkgRoot, pkg)
        pkgRoot.deleteRecursively()

        return pkg
    }
}