package refactor_plugin.util;

import refactor_plugin.model.CloneContext;

/**
 * Resolves whether an editor's absolute path refers to the same Java source as a
 * {@code sources[].file} entry in clone JSON, across different folder layouts
 * ({@code org/apache/...} vs {@code systems/src/org/apache/...} vs
 * {@code .../src/main/java/org/apache/...}).
 */
public final class ClonePathMatch {

    private ClonePathMatch() {}

    /**
     * Normalises a path to a comparable key: content after {@code src/main/java/}, or
     * after the first {@code /org/} or {@code /com/} segment (typical Java package roots).
     */
    public static String canonicalJavaPathKey(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String n = path.replace('\\', '/');
        if (n.startsWith("org/") || n.startsWith("com/")) {
            return n;
        }
        int m = n.indexOf("/src/main/java/");
        if (m >= 0) {
            return n.substring(m + "/src/main/java/".length());
        }
        m = n.indexOf("/org/");
        if (m >= 0) {
            return n.substring(m + 1);
        }
        m = n.indexOf("/com/");
        if (m >= 0) {
            return n.substring(m + 1);
        }
        return null;
    }

    /**
     * True if {@code editorAbsPath} is the same file as described by {@code jsonFile}
     * (relative or absolute path from JSON).
     */
    public static boolean editorMatchesSourceFile(String editorAbsPath, String jsonFile,
            CloneContext ctx) {
        if (editorAbsPath == null || jsonFile == null) {
            return false;
        }
        String fileNorm = editorAbsPath.replace('\\', '/');
        String srcNorm = CloneContext.normalizeJsonFilePath(jsonFile).replace('\\', '/');
        String resolved = ctx.resolvePath(jsonFile).replace('\\', '/');

        if (resolved.equals(fileNorm)) {
            return true;
        }
        if (fileNorm.endsWith("/" + srcNorm)) {
            return true;
        }

        String keyEd = canonicalJavaPathKey(fileNorm);
        String keyRs = canonicalJavaPathKey(resolved);
        String keySn = canonicalJavaPathKey(srcNorm);
        if (keyEd != null) {
            if (keyRs != null && keyEd.equals(keyRs)) {
                return true;
            }
            if (keySn != null && keyEd.equals(keySn)) {
                return true;
            }
        }
        return false;
    }
}
