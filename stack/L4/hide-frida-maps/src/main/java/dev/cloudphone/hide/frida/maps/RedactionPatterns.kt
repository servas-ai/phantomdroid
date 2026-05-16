package dev.cloudphone.hide.frida.maps

/**
 * Patterns matched against individual lines from /proc/<pid>/maps.
 * A line is elided (dropped from output) if it contains ANY of these substrings.
 *
 * Source: mutations/proposals/019e2f10-37cb-7c8b-bbfb-90e573cfe302.json §mutation.redaction_patterns
 * These are the canonical patterns for this mutation proposal.
 */
object RedactionPatterns {

    /**
     * Substrings to search for in each line of /proc/<pid>/maps or /proc/<pid>/smaps.
     * Case-sensitive. A match on ANY token causes the line to be dropped.
     */
    val ELIDE_TOKENS: Array<String> = arrayOf(
        "frida-",
        "frida-agent-",
        "frida-gadget-",
        "gadget",
        "gum-js-loop",
        "gmain",
        "linjector",
        "libfrida"
    )

    /**
     * Path patterns (as prefix or infix) for /proc files that expose address-space layout.
     * Only paths matching one of these are intercepted.
     */
    val MAPS_PATH_PATTERNS: Array<Regex> = arrayOf(
        Regex("/proc/\\d+/maps"),
        Regex("/proc/\\d+/smaps"),
        Regex("/proc/\\d+/maps_files"),
        Regex("/proc/self/maps"),
        Regex("/proc/self/smaps")
    )

    /** Returns true when the path refers to a maps-family pseudo-file. */
    fun isMapsPseudoFile(path: String): Boolean =
        MAPS_PATH_PATTERNS.any { it.containsMatchIn(path) }

    /** Returns true when the maps line should be removed from output. */
    fun shouldElide(line: String): Boolean =
        ELIDE_TOKENS.any { line.contains(it, ignoreCase = false) }
}
