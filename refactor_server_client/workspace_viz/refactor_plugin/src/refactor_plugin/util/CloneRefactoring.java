package refactor_plugin.util;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

import refactor_plugin.handlers.CloneRecordLiveExtract;
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
     * Serializable JSON-derived text change (offsets from one pristine snapshot).
     * Built off the UI thread when {@link #applyRecordsJsonInParallel} computes edits in parallel.
     */
    public record CloneJsonEdit(int offset, int length, String text) {}

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

    /**
     * Reads current editor buffer when a matching editor is open, otherwise disk text,
     * for every absolute path touched by {@code records}. Call from the UI thread before
     * parallel JSON edit computation so offsets match what {@link #apply} would use.
     */
    public static Map<String, String> snapshotTextsForRecords(List<CloneRecord> records) {
        Set<String> paths = collectAbsolutePathsForJson(records);
        Map<String, String> out = new LinkedHashMap<>();
        for (String abs : paths) {
            out.put(abs, readFileTextForJsonSnapshot(abs));
        }
        return out;
    }

    /**
     * Computes JSON-derived edits for each record in parallel (worker threads only touch
     * the immutable {@code snapshot} strings and in-memory {@link Document}s), merges
     * edits per file, validates non-overlapping replacement ranges, then applies on the UI thread.
     */
    public static void applyRecordsJsonInParallel(Shell shell, List<CloneRecord> records,
            Map<String, String> snapshot) {
        if (records == null || records.isEmpty()) {
            return;
        }
        int poolSize = Math.min(records.size(),
                Math.max(2, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        try {
            List<Future<Map<String, List<CloneJsonEdit>>>> futures = new ArrayList<>();
            for (CloneRecord r : records) {
                futures.add(pool.submit(() -> computeJsonEditsForRecord(r, snapshot)));
            }
            Map<String, List<CloneJsonEdit>> merged = new LinkedHashMap<>();
            for (Future<Map<String, List<CloneJsonEdit>>> f : futures) {
                Map<String, List<CloneJsonEdit>> part = f.get();
                for (Map.Entry<String, List<CloneJsonEdit>> e : part.entrySet()) {
                    merged.merge(e.getKey(), e.getValue(), (a, b) -> {
                        List<CloneJsonEdit> c = new ArrayList<>(a);
                        c.addAll(b);
                        return c;
                    });
                }
            }
            for (Map.Entry<String, List<CloneJsonEdit>> e : merged.entrySet()) {
                if (!assertReplacementRangesNonOverlapping(shell, e.getKey(), e.getValue())) {
                    return;
                }
            }
            Runnable applyAll = () -> {
                for (Map.Entry<String, List<CloneJsonEdit>> e : merged.entrySet()) {
                    applyJsonEditsToOpenFile(shell, e.getKey(), e.getValue());
                }
            };
            if (Display.getCurrent() != null) {
                applyAll.run();
            } else {
                Display.getDefault().syncExec(applyAll);
            }
        } catch (Exception ex) {
            MessageDialog.openError(shell, "Clone Refactoring Error",
                    "Parallel JSON apply failed:\n"
                    + (ex.getMessage() != null ? ex.getMessage() : ex.toString()));
        } finally {
            pool.shutdown();
        }
    }

    private static Set<String> collectAbsolutePathsForJson(List<CloneRecord> records) {
        Set<String> paths = new LinkedHashSet<>();
        CloneContext ctx = CloneContext.get();
        for (CloneRecord r : records) {
            if (r == null || r.sources == null) {
                continue;
            }
            for (CloneSource s : r.sources) {
                if (s == null || s.file == null) {
                    continue;
                }
                if (s.replacement_code == null || s.replacement_code.isBlank()) {
                    continue;
                }
                paths.add(ctx.resolvePath(s.file));
            }
        }
        return paths;
    }

    private static String readFileTextForJsonSnapshot(String absPath) {
        if (absPath == null || absPath.isBlank()) {
            return "";
        }
        try {
            var win = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (win == null) {
                try {
                    return Files.readString(Paths.get(absPath));
                } catch (Exception e) {
                    return "";
                }
            }
            ITextEditor te = findAlreadyOpenTextEditorMatching(absPath);
            if (te != null) {
                IDocument d = te.getDocumentProvider().getDocument(te.getEditorInput());
                if (d != null) {
                    return d.get();
                }
            }
        } catch (Exception ignored) {
            /* fall through to disk */
        }
        try {
            return Files.readString(Paths.get(absPath));
        } catch (Exception e) {
            return "";
        }
    }

    private static Map<String, List<CloneJsonEdit>> computeJsonEditsForRecord(CloneRecord record,
            Map<String, String> snapshot) {
        Map<String, List<CloneJsonEdit>> out = new LinkedHashMap<>();
        if (record == null || record.sources == null || record.sources.isEmpty()) {
            return out;
        }
        Map<String, List<CloneSource>> byFile = new LinkedHashMap<>();
        for (CloneSource src : record.sources) {
            if (src.file == null
                    || src.replacement_code == null
                    || src.replacement_code.isBlank()) {
                continue;
            }
            String abs = CloneContext.get().resolvePath(src.file);
            byFile.computeIfAbsent(abs, k -> new ArrayList<>()).add(src);
        }
        Set<String> insertMethodIn = new HashSet<>();
        if (record.updated_files != null) {
            for (UpdatedFile uf : record.updated_files) {
                if (uf.inserted_extracted_method && uf.file != null) {
                    insertMethodIn.add(CloneContext.get().resolvePath(uf.file));
                }
            }
        }
        for (Map.Entry<String, List<CloneSource>> entry : byFile.entrySet()) {
            String absFile = entry.getKey();
            boolean doInsert = insertMethodIn.isEmpty() || insertMethodIn.contains(absFile);
            ExtractedMethod em = doInsert ? record.extracted_method : null;
            String text = snapshot.getOrDefault(absFile, "");
            Document doc = new Document(text);
            try {
                List<Edit> edits = buildEditList(doc, entry.getValue(), em, record);
                List<CloneJsonEdit> flat = new ArrayList<>();
                for (Edit e : edits) {
                    flat.add(new CloneJsonEdit(e.offset(), e.length(), e.text()));
                }
                out.put(absFile, flat);
            } catch (org.eclipse.jface.text.BadLocationException ignored) {
                /* skip file */
            }
        }
        return out;
    }

    private static boolean assertReplacementRangesNonOverlapping(Shell shell, String absFile,
            List<CloneJsonEdit> edits) {
        List<int[]> intervals = new ArrayList<>();
        for (CloneJsonEdit e : edits) {
            if (e.length() <= 0) {
                continue;
            }
            intervals.add(new int[]{ e.offset(), e.offset() + e.length() });
        }
        intervals.sort(Comparator.comparingInt(a -> a[0]));
        for (int i = 1; i < intervals.size(); i++) {
            int s1 = intervals.get(i - 1)[0];
            int e1 = intervals.get(i - 1)[1];
            int s2 = intervals.get(i)[0];
            int e2 = intervals.get(i)[1];
            if (s1 < e2 && s2 < e1) {
                MessageDialog.openError(shell, "Clone Refactoring Error",
                        "Overlapping JSON replacement ranges for file:\n" + absFile + "\n\n"
                        + "Two clone groups in one drop both edit the same region. "
                        + "Apply one group, then the other.");
                return false;
            }
        }
        return true;
    }

    private static void applyJsonEditsToOpenFile(Shell shell, String absFile,
            List<CloneJsonEdit> jsonEdits) {
        try {
            ITextEditor te = openTextEditorForAbsPath(shell, absFile);
            if (te == null) {
                return;
            }
            IDocument doc = te.getDocumentProvider().getDocument(te.getEditorInput());
            if (doc == null) {
                return;
            }
            List<Edit> edits = new ArrayList<>();
            for (CloneJsonEdit je : jsonEdits) {
                edits.add(new Edit(je.offset(), je.length(), je.text()));
            }
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
     * Avoids a second editor tab for the same logical Java file: {@code openEditorOnFileStore}
     * uses a different input than an already-open workspace Java editor, even when both show
     * the same {@code org/...} type (e.g. JSON path under {@code systems/…} vs project layout).
     */
    private static ITextEditor openTextEditorForAbsPath(Shell shell, String absFile) {
        if (absFile == null || absFile.isBlank()) {
            return null;
        }
        try {
            ITextEditor existing = findAlreadyOpenTextEditorMatching(absFile);
            if (existing != null) {
                return existing;
            }
            var win = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (win == null) {
                return null;
            }
            IWorkbenchPage page = win.getActivePage();
            if (page == null) {
                return null;
            }
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
            IPath location = org.eclipse.core.runtime.Path.fromOSString(
                    Paths.get(absFile).normalize().toString());
            IFile iFile = root.getFileForLocation(location);
            if (iFile != null && iFile.exists()) {
                IJavaElement je = JavaCore.create(iFile);
                if (je instanceof ICompilationUnit cu) {
                    try {
                        IEditorPart part = JavaUI.openInEditor(cu);
                        if (part instanceof ITextEditor te) {
                            return te;
                        }
                    } catch (JavaModelException ignored) {
                        /* fall through to generic file editor */
                    }
                }
                IEditorPart part = IDE.openEditor(page, iFile);
                if (part instanceof ITextEditor te) {
                    return te;
                }
            }
            URI fileUri = new java.io.File(absFile).toURI();
            IFileStore fileStore = EFS.getLocalFileSystem().getStore(fileUri);
            IEditorPart editor = IDE.openEditorOnFileStore(page, fileStore);
            return editor instanceof ITextEditor te ? te : null;
        } catch (Exception e) {
            MessageDialog.openError(shell, "Clone Refactoring Error",
                    "Could not open editor for:\n" + absFile + "\n" + e.getMessage());
            return null;
        }
    }

    private static ITextEditor findAlreadyOpenTextEditorMatching(String absFile) {
        var win = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (win == null) {
            return null;
        }
        IWorkbenchPage page = win.getActivePage();
        if (page == null) {
            return null;
        }
        String targetNorm = Paths.get(absFile).normalize().toString()
                .replace('\\', '/');
        String targetKey = CloneContext.canonicalJavaSourceKey(targetNorm);
        for (IEditorReference ref : page.getEditorReferences()) {
            IEditorPart part = ref.getEditor(false);
            if (!(part instanceof ITextEditor te)) {
                continue;
            }
            String ep = CloneRecordLiveExtract.absoluteFilePathForEditor(te);
            if (ep == null) {
                continue;
            }
            String epNorm = Paths.get(ep).normalize().toString().replace('\\', '/');
            if (targetNorm.equals(epNorm)) {
                return te;
            }
            if (targetKey != null) {
                String epKey = CloneContext.canonicalJavaSourceKey(epNorm);
                if (targetKey.equals(epKey)) {
                    return te;
                }
            }
        }
        return null;
    }

    // ── apply all edits for one file ──────────────────────────────────────────

    private static void applyToFile(Shell shell, String absFile,
                                     List<CloneSource> sources,
                                     ExtractedMethod em, CloneRecord record) {
        try {
            ITextEditor te = openTextEditorForAbsPath(shell, absFile);
            if (te == null) {
                return;
            }

            IDocument doc = te.getDocumentProvider().getDocument(te.getEditorInput());
            if (doc == null) { return; }

            List<Edit> edits = buildEditList(doc, sources, em, record);

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

    private static List<Edit> buildEditList(IDocument doc, List<CloneSource> sources,
            ExtractedMethod em, CloneRecord record)
            throws org.eclipse.jface.text.BadLocationException {
        List<CloneSource> copy = new ArrayList<>(sources);
        copy.sort(Comparator.comparingInt(CloneRefactoring::rangeStartLine).reversed());
        List<Edit> edits = new ArrayList<>();
        for (CloneSource src : copy) {
            Edit e = buildRangeEdit(doc, src, record);
            if (e != null) {
                edits.add(e);
            }
        }
        if (em != null && em.code != null && !em.code.isBlank()) {
            CloneSource anchor = copy.stream()
                    .filter(s -> s.enclosing_function != null
                            && s.enclosing_function.fun_range != null)
                    .max(Comparator.comparingInt(
                            s -> parseEnd(s.enclosing_function.fun_range)))
                    .orElse(null);
            if (anchor != null) {
                Edit e = buildInsertEdit(doc, anchor, em, record);
                if (e != null) {
                    edits.add(e);
                }
            }
        }
        return edits;
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

    // ── Dropzone: multiple clone groups (drag / view menu) ─────────────────────

    /** Prefix of the transfer string when every selected drop row has a {@code classid}. */
    public static final String DROPZONE_CLASSIDS_PAYLOAD = "DROPZONE_CLASSIDS:\n";

    public static boolean isDropzoneClassidsPayload(String snippet) {
        return snippet != null && snippet.startsWith(DROPZONE_CLASSIDS_PAYLOAD);
    }

    /**
     * Parses class id lines after {@link #DROPZONE_CLASSIDS_PAYLOAD}, resolves records,
     * confirms, then live-first per same-file group and parallel JSON for the rest.
     *
     * @param dropSourceOffset {@code >= 0} for drop offset; {@code < 0} uses editor caret
     */
    public static void applyFromDropzoneClassidsPayload(ITextEditor editor, Shell shell,
            String payload, int dropSourceOffset) {
        String body = payload.substring(DROPZONE_CLASSIDS_PAYLOAD.length());
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String line : body.split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                ids.add(t);
            }
        }
        if (ids.isEmpty()) {
            MessageDialog.openWarning(shell, "Dropzone",
                    "No clone group ids were found in the multi-item payload.");
            return;
        }
        CloneContext ctx = CloneContext.get();
        List<CloneRecord> records = new ArrayList<>();
        for (String id : ids) {
            CloneRecord r = ctx.recordMap.get(id);
            if (r != null) {
                records.add(r);
            }
        }
        if (records.isEmpty()) {
            MessageDialog.openWarning(shell, "Dropzone",
                    "None of the clone ids match the loaded clone JSON.");
            return;
        }
        if (!MessageDialog.openConfirm(shell, "Apply Extract Method",
                dropzoneMultiCloneConfirmMessage(records))) {
            return;
        }
        int placement = dropSourceOffset >= 0 ? dropSourceOffset
                : readPlacementCaretForDropzone(editor);
        String filePath = CloneRecordLiveExtract.absoluteFilePathForEditor(editor);
        applyDropzoneSelectedCloneGroups(editor, shell, records, filePath, placement);
    }

    private static String dropzoneMultiCloneConfirmMessage(List<CloneRecord> records) {
        StringBuilder sb = new StringBuilder();
        sb.append("Apply \"Extract Method\" for ").append(records.size())
                .append(" clone group(s) at once?\n\n");
        int totalSites = 0;
        for (CloneRecord r : records) {
            int n = r.sources != null ? r.sources.size() : 0;
            totalSites += n;
            sb.append("\u2022 ").append(r.classid).append(" (").append(n).append(" site(s))\n");
        }
        sb.append("\nEach group that matches the target editor file uses the live workflow first. ")
                .append("Other groups compute JSON edits in parallel (one task per group), ")
                .append("then all JSON changes are applied on the UI thread.\n\n")
                .append("Total ").append(totalSites).append(" clone site(s).\n\nUse Ctrl+Z to undo.");
        return sb.toString();
    }

    private static void applyDropzoneSelectedCloneGroups(ITextEditor editor, Shell shell,
            List<CloneRecord> records, String filePath, int placement) {
        List<CloneRecord> parallelJson = new ArrayList<>();
        for (CloneRecord r : records) {
            if (filePath != null
                    && CloneRecordLiveExtract.isSameFileCloneRecordForEditor(r, filePath)) {
                CloneRecordLiveExtract.Result live =
                        applyCloneRefactorWithLiveFirst(editor, shell, r, filePath, placement);
                if (live == CloneRecordLiveExtract.Result.FAILED) {
                    return;
                }
            } else {
                parallelJson.add(r);
            }
        }
        if (!parallelJson.isEmpty()) {
            Map<String, String> snap = snapshotTextsForRecords(parallelJson);
            applyRecordsJsonInParallel(shell, parallelJson, snap);
        }
        MessageDialog.openInformation(shell, "Extract Method Applied",
                "Applied " + records.size() + " clone group(s).");
    }

    /**
     * Tries {@link CloneRecordLiveExtract}; on {@code NOT_APPLICABLE} applies JSON edits.
     */
    public static CloneRecordLiveExtract.Result applyCloneRefactorWithLiveFirst(
            ITextEditor editor, Shell shell, CloneRecord record, String filePath,
            int placementSourceOffset) {
        CloneRecordLiveExtract.Result live =
                CloneRecordLiveExtract.tryApplyLive(editor, shell, record, filePath,
                        placementSourceOffset);
        if (live == CloneRecordLiveExtract.Result.NOT_APPLICABLE) {
            CloneRecordLiveExtract.Result demo =
                    CloneRecordLiveExtract.tryApplyExportQuarkusDemo(editor, shell, filePath,
                            placementSourceOffset);
            if (demo != CloneRecordLiveExtract.Result.NOT_APPLICABLE) {
                return demo;
            }
            apply(shell, record);
            return CloneRecordLiveExtract.Result.SUCCESS;
        }
        return live;
    }

    private static int readPlacementCaretForDropzone(ITextEditor editor) {
        if (editor == null) {
            return -1;
        }
        var sel = editor.getSelectionProvider().getSelection();
        if (sel instanceof ITextSelection ts) {
            return ts.getOffset();
        }
        return -1;
    }
}
