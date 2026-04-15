package refactor_plugin.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Process-wide singleton holding state shared between {@code CloneGraphView},
 * DropzoneView, and EditorDropStartup — mirrors the two shared Maps in
 * the VS Code extension's activate() function.
 *
 * <p>Relative paths in JSON are resolved against {@link #workspaceRoot}. That root should
 * match the directory the JSON author used (often the parent of a {@code systems/} folder);
 * use {@link #workspaceRootForCloneJson(String)} when loading from disk.
 */
public class CloneContext {

    /**
     * Best-effort root directory for resolving {@code sources[].file} entries, given the
     * absolute path to {@code all_refactor_results.json}.
     */
    public static String workspaceRootForCloneJson(String jsonAbsolutePath) {
        java.io.File jf = new java.io.File(jsonAbsolutePath);
        java.io.File dir = jf.getParentFile();
        if (dir == null) {
            return jf.getAbsolutePath();
        }
        String leaf = dir.getName();
        if ("systems".equals(leaf) || "clone_data".equals(leaf)
                || "project_target".equals(leaf) || "systems_bck".equals(leaf)) {
            java.io.File parent = dir.getParentFile();
            return parent != null ? parent.getAbsolutePath() : dir.getAbsolutePath();
        }
        return dir.getAbsolutePath();
    }

    private static final CloneContext INSTANCE = new CloneContext();

    /** classid → CloneRecord; populated when the JSON file is loaded. */
    public final Map<String, CloneRecord> recordMap = new HashMap<>();

    /**
     * Absolute file path → classid of the clone group the file was opened from.
     * Set by CloneGraphView when the user selects a clone instance (single-click),
     * opens a source (double-click / menu), or uses “Set focus to clone group”.
     */
    public final Map<String, String> lastOpenedByFile = new HashMap<>();

    /**
     * Absolute path to the workspace root (runtime-refactor_plugin/).
     * Used to resolve relative file paths stored in all_refactor_results.json.
     * Set when JSON is loaded (CloneGraphView toolbar / startup autoload / EditorDropStartup).
     */
    public String workspaceRoot = "";

    /**
     * Optional focus from {@link view.CloneGraphView}: project/package name, then optional
     * Java class simple name, then optional clone {@code classid}. Any argument may be
     * {@code null} to clear that level; all {@code null} clears focus entirely.
     */
    public String graphFocusPackage;
    public String graphFocusClass;
    public String graphFocusClassid;

    private CloneContext() {
    }

    public static CloneContext get() {
        return INSTANCE;
    }

    /** Updates {@link #graphFocusPackage}, {@link #graphFocusClass}, {@link #graphFocusClassid}. */
    public void setGraphFocus(String packageName, String className, String classid) {
        graphFocusPackage = packageName;
        graphFocusClass = className;
        graphFocusClassid = classid;
    }

    /**
     * Resolves a file path from the JSON.
     * If the path is already absolute and exists, returns it as-is.
     * Otherwise resolves it relative to workspaceRoot.
     */
    public String resolvePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return filePath;
        }
        String fp = normalizeJsonFilePath(filePath);
        java.io.File f = new java.io.File(fp);
        if (f.isAbsolute() && f.exists()) {
            return fp;
        }
        return workspaceRoot.isEmpty() ? fp
                : new java.io.File(workspaceRoot, fp).getAbsolutePath();
    }

    /**
     * Whether the editor’s absolute path refers to the same Java compilation unit as a
     * {@code sources[].file} entry from JSON. Handles duplicate project layouts (e.g.
     * {@code systems/camel-java/org/...} vs {@code project_target01/src/main/java/org/...})
     * by comparing canonical {@code org/...} (or {@code com/...}) tails.
     */
    public boolean pathsEqualForCloneData(String editorAbsPath, String jsonRelativeFile) {
        if (editorAbsPath == null || editorAbsPath.isBlank()
                || jsonRelativeFile == null || jsonRelativeFile.isBlank()) {
            return false;
        }
        String norm = editorAbsPath.replace('\\', '/');
        String fp = normalizeJsonFilePath(jsonRelativeFile).replace('\\', '/');
        String resolved = resolvePath(jsonRelativeFile).replace('\\', '/');
        if (norm.equals(resolved)) {
            return true;
        }
        if (norm.endsWith("/" + fp)) {
            return true;
        }
        String keyE = canonicalJavaSourceKey(norm);
        String keyR = canonicalJavaSourceKey(resolved);
        return keyE != null && keyE.equals(keyR);
    }

    /**
     * Stable key for matching the same logical Java file across different on-disk trees
     * (Maven {@code src/main/java}, flat {@code systems/.../org}, etc.).
     */
    public static String canonicalJavaSourceKey(String normalizedAbsPath) {
        if (normalizedAbsPath == null) {
            return null;
        }
        String n = normalizedAbsPath.replace('\\', '/');
        String[] markers = { "/src/main/java/", "/src/test/java/" };
        for (String m : markers) {
            int i = n.indexOf(m);
            if (i >= 0) {
                return n.substring(i + m.length());
            }
        }
        int org = n.indexOf("/org/");
        if (org >= 0) {
            return n.substring(org + 1);
        }
        int com = n.indexOf("/com/");
        if (com >= 0) {
            return n.substring(com + 1);
        }
        return null;
    }

    /** Normalizes known bad relative paths in clone JSON (same idea as the VS Code side). */
    public static String normalizeJsonFilePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return filePath;
        }
        String n = filePath.replace('\\', '/');
        if (n.contains("camel-javaorg/")) {
            n = n.replace("camel-javaorg/", "camel-java/org/");
        }
        return n;
    }
}
