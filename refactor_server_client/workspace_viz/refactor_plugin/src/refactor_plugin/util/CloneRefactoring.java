package refactor_plugin.util;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

import refactor_plugin.model.CloneContext;
import refactor_plugin.model.CloneRecord;
import refactor_plugin.model.CloneRecord.CloneSource;
import refactor_plugin.model.CloneRecord.ExtractedMethod;
import refactor_plugin.model.CloneRecord.UpdatedFile;

/**
 * Applies a pre-computed "Extract Method" refactoring for a clone group.
 * Mirrors applyPrecomputedRefactoring() in extension.ts.
 * <p>
 * <strong>Not driven by drop coordinates:</strong> all edits come from JSON
 * {@code sources[].range}, {@code replacement_code}, and optional {@code extracted_method}.
 * The user never chooses the insert offset. That contrasts with Dropzone &rarr; editor, where
 * {@link refactor_plugin.listeners.EditorDropStartup} uses {@code resolveDropOffset} for
 * insert-at-drop + JDT, or &mdash; on the ExportQuarkus demo &mdash; optional Command Action 02
 * at existing clone lines when the dropped text matches those clones.
 * <p>
 * Key invariant (mirrors the VS Code implementation):
 *   All document offsets are computed from the PRISTINE document BEFORE any
 *   edits are made.  Edits are then applied in DESCENDING offset order so that
 *   no single edit shifts the offsets needed by later (lower-offset) edits.
 *
 *   This fixes two bugs in the old per-source approach:
 *   1. The extracted method was inserted once per clone source → appeared twice.
 *   2. After the first replacement shrank the document, subsequent line-number
 *      lookups used stale JSON coordinates and landed in the wrong location.
 */
public class CloneRefactoring {

    private static final Pattern RANGE_PAT = Pattern.compile("^(\\d+)-(\\d+)$");

    /** Immutable pending document edit: replace [offset, offset+length) with text. */
    private record Edit(int offset, int length, String text) {}

    /**
     * Entry point.  Groups sources by file, opens each file once, computes all
     * edits from the pristine document, then applies them highest-offset-first.
     * Must be called on the UI thread.
     */
    public static void apply(org.eclipse.swt.widgets.Shell shell, CloneRecord record) {
        if (record.sources == null || record.sources.isEmpty()) { return; }

        // Group sources by resolved absolute file path so each file is opened once.
        Map<String, List<CloneSource>> byFile = new LinkedHashMap<>();
        for (CloneSource src : record.sources) {
            if (src.file == null
                    || src.replacement_code == null
                    || src.replacement_code.isBlank()) { continue; }
            String abs = CloneContext.get().resolvePath(src.file);
            byFile.computeIfAbsent(abs, k -> new ArrayList<>()).add(src);
        }

        // Which files should receive the extracted method definition?
        // The JSON's updated_files[].inserted_extracted_method field tells us;
        // if that field is absent/empty fall back to inserting in every file.
        Set<String> insertMethodIn = new HashSet<>();
        if (record.updated_files != null) {
            for (UpdatedFile uf : record.updated_files) {
                if (uf.inserted_extracted_method && uf.file != null) {
                    insertMethodIn.add(CloneContext.get().resolvePath(uf.file));
                }
            }
        }

        for (Map.Entry<String, List<CloneSource>> entry : byFile.entrySet()) {
            String absFile  = entry.getKey();
            boolean doInsert = insertMethodIn.isEmpty()
                               || insertMethodIn.contains(absFile);
            applyToFile(shell, absFile, entry.getValue(),
                        doInsert ? record.extracted_method : null);
        }
    }

    // ── apply all edits for one file ──────────────────────────────────────────

    private static void applyToFile(org.eclipse.swt.widgets.Shell shell, String absFile,
                                     List<CloneSource> sources,
                                     ExtractedMethod em) {
        try {
            URI fileUri = new java.io.File(absFile).toURI();
            IFileStore    fileStore = EFS.getLocalFileSystem().getStore(fileUri);
            IWorkbenchPage page     = PlatformUI.getWorkbench()
                                                .getActiveWorkbenchWindow()
                                                .getActivePage();
            IEditorPart editor = IDE.openEditorOnFileStore(page, fileStore);
            if (!(editor instanceof ITextEditor te)) { return; }

            IDocument doc = te.getDocumentProvider().getDocument(te.getEditorInput());
            if (doc == null) { return; }

            // ── Step 1: compute ALL offsets from the PRISTINE document ────────
            List<Edit> edits = new ArrayList<>();

            for (CloneSource src : sources) {
                Edit e = buildRangeEdit(doc, src);
                if (e != null) { edits.add(e); }
            }

            // Insert extracted method exactly once, anchored to the enclosing
            // function that ends at the HIGHEST line number in this file.
            if (em != null && em.code != null && !em.code.isBlank()) {
                CloneSource anchor = sources.stream()
                    .filter(s -> s.enclosing_function != null
                                 && s.enclosing_function.fun_range != null)
                    .max(Comparator.comparingInt(
                            s -> parseEnd(s.enclosing_function.fun_range)))
                    .orElse(null);
                if (anchor != null) {
                    Edit e = buildInsertEdit(doc, anchor, em);
                    if (e != null) { edits.add(e); }
                }
            }

            // ── Step 2: apply edits DESCENDING by offset ─────────────────────
            // An edit at a higher offset cannot shift the bytes at a lower offset,
            // so later (lower-offset) edits still use correct pristine coordinates.
            edits.sort(Comparator.comparingInt(Edit::offset).reversed());
            for (Edit e : edits) {
                doc.replace(e.offset(), e.length(), e.text());
            }

        } catch (Exception e) {
            MessageDialog.openError(shell, "Clone Refactoring Error",
                    "Error applying refactoring for " + absFile
                    + ":\n" + e.getMessage());
        }
    }

    // ── build edit: clone range → replacement_code ────────────────────────────

    private static Edit buildRangeEdit(IDocument doc, CloneSource src)
            throws org.eclipse.jface.text.BadLocationException {
        int[] r = parseRangeInts(src.range);
        if (r == null) { return null; }
        int start0 = r[0] - 1;
        int end0   = r[1] - 1;
        if (start0 < 0 || end0 >= doc.getNumberOfLines() || start0 > end0) { return null; }

        int startOff = doc.getLineOffset(start0);
        // include all characters on end0 line except its trailing newline
        String lastLine = doc.get(doc.getLineOffset(end0), doc.getLineLength(end0))
                            .replaceAll("\\r?\\n$", "");
        int endOff = doc.getLineOffset(end0) + lastLine.length();

        return new Edit(startOff, endOff - startOff, src.replacement_code);
    }

    // ── build edit: insert extracted method after enclosing function ──────────

    private static Edit buildInsertEdit(IDocument doc, CloneSource anchor,
                                         ExtractedMethod em)
            throws org.eclipse.jface.text.BadLocationException {
        int[] r = parseRangeInts(anchor.enclosing_function.fun_range);
        if (r == null) { return null; }
        int encEnd0 = r[1] - 1;
        if (encEnd0 < 0 || encEnd0 >= doc.getNumberOfLines()) { return null; }

        String closingLine = doc.get(doc.getLineOffset(encEnd0),
                                     doc.getLineLength(encEnd0))
                               .replaceAll("\\r?\\n$", "");

        // Match the member-level indentation from the closing-brace line
        Matcher im = Pattern.compile("^(\\s*)").matcher(closingLine);
        String memberIndent = im.find() ? im.group(1) : "    ";

        // Re-indent each line of the extracted method body
        String[] codeLines = em.code.replace("\r\n", "\n").replace("\r", "\n")
                                    .split("\n", -1);
        StringBuilder sb = new StringBuilder("\n\n");
        for (String line : codeLines) {
            sb.append(line.trim().isEmpty() ? "" : memberIndent + line).append("\n");
        }
        // Remove the trailing newline — the closing brace line already ends with one
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '\n') {
            sb.deleteCharAt(sb.length() - 1);
        }

        int insertOff = doc.getLineOffset(encEnd0) + closingLine.length();
        return new Edit(insertOff, 0, sb.toString());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static int[] parseRangeInts(String range) {
        if (range == null) { return null; }
        Matcher m = RANGE_PAT.matcher(range.trim());
        if (!m.matches()) { return null; }
        return new int[]{ Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) };
    }

    private static int parseEnd(String range) {
        int[] r = parseRangeInts(range);
        return r != null ? r[1] : 0;
    }
}
