package io.github.springai.harness.storage;

import java.util.List;
import java.util.Set;

/**
 * Storage constants defining forbidden, internal, and ignored directories/paths.
 *
 * @author ichaobuster
 */
public final class StorageConstants {

    private StorageConstants() {
        // Private constructor to prevent instantiation
    }

    public static final String TRASH_DIR = ".trash";
    public static final String SNAPSHOTS_DIR = ".snapshots";
    public static final String SHADOW_DIR = ".shadow";
    public static final String STORAGE_META = ".storage";

    public static final List<String> IGNORED_PATH_PATTERN = List.of(
            "/.git/", "/node_modules/", "/target/", "/build/",
            "/.idea/", "/.vscode/", "/dist/", "/__pycache__/",
            "/" + TRASH_DIR + "/", "/" + SNAPSHOTS_DIR + "/", "/" + SHADOW_DIR + "/"
    );

    public static final List<String> INTERNAL_PATH_PATTERN = List.of(
            "/" + TRASH_DIR + "/", "/" + SNAPSHOTS_DIR + "/", "/" + SHADOW_DIR + "/"
    );

    public static final List<String> FORBIDDEN_PREFIXES = List.of(
            TRASH_DIR + "/", SNAPSHOTS_DIR + "/", SHADOW_DIR + "/"
    );

    public static final Set<String> FORBIDDEN_EXACT_PATHS = Set.of(
            TRASH_DIR, SNAPSHOTS_DIR, SHADOW_DIR, STORAGE_META
    );
}
