package refactor_plugin.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Process-wide singleton holding state shared between the CloneTreeView,
 * DropzoneView, and EditorDropStartup — mirrors the two shared Maps in
 * the VS Code extension's activate() function.
 */
public class CloneContext {

    private static final CloneContext INSTANCE = new CloneContext();

    /** classid → CloneRecord; populated when the JSON file is loaded. */
    public final Map<String, CloneRecord> recordMap = new HashMap<>();

    /**
     * Absolute file path → classid of the clone group the file was opened from.
     * Set by CloneTreeView when the user double-clicks a source node.
     */
    public final Map<String, String> lastOpenedByFile = new HashMap<>();

    /**
     * Absolute path to the workspace root (runtime-refactor_plugin/).
     * Used to resolve relative file paths stored in all_refactor_results.json.
     * Set by CloneTreeView when it loads the JSON.
     */
    public String workspaceRoot = "";

    private CloneContext() {}

    public static CloneContext get() { return INSTANCE; }

    /**
     * Resolves a file path from the JSON.
     * If the path is already absolute and exists, returns it as-is.
     * Otherwise resolves it relative to workspaceRoot.
     */
    public String resolvePath(String filePath) {
        if (filePath == null || filePath.isBlank()) { return filePath; }
        java.io.File f = new java.io.File(filePath);
        if (f.isAbsolute() && f.exists()) { return filePath; }
        return workspaceRoot.isEmpty() ? filePath
                : new java.io.File(workspaceRoot, filePath).getAbsolutePath();
    }
}
