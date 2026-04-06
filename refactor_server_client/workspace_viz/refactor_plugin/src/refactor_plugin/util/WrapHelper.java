package refactor_plugin.util;

/**
 * Wraps a dropped code snippet inside a method/function definition.
 * Mirrors the wrapInMethod() + normalizeBodyLines() helpers in extension.ts.
 * <p>
 * <strong>How this differs from Command Action 02 (EM) and {@link CloneRefactoring}:</strong>
 * the Dropzone payload is often the <em>same</em> source text as the clone region EM works on,
 * but the user may drop it at <em>any</em> offset. {@code WrapHelper} inserts a <em>new</em>
 * enclosing method at that drop offset and re-indents the body &mdash; it does not perform
 * JDT Extract Method and does not update other clone sites. For Java, the editor listener
 * normally tries insert-at-drop + JDT extract first; this class is the fallback when JDT is
 * unavailable or the language is not Java.
 */
public class WrapHelper {

    /**
     * @param body        the snippet text to wrap
     * @param methodName  identifier for the generated method
     * @param lang        language id: "java", "python", "typescript", etc.
     * @param outerIndent leading whitespace of the drop-target line
     */
    public static String wrapInMethod(String body, String methodName,
                                      String lang, String outerIndent) {
        // Normalise CRLF / CR line endings to LF before processing.
        // Eclipse editors on Windows (or cross-platform file copies) embed \r
        // in the selection text; without this, split("\n") leaves a trailing \r
        // on every line, which the editor renders as an extra blank line.
        body = body.replace("\r\n", "\n").replace("\r", "\n");

        String step       = "    ";
        String bodyIndent = outerIndent + step;
        String[] bodyLines = normalizeBodyLines(body, bodyIndent);
        String   joined    = String.join("\n", bodyLines);

        if ("python".equals(lang)) {
            return outerIndent + "def " + methodName + "():\n" + joined;
        }

        String header = "java".equals(lang)
                ? "private void " + methodName + "()"
                : "function " + methodName + "()";   // JS / TS / fallback

        return outerIndent + header + " {\n" + joined + "\n" + outerIndent + "}";
    }

    /**
     * Strips the minimum common leading whitespace from all non-empty lines,
     * then re-indents each line with {@code bodyIndent}.
     */
    private static String[] normalizeBodyLines(String body, String bodyIndent) {
        String[] lines = body.split("\n", -1);

        // Drop trailing blank lines
        int end = lines.length;
        while (end > 0 && lines[end - 1].trim().isEmpty()) { end--; }

        // Find minimum indent across non-empty lines
        int minIndent = Integer.MAX_VALUE;
        for (int i = 0; i < end; i++) {
            String l = lines[i];
            if (!l.trim().isEmpty()) {
                int k = 0;
                while (k < l.length() && (l.charAt(k) == ' ' || l.charAt(k) == '\t')) { k++; }
                minIndent = Math.min(minIndent, k);
            }
        }
        if (minIndent == Integer.MAX_VALUE) { minIndent = 0; }

        String[] result = new String[end];
        for (int i = 0; i < end; i++) {
            String l = lines[i];
            result[i] = l.trim().isEmpty() ? "" : bodyIndent + l.substring(Math.min(minIndent, l.length()));
        }
        return result;
    }
}
