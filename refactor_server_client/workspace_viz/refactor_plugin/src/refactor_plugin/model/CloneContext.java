package refactor_plugin.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Process-wide singleton holding state shared between the CloneTreeView,
 * DropzoneView, and EditorDropStartup — mirrors the two shared Maps in
 * the VS Code extension's activate() function.
 *
 * <p>Default filesystem layout: JSON and trees under {@code runtime-refactor_plugin/systems/};
 * see {@link #DEFAULT_CLONE_JSON} and {@link #workspaceRootForCloneJson(String)}.
 */
public class CloneContext {

    /** Parent of {@code systems/}; JSON {@code file} paths are relative to this root. */
    public static final String RUNTIME_REFACTOR_PLUGIN_ROOT =
            "/Users/dreamxia/2025_Dr.Song/ManageCodeClones_IDEPlugins"
                    + "/refactor_server_client/runtime-refactor_plugin";

    public static final String SYSTEMS_DIR = RUNTIME_REFACTOR_PLUGIN_ROOT + "/systems";

    public static final String DEFAULT_CLONE_JSON =
            SYSTEMS_DIR + "/all_refactor_results.json";

    /**
     * {@link #workspaceRoot} for a JSON file under {@code systems/}, legacy dirs, or flat layout.
     */
    public static String workspaceRootForCloneJson(String jsonPath) {
        java.io.File jf = new java.io.File(jsonPath);
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
     * Set by CloneTreeView when the user double-clicks a source node.
     */
    public final Map<String, String> lastOpenedByFile = new HashMap<>();

    /** UI focus from CloneGraphView to steer Dropzone/plan routing. */
    public volatile String preferredProject = null;
    public volatile String preferredClassName = null;
    public volatile String preferredClassId = null;

    /**
     * Absolute path to {@code runtime-refactor_plugin/} (parent of {@code systems/}).
     * JSON {@code file} entries are relative to this root (e.g. {@code systems/.../Foo.java}).
     */
    public String workspaceRoot = "";

    private CloneContext() {}

    public static CloneContext get() { return INSTANCE; }

    public void setGraphFocus(String project, String className, String classId) {
        preferredProject = project;
        preferredClassName = className;
        preferredClassId = classId;
    }

    /**
     * Fixes known bad relative paths in clone JSON (e.g. missing slash in
     * {@code camel-javaorg/} so it resolves beside the real {@code camel-java/org/} tree).
     */
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

    /**
     * Resolves a file path from the JSON.
     * If the path is already absolute and exists, returns it as-is.
     * Otherwise resolves it relative to workspaceRoot.
     */
    public String resolvePath(String filePath) {
        if (filePath == null || filePath.isBlank()) { return filePath; }
        String fp = normalizeJsonFilePath(filePath);
        java.io.File f = new java.io.File(fp);
        if (f.isAbsolute() && f.exists()) { return fp; }
        return workspaceRoot.isEmpty() ? fp
                : new java.io.File(workspaceRoot, fp).getAbsolutePath();
    }
}
