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
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.widgets.Shell;
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
 *
 * <p><b>Parallel to {@code README_multi_0408.md}</b> / {@link refactor_plugin.handlers.ExtractMethodWorkflow}
 * when live LTK is unavailable: the README’s rebinding and relocation are <em>materialized</em>
 * in JSON ({@code replacement_code}, {@code extracted_method}). This class preserves
 * <strong>bottom-up</strong> application (descending offsets) and {@link ICompilationUnit#reconcile}
 * after text edits. See README “Applying this guide to drag-and-drop” for the full mapping.
 *
 * <p><b>Concept alignment:</b>
 *
 * <ul>
 *   <li><b>Bottom-up / line-shift:</b> same README idea — resolve ranges from one pristine
 *       document; apply replacement edits in <b>descending offset</b> order
 *       ({@code edits.sort(...reversed())}). Intra-editor drag reverts first so JSON
 *       coordinates stay valid.</li>
 *   <li><b>Java model:</b> {@link ICompilationUnit#reconcile} after edits when the editor
 *       maps to a workspace CU (like after each LTK/AST step in the handler).</li>
 *   <li><b>AST rebind / move:</b> Precomputed in JSON for this path.</li>
 * </ul>
 *
 * <p>Key invariant (mirrors the VS Code implementation):
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

    /** Same surviving name as {@link refactor_plugin.handlers.CloneRecordLiveExtract} live path. */
    private static final String PRIMARY_LTK_METHOD_NAME = "extractedM1Block1";

    /** Immutable pending document edit: replace [offset, offset+length) with text. */
    private record Edit(int offset, int length, String text) {}

    /**
     * Entry point.  Groups sources by file, opens each file once, computes all
     * edits from the pristine document, then applies them highest-offset-first.
     * Must be called on the UI thread.
     */
    public static void apply(Shell shell, CloneRecord record) {
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
                        doInsert ? record.extracted_method : null, record);
        }
    }

    // ── apply all edits for one file ──────────────────────────────────────────

    private static void applyToFile(Shell shell, String absFile,
                                     List<CloneSource> sources,
                                     ExtractedMethod em, CloneRecord record) {
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

            // Match ExtractMethodWorkflow: process later source lines before earlier ones
            // when planning (final application order is still by descending offset).
            sources.sort(Comparator.comparingInt(CloneRefactoring::rangeStartLine).reversed());

            // ── Step 1: compute ALL offsets from the PRISTINE document ────────
            List<Edit> edits = new ArrayList<>();

            for (CloneSource src : sources) {
                Edit e = buildRangeEdit(doc, src, record);
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
                    Edit e = buildInsertEdit(doc, anchor, em, record);
                    if (e != null) { edits.add(e); }
                }
            }

            // ── Step 2: apply edits DESCENDING by offset (bottom-up in file) ──
            // An edit at a higher offset cannot shift the bytes at a lower offset,
            // so later (lower-offset) edits still use correct pristine coordinates.
            edits.sort(Comparator.comparingInt(Edit::offset).reversed());
            for (Edit e : edits) {
                doc.replace(e.offset(), e.length(), e.text());
            }

            reconcileJavaModelIfPossible(te);

        } catch (Exception e) {
            MessageDialog.openError(shell, "Clone Refactoring Error",
                    "Error applying refactoring for " + absFile
                    + ":\n" + e.getMessage());
        }
    }

    /**
     * JSON precomputes {@code extracted} (or {@code extracted_method.method_name}); live LTK
     * uses {@code extractedM1Block1}. Rewrite identifiers so text fallback matches.
     */
    private static String replaceJsonMethodNameWithPrimaryLtk(String text, CloneRecord record) {
        if (text == null || record == null) {
            return text;
        }
        String oldName = jsonDeclaredExtractName(record);
        if (PRIMARY_LTK_METHOD_NAME.equals(oldName)) {
            return text;
        }
        return text.replaceAll("\\b" + Pattern.quote(oldName) + "\\b", PRIMARY_LTK_METHOD_NAME);
    }

    private static String jsonDeclaredExtractName(CloneRecord record) {
        if (record.extracted_method != null
                && record.extracted_method.method_name != null
                && !record.extracted_method.method_name.isBlank()) {
            return record.extracted_method.method_name.trim();
        }
        return "extracted";
    }

    // ── build edit: clone range → replacement_code ────────────────────────────

    private static Edit buildRangeEdit(IDocument doc, CloneSource src, CloneRecord record)
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

        String replacement = replaceJsonMethodNameWithPrimaryLtk(src.replacement_code, record);
        return new Edit(startOff, endOff - startOff, replacement);
    }

    // ── build edit: insert extracted method after enclosing function ──────────

    private static Edit buildInsertEdit(IDocument doc, CloneSource anchor,
                                         ExtractedMethod em, CloneRecord record)
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

        String body = replaceJsonMethodNameWithPrimaryLtk(em.code, record);
        // Re-indent each line of the extracted method body
        String[] codeLines = body.replace("\r\n", "\n").replace("\r", "\n")
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

    /**
     * Keeps JDT in sync after text edits when the editor is a workspace Java file
     * (cf. {@code ICompilationUnit.reconcile} after each step in {@link refactor_plugin.handlers.ExtractMethodWorkflow}).
     */
    private static void reconcileJavaModelIfPossible(ITextEditor editor) {
        try {
            IJavaElement je = JavaUI.getEditorInputJavaElement(editor.getEditorInput());
            if (je instanceof ICompilationUnit icu) {
                icu.reconcile(ICompilationUnit.NO_AST, false, null,
                        new NullProgressMonitor());
            }
        } catch (Exception ignored) {
            // Non-workspace / non-Java: skip
        }
    }

    /** 1-based start line from {@link CloneSource#range}, or 0 if unparseable. */
    private static int rangeStartLine(CloneSource src) {
        int[] r = parseRangeInts(src.range);
        return r != null ? r[0] : 0;
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
