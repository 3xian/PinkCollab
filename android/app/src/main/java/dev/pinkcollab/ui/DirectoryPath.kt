package dev.pinkcollab.ui

import java.util.Locale

internal data class DirectoryCrumb(
    val label: String,
    val path: String,
    val current: Boolean,
)

internal fun hostUsesWindowsPaths(os: String): Boolean = os.equals("windows", ignoreCase = true)
internal fun browserTitle(hostName: String): String = hostName.trim().ifBlank { "Browse workspace" }

internal fun directoryLeaf(path: String): String {
    val trimmed = path.trim().trimEnd('/', '\\')
    if (trimmed.isEmpty()) return path.trim().ifBlank { "/" }
    val leaf = trimmed.substringAfterLast('/').substringAfterLast('\\')
    return leaf.ifBlank { trimmed }
}

/** Labels for the workspace root and each directory inside it. The paths are display identity, not navigation targets. */
internal fun directoryCrumbs(path: String, roots: List<String>, windows: Boolean): List<DirectoryCrumb> {
    val root = longestWorkspaceRoot(path, roots, windows)
    if (root == null) {
        return listOf(DirectoryCrumb(directoryLeaf(path).ifBlank { path }, path, current = true))
    }
    val segments = relativeSegments(path, root, windows).orEmpty()
    val separator = directorySeparator(path)
    return buildList {
        add(DirectoryCrumb(directoryLeaf(root).ifBlank { root.trim() }, root, current = segments.isEmpty()))
        segments.forEachIndexed { index, segment ->
            add(
                DirectoryCrumb(
                    label = segment,
                    path = appendRelative(root, segments.subList(0, index + 1), separator),
                    current = index == segments.lastIndex,
                ),
            )
        }
    }
}

/**
 * Server path a crumb may open.
 * The immediate parent uses [parentPath], the same value as system back.
 * A deeper tag opens a path only when a listing already returned it. A null parent is the allowlist boundary, so no ancestor opens.
 */
internal fun crumbTarget(
    crumbs: List<DirectoryCrumb>,
    index: Int,
    parentPath: String?,
    knownPaths: List<String>,
    windows: Boolean,
): String? {
    val crumb = crumbs.getOrNull(index) ?: return null
    if (crumb.current) return null
    if (index == crumbs.lastIndex - 1) return parentPath
    if (parentPath == null) return null
    return knownPaths.firstOrNull { sameDirectory(it, crumb.path, windows) }
}

internal fun noteServerPath(known: List<String>, path: String, windows: Boolean): List<String> {
    if (path.isBlank()) return known
    val index = known.indexOfFirst { sameDirectory(it, path, windows) }
    if (index < 0) return known + path
    if (known[index] == path) return known
    return known.toMutableList().also { it[index] = path }
}

/**
 * Parent for in-browser back.
 * A loaded listing parent is authoritative, including null at the allowlist boundary.
 * Before that listing arrives, only a parent still inside a known workspace root is offered.
 */
internal fun browserParentTarget(
    path: String,
    listingReady: Boolean,
    listingParent: String?,
    roots: List<String>,
    windows: Boolean,
): String? {
    if (listingReady) {
        val parent = listingParent?.takeIf { it.isNotBlank() } ?: return null
        return parent.takeUnless { sameDirectory(it, path, windows) }
    }
    return synthesizedParent(path, roots, windows)
}

private fun synthesizedParent(path: String, roots: List<String>, windows: Boolean): String? {
    val root = longestWorkspaceRoot(path, roots, windows) ?: return null
    if (sameDirectory(path, root, windows)) return null
    val segments = relativeSegments(path, root, windows) ?: return null
    if (segments.size <= 1) return root
    return appendRelative(root, segments.dropLast(1), directorySeparator(path))
}

private fun longestWorkspaceRoot(path: String, roots: List<String>, windows: Boolean): String? {
    val key = directoryKey(path, windows)
    return roots
        .filter { root ->
            val rootKey = directoryKey(root, windows)
            key == rootKey || key.startsWith("$rootKey/")
        }
        .maxByOrNull { directoryKey(it, windows).length }
}

private fun relativeSegments(path: String, root: String, windows: Boolean): List<String>? {
    val key = directoryKey(path, windows)
    val rootKey = directoryKey(root, windows)
    if (key != rootKey && !key.startsWith("$rootKey/")) return null
    if (key == rootKey) return emptyList()
    val display = directoryKey(path, windows = false)
    return display.drop(rootKey.length).trimStart('/').split('/').filter { it.isNotEmpty() }
}

private fun appendRelative(root: String, segments: List<String>, separator: Char): String {
    val relative = segments.joinToString(separator.toString())
    val base = root.trimEnd('/', '\\')
    val combined = if (base.isEmpty()) "$separator$relative" else "$base$separator$relative"
    return if (separator == '\\') combined.replace('/', '\\') else combined.replace('\\', '/')
}

private fun directorySeparator(path: String): Char = if (path.contains('\\')) '\\' else '/'

internal fun sameDirectory(left: String, right: String, windows: Boolean): Boolean =
    directoryKey(left, windows) == directoryKey(right, windows)

private fun directoryKey(path: String, windows: Boolean): String {
    var value = path.trim().replace('\\', '/')
    val unc = value.startsWith("//")
    value = if (unc) "//" + value.drop(2).replace(Regex("/+"), "/") else value.replace(Regex("/+"), "/")
    if (value.length > 1 && value.endsWith('/')) value = value.dropLast(1)
    return if (windows) value.lowercase(Locale.ROOT) else value
}
