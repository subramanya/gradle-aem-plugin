package com.cognifide.gradle.sling.common.pkg.vault

import com.cognifide.gradle.sling.common.pkg.PackageException

enum class CndSyncType {
    ALWAYS,
    PRESERVE,
    NEVER;

    companion object {

        fun find(name: String) = values().firstOrNull { it.name.equals(name, true) }

        fun of(name: String) = find(name)
                ?: throw PackageException("Unsupported CND file sync mode '$name'!")
    }
}
