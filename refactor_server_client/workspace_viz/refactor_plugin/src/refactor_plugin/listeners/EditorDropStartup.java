package refactor_plugin.listeners;

import java.io.FileReader;
import java.lang.reflect.Type;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import refactor_plugin.dnd.DropzoneTransfer;
import refactor_plugin.handlers.CloneRecordLiveExtract;
import refactor_plugin.handlers.ExtractMethodWorkflow;
import refactor_plugin.model.CloneContext;
import refactor_plugin.model.CloneRecord;
import refactor_plugin.util.CloneRefactoring;
import refactor_plugin.util.WrapHelper;
import view.CloneGraphView;

/**
 * Early-startup hook that attaches DnD and drag-detection listeners to every
 * text/Java editor opened in the workbench.
 *
 * Three drop/drag paths:
 *
 *  1. DropzoneTransfer drop  — snippet dragged from the Dropzone sidebar.
 *       a. Clone-aware : file is part of a loaded clone group → live
 *          {@link refactor_plugin.handlers.ExtractMethodWorkflow} for all same-file
 *          sites in one step (same pipeline as {@link refactor_plugin.handlers.ExtractMethodWorkflow}:
 *          bottom-up LTK, {@code IMethod.delete}, AST rebind/move), else JSON
 *          {@link refactor_plugin.util.CloneRefactoring}.
 *       b. Generic wrap: no clone context → prompt for a method name and
 *          insert the snippet wrapped in a language-appropriate function.
 *
 *  2. Plain TextTransfer drop — ordinary editor-to-editor text drag
 *       (plain copy/move with no refactoring intent).
 *
 *  3. Intra-editor drag detection (mirrors VS Code dragListener):
 *       Watches IDocument for a delete+insert pair that indicates the user
 *       dragged a block of code within the same file.  If the file is part of
 *       a clone group, the drag is reverted then the same live / JSON refactor as (1a).
 *
 *       <p>Reverting restores a pristine buffer (README: avoid line-shift before applying
 *       ranges). One gesture refactors every same-file site per {@code README_multi_0408.md}
 *       when {@link refactor_plugin.handlers.CloneRecordLiveExtract} can obtain a workspace
 *       {@code ICompilationUnit}; otherwise {@link refactor_plugin.util.CloneRefactoring}
 *       applies precomputed text with bottom-up offset order and reconcile.
 */
public class EditorDropStartup implements IStartup {

    @Override
    public void earlyStartup() {
        // IStartup.earlyStartup() is already called by Eclipse on a background
        // thread — no extra thread needed here.  Load the JSON first so that
        // CloneContext.recordMap is populated before the user tries a drag-drop,
        // even if the Clone Graph view has never been opened.
        tryAutoLoadJson();
        Display.getDefault().asyncExec(this::attachToCurrentEditors);
    }

    private void attachToCurrentEditors() {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null) { return; }

        // Attach to every editor that was already open when the plugin started.
        // (partOpened won't fire for editors restored from the previous session.)
        var page = window.getActivePage();
        if (page != null) {
            for (var ref : page.getEditorReferences()) {
                var part = ref.getEditor(false);
                if (part instanceof ITextEditor te) { attachDropListener(te); }
            }
        }

        // Hook future editors as they are opened.
        hookEditorListener(window);
    }

    /**
     * Loads all_refactor_results.json from the runtime workspace into
     * {@link CloneContext} so that drag-drop can find clone records even before
     * the user opens the Clone Graph view.
     *
     * Uses {@code Platform.getLocation()} (the Eclipse runtime workspace root)
     * instead of a hardcoded path so it works on any machine.
     */
    private void tryAutoLoadJson() {
        try {
            org.eclipse.core.runtime.IPath wsLoc =
                    org.eclipse.core.runtime.Platform.getLocation();
            if (wsLoc == null) { return; }
            String base = wsLoc.toOSString();

            String rr = "/refactor_server_client/runtime-refactor_plugin";
            String[] candidates = {
//                CloneContext.DEFAULT_CLONE_JSON,
                base + rr + "/systems/all_refactor_results.json",
                base + "/systems/all_refactor_results.json",
                base + rr + "/all_refactor_results.json",
                base + "/all_refactor_results.json",
            };
            for (String jsonPath : candidates) {
                java.io.File f = new java.io.File(jsonPath);
                if (!f.exists()) { continue; }
                try (FileReader reader = new FileReader(f)) {
                    Gson gson = new Gson();
                    Type type = new TypeToken<List<CloneRecord>>() {}.getType();
                    List<CloneRecord> records = gson.fromJson(reader, type);
                    if (records != null && !records.isEmpty()) {
                        CloneContext ctx = CloneContext.get();
                        ctx.workspaceRoot = CloneContext.workspaceRootForCloneJson(jsonPath);
                        ctx.workspaceRoot =
                                CloneContext.workspaceRootForCloneJson(f.getAbsolutePath());
                        ctx.recordMap.clear();
                        for (CloneRecord r : records) {
                            ctx.recordMap.put(r.classid, r);
                        }
                        System.out.println("[refactor_plugin] auto-loaded "
                            + records.size() + " records from " + jsonPath);
                        CloneGraphView.refreshIfOpen();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[refactor_plugin] tryAutoLoadJson FAILED: " + e);
        }
    }

    // ── Part listener — attach on every new editor ────────────────────────────

    private void hookEditorListener(IWorkbenchWindow window) {
        window.getPartService().addPartListener(new IPartListener2() {
            @Override
            public void partOpened(IWorkbenchPartReference partRef) {
                IWorkbenchPart part = partRef.getPart(false);
                if (part instanceof ITextEditor te) { attachDropListener(te); }
            }
            @Override public void partActivated(IWorkbenchPartReference r)    {}
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r)       {}
            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partVisible(IWorkbenchPartReference r)      {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}
        });
    }

    // ── Attach to a specific editor ───────────────────────────────────────────

    private void attachDropListener(ITextEditor editor) {
        StyledText widget = (StyledText) editor.getAdapter(Control.class);
        if (widget == null || widget.isDisposed()) { return; }

        // Guard: only attach once per widget
        if (Boolean.TRUE.equals(widget.getData("refactor.dnd.attached"))) { return; }
        widget.setData("refactor.dnd.attached", Boolean.TRUE);

        DropTarget target = (DropTarget) widget.getData(DND.DROP_TARGET_KEY);
        if (target != null) {
            // Put DropzoneTransfer FIRST so it wins the DnD type negotiation
            // over TextTransfer.  This prevents the native StyledText from
            // inserting raw text when a Dropzone item is dropped.
            Transfer[] existing = target.getTransfer();
            Transfer[] extended  = new Transfer[existing.length + 1];
            extended[0] = DropzoneTransfer.getInstance(); // highest priority
            System.arraycopy(existing, 0, extended, 1, existing.length);
            target.setTransfer(extended);

            target.addDropListener(new DropTargetAdapter() {
                @Override
                public void drop(DropTargetEvent event) {
                    Shell shell = widget.getShell();

                    // ── Path 1: Dropzone drag → clone-aware or generic wrap ──
                    if (DropzoneTransfer.getInstance()
                            .isSupportedType(event.currentDataType)) {
                        String snippet = (String) event.data;
                        if (snippet != null && !snippet.isBlank()) {
                            int dropOffset = offsetAtDrop(widget, event);
                            handleDropzoneSnippet(editor, snippet, shell, dropOffset);
                        }
                        return;
                    }

                    // ── Path 2: plain text drag (no refactoring intent) ──────
                    // The intra-editor drag case is handled by the document
                    // listener below (attachDragDetector).  Nothing extra to do
                    // here for plain-text drops.
                }
            });
        }

        // ── Path 3: intra-editor drag detection ───────────────────────────────
        attachDragDetector(editor, widget);
    }

    // ── Handle a drop from the Dropzone sidebar ───────────────────────────────

    /**
     * @param dropSourceOffset {@link StyledText#getOffsetAtPoint} for the drop, or {@code -1}
     */
    private void handleDropzoneSnippet(ITextEditor editor, String snippet, Shell shell,
            int dropSourceOffset) {
        if (CloneRefactoring.isDropzoneClassidsPayload(snippet)) {
            CloneRefactoring.applyFromDropzoneClassidsPayload(editor, shell, snippet,
                    dropSourceOffset);
            return;
        }

        int placement = dropSourceOffset >= 0 ? dropSourceOffset : readPlacementCaret(editor);
        String      filePath = getEditorFilePath(editor);
        CloneRecord record   = filePath != null ? findRecordForFile(filePath) : null;
        System.out.println("[refactor_plugin] drop: filePath=" + filePath
            + "  recordMap.size=" + CloneContext.get().recordMap.size()
            + "  matched=" + (record != null ? record.classid : "null"));

        if (record != null) {
            // ── Clone-aware path (one drop → all same-file sites, e.g. 2 clones) ──
            boolean confirm = MessageDialog.openConfirm(shell,
                    "Apply Extract Method",
                    cloneGroupConfirmMessage(record));
            if (!confirm) { return; }

            CloneRecordLiveExtract.Result r =
                    CloneRefactoring.applyCloneRefactorWithLiveFirst(editor, shell, record,
                            filePath, placement);
            if (r != CloneRecordLiveExtract.Result.FAILED) {
                MessageDialog.openInformation(shell, "Extract Method Applied",
                        "Extract method applied for " + record.classid
                        + " \u2014 " + record.sources.size()
                        + " clone site(s) updated.");
            }

        } else if (ExtractMethodWorkflow.isExportQuarkusDemoWorkspacePath(filePath)) {
            // ── Course demo: same two-site extract as Command Action 02 (no clone JSON) ──
            boolean ok = MessageDialog.openConfirm(shell,
                    "Apply Extract Method (demo)",
                    exportQuarkusDemoConfirmMessage());
            if (!ok) { return; }
            CloneRecordLiveExtract.Result r =
                    CloneRecordLiveExtract.tryApplyExportQuarkusDemo(editor, shell, filePath,
                            placement);
            if (r == CloneRecordLiveExtract.Result.SUCCESS) {
                MessageDialog.openInformation(shell, "Extract Method Applied",
                        "ExportQuarkus demo: both sites extracted and merged (same as Command Action 02).");
            }

        } else {
            // ── Generic wrap path ─────────────────────────────────────────
            InputDialog dlg = new InputDialog(shell,
                    "Wrap in Extracted Method",
                    "Name for the method that will wrap the dropped snippet:",
                    "extractedMethod",
                    v -> v.matches("[a-zA-Z_$][\\w$]*") ? null
                       : "Please enter a valid Java identifier.");
            if (dlg.open() != Window.OK) { return; }

            String methodName = dlg.getValue().trim();
            if (methodName.isEmpty()) { methodName = "extractedMethod"; }

            String lang    = detectLanguage(editor);
            String indent  = getCaretIndent(editor);
            String wrapped = WrapHelper.wrapInMethod(snippet, methodName, lang, indent);

            insertAtCaret(editor, wrapped);
            MessageDialog.openInformation(shell, "Snippet Wrapped",
                    "Snippet wrapped in " + methodName + "() and inserted.");
        }
    }

    // ── Intra-editor drag detection (mirrors VS Code dragListener) ────────────

    /**
     * Attaches an {@link IDocumentListener} that mirrors the VS Code
     * {@code onDidChangeTextDocument} drag-listener logic.
     *
     * VS Code fires one event with TWO content-changes (delete + insert of the
     * same text) when the user drags a block within the same file.  Eclipse
     * fires two consecutive {@code documentChanged} callbacks instead.
     *
     * Algorithm (drag-DOWN only, matching VS Code):
     * <pre>
     *   e1 = INSERT at I (original coords), text = body
     *   e2 = DELETE at D (original coords), length = N = body.length()
     *   Condition: I &gt; D + N
     *
     *   post_drag = orig[0..D] + orig[D+N..I] + body + orig[I..]
     *   pre_drag  = post[0..D] + body + post[D..I-N] + post[I..]
     * </pre>
     *
     * On confirm, revert then live multi-site extract ({@link CloneRecordLiveExtract})
     * or JSON fallback ({@link refactor_plugin.util.CloneRefactoring}).
     */
    private void attachDragDetector(ITextEditor editor, StyledText widget) {
        IDocument doc = editor.getDocumentProvider()
                              .getDocument(editor.getEditorInput());
        if (doc == null) { return; }

        // Mutable state for the two-event batch (arrays so lambdas can capture)
        long[]   ignoreUntil = { 0L };
        int[]    e1Off       = { -1 };
        int[]    e1Len       = { -1 };
        String[] e1Text      = { null };
        long[]   e1Time      = { 0L };

        doc.addDocumentListener(new IDocumentListener() {
            @Override public void documentAboutToBeChanged(DocumentEvent e) {}

            @Override
            public void documentChanged(DocumentEvent e) {
                long now = System.currentTimeMillis();
                if (now < ignoreUntil[0]) { return; }

                String txt = e.getText() != null ? e.getText() : "";
                int    off = e.getOffset();
                int    len = e.getLength();

                if (e1Off[0] < 0 || now - e1Time[0] > 250) {
                    // Store as first event of a potential drag
                    e1Off[0]  = off;
                    e1Len[0]  = len;
                    e1Text[0] = txt;
                    e1Time[0] = now;
                    return;
                }

                // Second event within 250 ms — check for drag pattern
                int    o1 = e1Off[0],  l1 = e1Len[0];
                String t1 = e1Text[0] != null ? e1Text[0] : "";
                e1Off[0] = -1; // reset first-event slot

                // Identify which event is the insertion and which is the deletion
                int    insOff, delOff, bodyLen;
                String body;
                if (!t1.isEmpty() && l1 == 0 && txt.isEmpty() && len > 0) {
                    insOff = o1; body = t1;  delOff = off; bodyLen = len;
                } else if (!txt.isEmpty() && len == 0 && t1.isEmpty() && l1 > 0) {
                    insOff = off; body = txt; delOff = o1;  bodyLen = l1;
                } else { return; }

                if (bodyLen != body.length()) { return; }
                // Only handle drag-DOWN (insertion point is below deleted block end)
                if (insOff <= delOff + bodyLen) { return; }
                // Ignore trivially short drags (not a refactoring candidate)
                if (body.replaceAll("\\s+", "").length() < 25) { return; }

                String filePath = getEditorFilePath(editor);
                if (filePath == null) { return; }
                CloneRecord record = findRecordForFile(filePath);
                final boolean exportQuarkusDemo =
                        record == null
                        && ExtractMethodWorkflow.isExportQuarkusDemoWorkspacePath(filePath);
                if (record == null && !exportQuarkusDemo) { return; }

                // Reconstruct the pre-drag document — mirrors VS Code revertDrag():
                //   pre = post[0..D] + body + post[D..I-N] + post[I..]
                // where D=delOff, N=bodyLen, I=insOff (all in original coords for drag-down)
                String postDrag = doc.get();
                int gapStart    = insOff - bodyLen;
                int gapEnd      = insOff;
                if (delOff < 0 || gapStart < delOff || gapEnd > postDrag.length()) { return; }
                String preDrag  = postDrag.substring(0, delOff)
                                + body
                                + postDrag.substring(delOff, gapStart)
                                + postDrag.substring(gapEnd);

                final CloneRecord rec = record;
                final int placement = readPlacementCaret(editor);
                ignoreUntil[0] = now + 3000; // suppress further events while dialog is open

                Display.getDefault().asyncExec(() -> {
                    Shell shell = widget.isDisposed()
                            ? Display.getDefault().getActiveShell()
                            : widget.getShell();

                    String confirmTitle = exportQuarkusDemo
                            ? "Apply Extract Method (demo)"
                            : "Apply Extract Method";
                    String confirmMsg = exportQuarkusDemo
                            ? exportQuarkusDemoConfirmMessage()
                            : cloneGroupConfirmMessage(rec);
                    boolean ok = MessageDialog.openConfirm(shell, confirmTitle, confirmMsg);

                    if (!ok) {
                        ignoreUntil[0] = 0; // allow normal edits again
                        return;
                    }

                    // 1. Restore pristine text so line ranges match (JSON or fixed demo lines).
                    try {
                        doc.replace(0, doc.getLength(), preDrag);
                    } catch (Exception ex) {
                        MessageDialog.openError(shell, "Revert Error",
                                "Could not revert drag: " + ex.getMessage());
                        ignoreUntil[0] = 0;
                        return;
                    }

                    CloneRecordLiveExtract.Result r;
                    if (exportQuarkusDemo) {
                        r = CloneRecordLiveExtract.tryApplyExportQuarkusDemo(editor, shell, filePath,
                                placement);
                        if (r == CloneRecordLiveExtract.Result.SUCCESS) {
                            MessageDialog.openInformation(shell, "Extract Method Applied",
                                    "ExportQuarkus demo: both sites updated (Command Action 02).");
                        }
                    } else {
                        r = CloneRefactoring.applyCloneRefactorWithLiveFirst(editor, shell, rec,
                                filePath, placement);
                        if (r != CloneRecordLiveExtract.Result.FAILED) {
                            MessageDialog.openInformation(shell, "Extract Method Applied",
                                    "Done: " + rec.classid
                                    + " \u2014 " + rec.sources.size()
                                    + " site(s) updated.");
                        }
                    }
                });
            }
        });
    }

    /**
     * Explains that one confirm refactors every same-file site (e.g. two clones) using the
     * {@link refactor_plugin.handlers.ExtractMethodWorkflow} when the workspace CU is available.
     */
    private static String cloneGroupConfirmMessage(CloneRecord record) {
        int n = record.sources != null ? record.sources.size() : 0;
        return "Apply \"Extract Method\" for clone group \"" + record.classid + "\"?\n\n"
                + "Same workflow as Command Action 02 (EM): bottom-up extract, Java model "
                + "deletion of duplicate extracted methods, AST rebind of calls, optional move.\n\n"
                + "Placement: drop on a line (or put the caret there before OK). Drop/caret inside "
                + "a method \u2192 move after it; in trailing whitespace before the class "
                + "closing brace \u2192 end of class; else clone JSON hint.\n\n"
                + "All " + n + " clone site(s) in this file run in one step (you moved or dropped "
                + "one instance; both/all ranges in the JSON are updated together).\n\n"
                + "Use Ctrl+Z to undo.";
    }

    /** Confirm text when no clone JSON is loaded but the file is the ExportQuarkus demo. */
    private static String exportQuarkusDemoConfirmMessage() {
        return "Apply the Command Action 02 ExportQuarkus demo (no clone JSON loaded)?\n\n"
                + "This runs the same two-site extract as the menu command: lines 316\u2013335 and "
                + "476\u2013495 \u2192 extractedM1Block1, merge, then move the result.\n\n"
                + "Placement: drop target (or caret before OK) inside a method \u2192 after it; "
                + "trailing whitespace before class } \u2192 end of class; else "
                + "replaceQuarkusDependencies(...) (default).\n\n"
                + "Use Ctrl+Z to undo.";
    }

    private static int readPlacementCaret(ITextEditor editor) {
        if (editor == null) {
            return -1;
        }
        var sel = editor.getSelectionProvider().getSelection();
        if (sel instanceof ITextSelection ts) {
            return ts.getOffset();
        }
        return -1;
    }

    /** Drop coordinates are relative to the {@link StyledText} drop target. */
    private static int offsetAtDrop(StyledText widget, DropTargetEvent event) {
        if (widget == null || widget.isDisposed() || event == null) {
            return -1;
        }
        try {
            return widget.getOffsetAtPoint(new Point(event.x, event.y));
        } catch (Exception e) {
            return -1;
        }
    }

    // ── Clone-record lookup (used by both paths) ──────────────────────────────

    /**
     * Finds the clone record for {@code filePath} using three strategies
     * in order of specificity:
     *
     * <ol>
     *   <li>Clone Graph <strong>focus</strong> {@code graphFocusClassid} when it names a
     *       group whose sites match the editor file (same-file clones under different
     *       project roots, e.g. {@code systems/…} vs {@code project_target01/…}).</li>
     *   <li>Exact match via {@code lastOpenedByFile} (set when the user selects or opens
     *       a clone instance in the Clone Graph).</li>
     *   <li>Canonical {@code org/…} match against any {@code lastOpenedByFile} key when the
     *       editor’s absolute path string differs (e.g. workspace vs {@code systems/} copy).</li>
     *   <li>Exact resolved-path match using {@code CloneContext.resolvePath()}
     *       (works when {@code workspaceRoot} is set correctly).</li>
     *   <li>Path-suffix match — the absolute OS path always ends with the
     *       relative JSON source path, regardless of whether
     *       {@code workspaceRoot} is set or what path prefix Eclipse reports.
     *       This is the most robust fallback and mirrors how VS Code's
     *       {@code document.uri.fsPath} always gives an absolute path that
     *       contains the relative JSON path as a suffix.</li>
     *   <li>{@link CloneContext#pathsEqualForCloneData} — same logical {@code org/…} file
     *       as JSON when Eclipse reports a different absolute prefix.</li>
     * </ol>
     */
    private CloneRecord findRecordForFile(String filePath) {
        if (filePath == null) { return null; }
        CloneContext ctx = CloneContext.get();
        String fileNorm = filePath.replace('\\', '/');

        // Strategy 0: explicit clone group focus from Clone Graph (single-click instance)
        String focusCid = ctx.graphFocusClassid;
        if (focusCid != null && !focusCid.isBlank()) {
            CloneRecord focused = ctx.recordMap.get(focusCid);
            if (focused != null
                    && CloneRecordLiveExtract.isSameFileCloneRecordForEditor(focused, filePath)) {
                return focused;
            }
        }

        // Strategy 1: exact hit from Clone Graph navigation
        String cid = ctx.lastOpenedByFile.get(filePath);
        CloneRecord found = cid != null ? ctx.recordMap.get(cid) : null;
        if (found != null) { return found; }

        // Strategy 1b: same logical file as a registered path (different prefix / symlink / casing)
        String keyEditor = CloneContext.canonicalJavaSourceKey(fileNorm);
        if (keyEditor != null) {
            for (var e : ctx.lastOpenedByFile.entrySet()) {
                String k = e.getKey();
                if (k == null || k.isBlank()) {
                    continue;
                }
                String kNorm = k.replace('\\', '/');
                if (fileNorm.equals(kNorm)) {
                    continue;
                }
                String keyStored = CloneContext.canonicalJavaSourceKey(kNorm);
                if (keyStored != null && keyEditor.equals(keyStored)) {
                    CloneRecord byKey = ctx.recordMap.get(e.getValue());
                    if (byKey != null) {
                        return byKey;
                    }
                }
            }
        }

        for (CloneRecord r : ctx.recordMap.values()) {
            if (r.sources == null) { continue; }
            for (CloneRecord.CloneSource src : r.sources) {
                if (src.file == null) { continue; }
                String srcNorm = src.file.replace('\\', '/');

                // Strategy 2: resolved absolute path (requires workspaceRoot set)
                String resolved = ctx.resolvePath(src.file).replace('\\', '/');
                if (resolved.equals(fileNorm)) { return r; }

                // Strategy 3: suffix match — the OS path always ends with the
                // relative path from the JSON, e.g.:
                //   filePath = "/…/runtime-refactor_plugin/systems/…/ExportQuarkus.java"
                //   src.file = "systems/…/ExportQuarkus.java"
                if (fileNorm.endsWith("/" + srcNorm)) { return r; }

                if (ctx.pathsEqualForCloneData(filePath, src.file)) { return r; }
            }
        }
        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** @see CloneRecordLiveExtract#absoluteFilePathForEditor(ITextEditor) */
    private String getEditorFilePath(ITextEditor editor) {
        return CloneRecordLiveExtract.absoluteFilePathForEditor(editor);
    }

    private String detectLanguage(ITextEditor editor) {
        var ipath = editor.getEditorInput()
                          .getAdapter(org.eclipse.core.runtime.IPath.class);
        if (ipath == null) { return "java"; }
        String ext = ipath.getFileExtension();
        if (ext == null) { return "java"; }
        return switch (ext.toLowerCase()) {
            case "py"       -> "python";
            case "ts", "js" -> "typescript";
            default         -> "java";
        };
    }

    private String getCaretIndent(ITextEditor editor) {
        var sel = editor.getSelectionProvider().getSelection();
        if (!(sel instanceof ITextSelection ts)) { return ""; }
        IDocument doc = editor.getDocumentProvider()
                               .getDocument(editor.getEditorInput());
        if (doc == null) { return ""; }
        try {
            int lineIdx = ts.getStartLine();
            if (lineIdx < 0) { return ""; }
            String lineText = doc.get(doc.getLineOffset(lineIdx),
                                      doc.getLineLength(lineIdx));
            Matcher m = Pattern.compile("^(\\s*)").matcher(lineText);
            return m.find() ? m.group(1) : "";
        } catch (Exception e) { return ""; }
    }

    private void insertAtCaret(ITextEditor editor, String text) {
        var sel = editor.getSelectionProvider().getSelection();
        if (!(sel instanceof ITextSelection ts)) { return; }
        IDocument doc = editor.getDocumentProvider()
                               .getDocument(editor.getEditorInput());
        if (doc == null) { return; }
        try { doc.replace(ts.getOffset(), 0, text); }
        catch (Exception e) { /* ignore insertion errors */ }
    }
}
