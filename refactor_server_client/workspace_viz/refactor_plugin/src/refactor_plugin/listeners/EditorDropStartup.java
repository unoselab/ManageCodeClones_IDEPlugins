package refactor_plugin.listeners;

import java.io.FileReader;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.dnd.DND;
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

import view.CloneGraphView;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.ui.IFileEditorInput;

import refactor_plugin.dnd.DropzoneTransfer;
import refactor_plugin.model.CloneContext;
import refactor_plugin.model.CloneRecord;
import refactor_plugin.util.ClonePathMatch;
import refactor_plugin.util.CloneRefactoring;
import refactor_plugin.util.DemoExtractRelocation;
import refactor_plugin.util.MultiSiteJdtExtract.ExtractMethodHandlerDemo;
import refactor_plugin.util.MultiSiteJdtExtract;
import refactor_plugin.util.MultiSiteJdtExtract.Result;
import refactor_plugin.util.WrapHelper;

/**
 * Early-startup hook that attaches DnD and drag-detection listeners to every
 * text/Java editor opened in the workbench.
 *
 * Three drop/drag paths:
 *
 *  1. DropzoneTransfer drop  — snippet dragged from the Dropzone sidebar.
 *       a. Clone-aware : file is part of a loaded clone group → same-file Java
 *          clones use {@link MultiSiteJdtExtract} (JDT/LTK); otherwise the
 *          pre-computed JSON {@link CloneRefactoring} path.
 *       b. Generic Java: no clone record → on {@link ExtractMethodHandlerDemo} target file,
 *          offer the same multi-site extract as Command Action 02 (EM); otherwise insert
 *          snippet and {@link MultiSiteJdtExtract#applyWithLineRanges} on the traced line
 *          range (console trace). Non-Java or fallback: wrap with {@link WrapHelper}.
 *
 *  2. Plain TextTransfer drop — after the editor inserts the text, optional JDT Extract Method
 *       on the dropped range (same logic as Dropzone; async, see
 *       {@link #handleEditorTextTransferDrop}).
 *
 *  3. Intra-editor drag detection (mirrors VS Code dragListener):
 *       Watches IDocument for a delete+insert pair (drag-down).  If a clone record matches,
 *       the drag is reverted and {@link #applyCloneRefactoringPreferJdt} runs.  On the
 *       ExportQuarkus Command Action 02 demo file, if the moved text matches the demo
 *       {@code for (dep)} clone block (same normalized text as
 *       {@link MultiSiteJdtExtract.ExtractMethodHandlerDemo#DEMO_CLONE_SNIPPET}), the drag is
 *       reverted and multi-site extract runs on <em>discovered</em> 1-based line ranges in the
 *       current file (fallback: fixed {@link MultiSiteJdtExtract.ExtractMethodHandlerDemo#SAME_FILE_CLONE_RANGES}).
 *       Matching uses {@link MultiSiteJdtExtract#intraEditorDragMatchesDemoClone}, plus a fallback
 *       when the pre-drag buffer still has two discovered clone windows and the moved body matches
 *       {@link MultiSiteJdtExtract#matchesDemoCloneBody}.  This wins over a loaded JSON
 *       {@link CloneRecord} for the same file.  After CA02 completes, document events are ignored
 *       briefly so refactoring echoes are not treated as a second drag (which would open the
 *       single-site Extract dialog).  On the ExportQuarkus demo file there is no generic
 *       single-site Extract dialog for intra-editor drag (only CA02 above).  Otherwise generic
 *       JDT on the moved span when there is no clone record, or the JSON clone confirm path.
 */
public class EditorDropStartup implements IStartup {

    /**
     * After Command Action 02 finishes from an intra-editor drag, keep ignoring
     * {@link IDocumentListener} events for this window so JDT/reconcile/relocate text deltas are
     * not parsed as a second drag (which would open the single-site Extract dialog and fail on
     * wrong offsets).
     */
    private static final long INTRA_DRAG_SILENCE_AFTER_CA02_MS = 5_000L;

    /**
     * After clone-record or generic intra-drag refactor, ignore document events briefly so
     * revert/replace/JDT echoes are not parsed as a second drag (which stacks dialogs).
     */
    private static final long INTRA_DRAG_SILENCE_AFTER_INTRA_REFACTOR_MS = 5_000L;

    private record DemoJdtRun(Result result, List<int[]> rangesApplied) {}

    /**
     * Multi-site JDT updates {@link ICompilationUnit#getBuffer()} first; the editor
     * {@link IDocument} can still hold the pre-refactor text, so {@link DemoExtractRelocation}
     * would parse the wrong source and skip the move (method stays at JDT default, often end of
     * the type). Copy working-copy source into the document before relocation.
     */
    private static void pushCuSourceToEditorDocument(ICompilationUnit cu, IDocument doc) {
        if (cu == null || doc == null) {
            return;
        }
        try {
            String src = cu.getSource();
            if (src == null) {
                return;
            }
            if (src.contentEquals(doc.get())) {
                return;
            }
            doc.replace(0, doc.getLength(), src);
        } catch (BadLocationException | JavaModelException e) {
            System.err.println("[refactor_plugin] sync editor document from CU: " + e.getMessage());
        }
    }

    /** After relocation edits {@link IDocument}, push back to the Java working copy. */
    private static void pushEditorDocumentToCu(ICompilationUnit cu, IDocument doc) {
        if (cu == null || doc == null) {
            return;
        }
        try {
            IBuffer buf = cu.getBuffer();
            if (buf == null) {
                return;
            }
            buf.setContents(doc.get());
        } catch (JavaModelException e) {
            System.err.println("[refactor_plugin] sync CU from editor document: " + e.getMessage());
        }
    }

    /**
     * Bundled ExportQuarkus demo snapshot line windows (1-based inclusive), for user-visible
     * labels when JDT reports a different attempted range (e.g. insert-at-drop or discovery
     * shift).
     */
    private static String demoCommandAction02ReferenceRangesLabel() {
        return "Reference Command Action 02 clone windows (1-based inclusive lines, bundled demo): "
                + Arrays.deepToString(ExtractMethodHandlerDemo.SAME_FILE_CLONE_RANGES) + ".";
    }

    @Override
    public void earlyStartup() {
        // IStartup.earlyStartup() is already called by Eclipse on a background
        // thread — no extra thread needed here.  Load the JSON first so that
        // CloneContext.recordMap is populated before the user tries a drag-drop,
        // even if the Clone Tree view has never been opened.
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
     * Loads {@code all_refactor_results.json} into {@link CloneContext} so drag-drop
     * works before Clone Tree opens. Tries {@link CloneContext#DEFAULT_CLONE_JSON}
     * first, then Eclipse {@code Platform.getLocation()} layouts.
     */
    private void tryAutoLoadJson() {
        try {
            org.eclipse.core.runtime.IPath wsLoc =
                    org.eclipse.core.runtime.Platform.getLocation();
            if (wsLoc == null) { return; }
            String base = wsLoc.toOSString();

            String rr = "/refactor_server_client/runtime-refactor_plugin";
            String[] candidates = {
                CloneContext.DEFAULT_CLONE_JSON,
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
                        ctx.recordMap.clear();
                        for (CloneRecord r : records) {
                            ctx.recordMap.put(r.classid, r);
                        }
                        System.out.println("[refactor_plugin] auto-loaded "
                            + records.size() + " records from " + jsonPath
                            + " (workspaceRoot=" + ctx.workspaceRoot + ")");
                        Display.getDefault().asyncExec(CloneGraphView::refreshIfOpen);
                        return;
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
            Transfer dropzoneXfer = DropzoneTransfer.getInstance();
            extended[0] = dropzoneXfer;
            System.arraycopy(existing, 0, extended, 1, existing.length);
            target.setTransfer(extended);

            target.addDropListener(new DropTargetAdapter() {
                @Override
                public void drop(DropTargetEvent event) {
                    Shell shell = widget.getShell();

                    // ── Path 1: Dropzone drag → clone-aware or generic wrap ──
                    if (dropzoneXfer.isSupportedType(event.currentDataType)) {
                        String snippet = (String) event.data;
                        if (snippet != null && !snippet.isBlank()) {
                            int dropOffset = resolveDropOffset(widget, event);
                            handleDropzoneSnippet(editor, snippet, shell, dropOffset);
                        }
                        return;
                    }

                    // ── Path 2: plain text (e.g. from another editor) ─────────
                    if (TextTransfer.getInstance().isSupportedType(event.currentDataType)) {
                        String snippet = (String) event.data;
                        if (snippet != null && !snippet.isBlank()
                                && compilationUnitFromJavaIFileEditor(editor) != null
                                && "java".equals(detectLanguage(editor))) {
                            final String payload = snippet;
                            final int textDropOffset = resolveDropOffset(widget, event);
                            // StyledText inserts first; same drop index as Dropzone uses
                            Display.getDefault().asyncExec(() -> handleEditorTextTransferDrop(
                                    editor, shell, widget, payload, textDropOffset));
                        }
                    }
                }
            });
        }

        // ── Path 3: intra-editor drag detection ───────────────────────────────
        attachDragDetector(editor, widget);
    }

    // ── Handle a drop from the Dropzone sidebar ───────────────────────────────

    /**
     * Maps display coordinates from a {@link DropTargetEvent} to a StyledText offset.
     */
    private static int resolveDropOffset(StyledText widget, DropTargetEvent event) {
        try {
            Point p = widget.getDisplay().map(null, widget, event.x, event.y);
            return widget.getOffsetAtLocation(p);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return widget.getCaretOffset();
        }
    }

    private void handleDropzoneSnippet(ITextEditor editor, String snippet, Shell shell,
            int dropOffset) {
        String      filePath = getEditorFilePath(editor);
        CloneRecord record   = filePath != null ? findRecordForFile(filePath) : null;
        System.out.println("[refactor_plugin] drop: filePath=" + filePath
            + "  recordMap.size=" + CloneContext.get().recordMap.size()
            + "  matched=" + (record != null ? record.classid : "null"));

        if (record != null) {
            // ── Clone-aware path ──────────────────────────────────────────
            System.out.println("[refactor_plugin] clone-aware drop: classid=" + record.classid);
            boolean confirm = MessageDialog.openConfirm(shell,
                    "Apply Extract Method",
                    "Apply \"Extract Method\" for clone group \""
                            + record.classid + "\"?\n\n"
                            + record.sources.size()
                            + " clone site(s) will be updated together (same multi-site JDT "
                            + "workflow as Command Action 02 when there are multiple sites: "
                            + "temp names, unify calls, remove extras, one final name).\n\n"
                            + "After a successful JDT extract, the unified method declaration "
                            + "is moved to a class-member position near your drop.\n\n"
                            + "Use Ctrl+Z to undo.");
            if (!confirm) { return; }

            applyCloneRefactoringPreferJdt(shell, filePath, record, editor, dropOffset);

        } else {
            // ── Generic wrap path (no clone record for this editor path) ───
            int n = CloneContext.get().recordMap.size();
            if (n > 0) {
                System.out.println("[refactor_plugin] generic wrap: no clone match for "
                        + filePath + " (" + n + " record(s) loaded). "
                        + "Use JSON under runtime-refactor_plugin/systems/; "
                        + "or open file from Clone Tree.");
            }
            String lang = detectLanguage(editor);
            ICompilationUnit javaCu = "java".equals(lang)
                    ? compilationUnitFromJavaIFileEditor(editor)
                    : null;

            // Same refactor as Command Action 02 (EM): two-site applyWithLineRanges on existing
            // clone locations. Match on Dropzone payload alone (golden clone body) and/or on the
            // buffer so we do not fall through to insert+single-site at an arbitrary drop offset
            // (JDT often rejects that as an invalid selection). On this demo file we never run
            // insert-at-drop JDT (wrong line span vs 316\u2013335 / 476\u2013495).
            if (javaCu != null && filePath != null
                    && ExtractMethodHandlerDemo.editorFileMatchesDemo(filePath)) {
                IDocument dropDoc = editor.getDocumentProvider()
                        .getDocument(editor.getEditorInput());
                try {
                    String srcNow = javaCu.getSource();
                    boolean demoPayload = MultiSiteJdtExtract.matchesDemoCloneBody(snippet)
                            || (srcNow != null && MultiSiteJdtExtract
                                    .droppedTextMatchesDemoCloneWindows(srcNow, snippet));
                    if (demoPayload && dropDoc != null) {
                        if (tryCommandAction02DemoDrop(shell, javaCu, dropDoc, dropOffset,
                                editor)) {
                            return;
                        }
                        return;
                    }
                    if (srcNow != null && dropDoc != null
                            && MultiSiteJdtExtract.discoverDemoCloneLineRanges(srcNow).size() >= 2) {
                        String refStr = Arrays.deepToString(
                                ExtractMethodHandlerDemo.SAME_FILE_CLONE_RANGES);
                        boolean go = MessageDialog.openConfirm(shell,
                                "Command Action 02 (EM)",
                                "This file contains two Command Action 02\u2013style clone windows, "
                                        + "but the Dropzone text did not match the bundled golden "
                                        + "clone body.\n\n"
                                        + "Run the same two-site Extract Method as the menu "
                                        + "(discovered 1-based line ranges; reference fallback "
                                        + refStr + " \u2192 "
                                        + ExtractMethodHandlerDemo.UNIFIED_METHOD_NAME + ")?\n\n"
                                        + "This avoids insert-at-drop, which often breaks JDT for "
                                        + "the clone loop.");
                        if (go) {
                            try {
                                applyDemoMultiSiteExtractWithMethodRelocation(shell, javaCu, dropDoc,
                                        dropOffset,
                                        "dropzone\u2192Command Action 02 (EM) [two clones in file]",
                                        editor);
                            } catch (Exception e) {
                                MessageDialog.openError(shell, "Extract failed",
                                        e.getMessage() != null ? e.getMessage() : e.toString());
                            }
                            return;
                        }
                    }
                } catch (JavaModelException e) {
                    System.err.println("[refactor_plugin] demo clone match on drop: "
                            + e.getMessage());
                }
            }

            InputDialog dlg = new InputDialog(shell,
                    "Extract Method (JDT)",
                    "Method name (same as Command Action 02 / JDT Extract Method):",
                    "extractedMethod",
                    v -> v.matches("[a-zA-Z_$][\\w$]*") ? null
                       : "Please enter a valid Java identifier.");
            if (dlg.open() != Window.OK) { return; }

            String methodName = dlg.getValue().trim();
            if (methodName.isEmpty()) { methodName = "extractedMethod"; }

            if (javaCu != null) {
                boolean demoEditor = filePath != null
                        && ExtractMethodHandlerDemo.editorFileMatchesDemo(filePath);
                if (!demoEditor && tryJdtExtractDroppedSnippet(editor, snippet, shell, dropOffset,
                        methodName, filePath)) {
                    return;
                }
            }

            String indent = getIndentAtOffset(editor, dropOffset);
            String wrapped = WrapHelper.wrapInMethod(snippet, methodName, lang, indent);
            insertAtOffset(editor, dropOffset, wrapped);
            MessageDialog.openInformation(shell, "Snippet Wrapped",
                    "Text-only wrap (no JDT): inserted " + methodName + "() at drop site.");
        }
    }

    private record JdtExtractAttempt(Result jdt, int[] oneBasedLines) {}

    /**
     * Reconciles and runs {@link MultiSiteJdtExtract#applyWithLineRanges} for text already
     * occupying {@code [startOff, endExclusive)} in {@code doc}.
     */
    private JdtExtractAttempt runJdtExtractOnDocumentRange(ICompilationUnit cu, IDocument doc,
            int startOff, int endExclusive, String methodName, String traceAbsPath,
            String traceTag) throws Exception {
        try {
            cu.reconcile(ICompilationUnit.NO_AST, false, null, new NullProgressMonitor());
        } catch (Exception reconcileEx) {
            System.err.println("[refactor_plugin] reconcile before extract: "
                    + reconcileEx.getMessage());
        }
        int[] lines = MultiSiteJdtExtract.oneBasedInclusiveLinesForRange(doc, startOff,
                endExclusive);
        logJdtSelectionTrace(traceTag, traceAbsPath, cu, doc, startOff, endExclusive, lines,
                endExclusive - startOff);
        List<int[]> oneSite = new ArrayList<>();
        oneSite.add(new int[]{ lines[0], lines[1] });
        Result jdtRes = MultiSiteJdtExtract.applyWithLineRanges(cu, oneSite, methodName);
        return new JdtExtractAttempt(jdtRes, lines);
    }

    /**
     * JDT extract on text already in the document (moved or externally dropped). Does not
     * remove text on failure (use Undo if needed).
     */
    private void tryJdtExtractExistingRange(ITextEditor editor, Shell shell,
            int startOff, int endExclusive, String methodName, String traceAbsPath,
            String traceTag) {
        ICompilationUnit cu = compilationUnitFromJavaIFileEditor(editor);
        IDocument doc = editor.getDocumentProvider().getDocument(editor.getEditorInput());
        if (cu == null || doc == null || endExclusive <= startOff) {
            return;
        }
        try {
            JdtExtractAttempt att = runJdtExtractOnDocumentRange(cu, doc, startOff,
                    endExclusive, methodName, traceAbsPath, traceTag);
            if (att.jdt().ok()) {
                try {
                    cu.reconcile(ICompilationUnit.NO_AST, false, null,
                            new NullProgressMonitor());
                    MultiSiteJdtExtract.revealMethodInEditor(cu, methodName, editor);
                } catch (Exception e) {
                    System.err.println("[refactor_plugin] post-extract: " + e.getMessage());
                }
                MessageDialog.openInformation(shell, "Extract Method (JDT)",
                        "Applied Eclipse Extract Method as " + methodName + "()\n"
                                + "Selection lines (1-based inclusive): "
                                + att.oneBasedLines()[0] + "\u2013" + att.oneBasedLines()[1]
                                + "\n(" + traceTag + " \u2014 same engine as Dropzone).");
                return;
            }
            StringBuilder msg = new StringBuilder();
            if (att.jdt().detail() != null && !att.jdt().detail().isBlank()) {
                msg.append(att.jdt().detail()).append("\n\n");
            } else if (att.jdt().title() != null && !att.jdt().title().isBlank()) {
                msg.append(att.jdt().title()).append("\n\n");
            }
            msg.append("Extract Method did not run. Fix errors in the enclosing method or "
                    + "adjust the selection, then try Refactor \u2192 Extract Method.");
            String d = att.jdt().detail() != null ? att.jdt().detail() : "";
            if (d.contains("not valid") || d.contains("selection")) {
                msg.append("\n\nFor small or partial highlights, JDT often needs a full sequence "
                        + "of statements (e.g. a whole loop or block), not a fragment.");
            }
            MessageDialog.openWarning(shell, "JDT Extract Method failed", msg.toString());
        } catch (Exception e) {
            MessageDialog.openError(shell, "Extract failed", e.getMessage());
        }
    }

    /**
     * After a {@link TextTransfer} drop: on the demo ExportQuarkus file, if the pasted text
     * matches the golden clone body and/or clone windows in the buffer (same idea as Dropzone),
     * offers Command Action 02 after removing the pasted span when it can be found. If the span
     * cannot be matched at the drop offset, the demo file does not show a single-site warning.
     * Otherwise single-site JDT on the resolved paste range for non-demo files.
     *
     * @param dropOffset index from {@link #resolveDropOffset(StyledText, DropTargetEvent)} (same as
     *                   {@link #handleDropzoneSnippet})
     */
    private void handleEditorTextTransferDrop(ITextEditor editor, Shell shell,
            StyledText widget, String snippet, int dropOffset) {
        if (widget.isDisposed()) {
            return;
        }
        ICompilationUnit cu = compilationUnitFromJavaIFileEditor(editor);
        IDocument doc = editor.getDocumentProvider().getDocument(editor.getEditorInput());
        if (cu == null || doc == null) {
            return;
        }
        String normalized = snippet.replace("\r\n", "\n").replace("\r", "\n");
        if (normalized.isBlank()) {
            return;
        }
        String filePath = getEditorFilePath(editor);
        if (filePath != null && ExtractMethodHandlerDemo.editorFileMatchesDemo(filePath)) {
            /* Same routing as Dropzone: golden body and/or buffer windows (clipboard may differ). */
            boolean demoTextDrop = demoCloneSiteIndexForBodyInSource(doc.get(), normalized) >= 0
                    || MultiSiteJdtExtract.matchesDemoCloneBody(normalized);
            if (demoTextDrop) {
                boolean go = MessageDialog.openConfirm(shell,
                        "Demo clone — Command Action 02 (EM)",
                        "The dropped text matches the demo ExportQuarkus clone block.\n\n"
                                + "Remove the pasted text and run the same two-site Extract Method "
                                + "as the menu (discovered 1-based line ranges; reference fallback "
                                + Arrays.deepToString(ExtractMethodHandlerDemo.SAME_FILE_CLONE_RANGES)
                                + " \u2192 "
                                + ExtractMethodHandlerDemo.UNIFIED_METHOD_NAME
                                + ").\n\n"
                                + "The extracted method is then moved after the method that "
                                + "contains your paste point (valid class-member placement).");
                if (go) {
                    try {
                        int[] pasted = findRangeSameLogicAsDropzoneInsert(doc, widget, dropOffset,
                                normalized);
                        if (pasted != null) {
                            doc.replace(pasted[0], pasted[1] - pasted[0], "");
                        } else {
                            System.err.println(
                                    "[refactor_plugin] CA02 text drop: could not locate pasted "
                                            + "range; running EM anyway (check for duplicate text).");
                        }
                        int placementOffset = offsetAfterRemovingRange(dropOffset, pasted);
                        applyDemoMultiSiteExtractWithMethodRelocation(shell, cu, doc, placementOffset,
                                "text-transfer-drop\u2192Command Action 02 (EM)", editor);
                    } catch (Exception e) {
                        MessageDialog.openError(shell, "Extract failed",
                                e.getMessage() != null ? e.getMessage() : e.toString());
                    }
                    return;
                }
                return;
            }
        }

        int[] range = findRangeSameLogicAsDropzoneInsert(doc, widget, dropOffset, normalized);
        if (range == null) {
            /* ExportQuarkus demo: never prompt here — paste may already match intent; single-site
             * offset match is unreliable (CRLF vs LF, widget vs IDocument). Use menu CA02 or Dropzone. */
            if (filePath != null && ExtractMethodHandlerDemo.editorFileMatchesDemo(filePath)) {
                System.err.println("[refactor_plugin] text-transfer on ExportQuarkus demo: could not "
                        + "locate pasted span at dropOffset=" + dropOffset
                        + "; skip single-site extract (use Command Action 02 / Dropzone if needed).");
                return;
            }
            MessageDialog.openWarning(shell, "JDT Extract Method",
                    "Could not match the dropped text at the drop offset (same index Dropzone uses). "
                            + "Try Dropzone or check line endings.\n\n"
                            + "dropOffset=" + dropOffset);
            return;
        }
        InputDialog dlg = new InputDialog(shell,
                "Extract Method (JDT)",
                "Method name (same as Command Action 02 / JDT Extract Method):",
                "extractedMethod",
                v -> v.matches("[a-zA-Z_$][\\w$]*") ? null
                        : "Please enter a valid Java identifier.");
        if (dlg.open() != Window.OK) {
            return;
        }
        String methodName = dlg.getValue().trim();
        if (methodName.isEmpty()) {
            methodName = "extractedMethod";
        }
        tryJdtExtractExistingRange(editor, shell, range[0], range[1], methodName, filePath,
                "text-transfer-drop");
    }

    /**
     * Maps an offset from before {@code doc.replace(p0, p1 - p0, "")} to coordinates after the
     * deletion. {@code removed} is {@code [p0, endExclusive)} or {@code null}.
     */
    private static int offsetAfterRemovingRange(int offsetBefore, int[] removed) {
        if (removed == null) {
            return offsetBefore;
        }
        int p0 = removed[0];
        int p1 = removed[1];
        int delLen = p1 - p0;
        if (offsetBefore <= p0) {
            return offsetBefore;
        }
        if (offsetBefore >= p1) {
            return offsetBefore - delLen;
        }
        return p0;
    }

    /**
     * Range {@code [start, endExclusive)} for text that Dropzone would have inserted at
     * {@code dropOffset}: try that offset (small slack), expanding {@code endExclusive} until
     * normalised document text equals the snippet (handles {@code \r\n} vs {@code \n}), then
     * fall back to selection/caret heuristics.
     */
    private static int[] findRangeSameLogicAsDropzoneInsert(IDocument doc, StyledText widget,
            int dropOffset, String normalizedSnippet) {
        String want = normalizedSnippet.replace("\r\n", "\n").replace("\r", "\n");
        if (want.isEmpty()) {
            return null;
        }
        int max = doc.getLength();
        for (int delta = -4; delta <= 4; delta++) {
            int start = Math.max(0, Math.min(dropOffset + delta, max));
            int[] r = rangeForSnippetStartingAt(doc, start, want);
            if (r != null) {
                return r;
            }
        }
        return locateDroppedSnippetInDocument(doc, widget, want);
    }

    /** Smallest {@code endExclusive} such that normalised {@code doc[start,endExclusive)} equals {@code want}. */
    private static int[] rangeForSnippetStartingAt(IDocument doc, int start, String want) {
        int max = doc.getLength();
        if (start < 0 || start > max) {
            return null;
        }
        int cap = Math.min(max, start + Math.max(want.length() * 3, want.length() + 128));
        for (int end = start + 1; end <= cap; end++) {
            try {
                String slice = doc.get(start, end - start).replace("\r\n", "\n").replace("\r", "\n");
                if (slice.equals(want)) {
                    return new int[]{ start, end };
                }
            } catch (Exception ignored) { /* next end */ }
        }
        return null;
    }

    /**
     * Resolves {@code [start, endExclusive)} for text just dropped via {@link TextTransfer}:
     * prefers {@link StyledText#getSelectionRange()} (often spans the insertion), then caret at
     * end, then caret at start, then a wide backward scan.
     */
    private static int[] locateDroppedSnippetInDocument(IDocument doc, StyledText widget,
            String normalizedSnippet) {
        String want = normalizedSnippet.replace("\r\n", "\n").replace("\r", "\n");
        if (want.isEmpty()) {
            return null;
        }
        Point sel = widget.getSelectionRange();
        if (sel != null && sel.y > 0) {
            int a = sel.x;
            int b = sel.x + sel.y;
            try {
                if (a >= 0 && b <= doc.getLength() && b > a) {
                    String slice = doc.get(a, b - a).replace("\r\n", "\n").replace("\r", "\n");
                    if (slice.equals(want)) {
                        return new int[]{ a, b };
                    }
                }
            } catch (Exception ignored) { /* fall through */ }
        }

        int c = Math.min(Math.max(0, widget.getCaretOffset()), doc.getLength());
        int[] endAtCaret = locateSnippetRangeEndingNearCaret(doc, c, want);
        if (endAtCaret != null) {
            return endAtCaret;
        }

        int docLen = doc.getLength();
        for (int len = 1; c + len <= docLen && len <= want.length() + 96; len++) {
            try {
                String slice = doc.get(c, len).replace("\r\n", "\n").replace("\r", "\n");
                if (slice.equals(want)) {
                    return new int[]{ c, c + len };
                }
            } catch (Exception ignored) { /* next */ }
        }

        return locateSnippetRangeEndingNearCaret(doc, docLen, want);
    }

    /**
     * Finds {@code [start, endExclusive)} ending at {@code caretHint} whose normalised text
     * equals {@code want} ({@code \r\n} vs {@code \n} tolerant).
     */
    private static int[] locateSnippetRangeEndingNearCaret(IDocument doc, int caretHint,
            String want) {
        if (want.isEmpty()) {
            return null;
        }
        int docLen = doc.getLength();
        int e = Math.min(Math.max(0, caretHint), docLen);
        int low = Math.max(0, e - want.length() - 200);
        for (int s = e - 1; s >= low; s--) {
            try {
                String slice = doc.get(s, e - s).replace("\r\n", "\n").replace("\r", "\n");
                if (slice.equals(want)) {
                    return new int[]{ s, e };
                }
            } catch (Exception ignored) { /* try smaller window */ }
        }
        return null;
    }

    /**
     * Same entry point as Command Action 02 (EM): {@link MultiSiteJdtExtract#applyWithLineRanges}
     * with the <strong>actual</strong> 1-based inclusive line range derived from the insert
     * offsets (logged to the console). Inserts the snippet, reconciles, then extracts; on JDT
     * failure the snippet is removed.
     *
     * @param traceAbsPath editor file path for tracing (may be {@code null})
     * @return {@code true} if this path ran (Java + IFile editor); {@code false} to
     *         fall back to {@link WrapHelper}
     */
    private boolean tryJdtExtractDroppedSnippet(ITextEditor editor, String snippet,
            Shell shell, int dropOffset, String methodName, String traceAbsPath) {
        ICompilationUnit cu = compilationUnitFromJavaIFileEditor(editor);
        if (cu == null) {
            return false;
        }
        IDocument doc = editor.getDocumentProvider().getDocument(editor.getEditorInput());
        if (doc == null) {
            return false;
        }

        String normalized = snippet.replace("\r\n", "\n").replace("\r", "\n");
        if (normalized.isBlank()) {
            return false;
        }

        int insertAt = Math.max(0, Math.min(dropOffset, doc.getLength()));
        try {
            int lenBefore = doc.getLength();
            doc.replace(insertAt, 0, normalized);
            // Physical length in buffer (may differ from normalized.length() if IDocument maps \n)
            int endExclusive = insertAt + (doc.getLength() - lenBefore);

            JdtExtractAttempt att = runJdtExtractOnDocumentRange(cu, doc, insertAt,
                    endExclusive, methodName, traceAbsPath, "dropzone-insert");
            if (att.jdt().ok()) {
                try {
                    cu.reconcile(ICompilationUnit.NO_AST, false, null,
                            new NullProgressMonitor());
                    MultiSiteJdtExtract.revealMethodInEditor(cu, methodName, editor);
                } catch (Exception e) {
                    System.err.println("[refactor_plugin] post-extract: " + e.getMessage());
                }
                MessageDialog.openInformation(shell, "Extract Method (JDT)",
                        "Applied Eclipse Extract Method as " + methodName + "()\n"
                                + "Selection lines (1-based inclusive): "
                                + att.oneBasedLines()[0] + "\u2013" + att.oneBasedLines()[1]
                                + "\n"
                                + "(MultiSiteJdtExtract.applyWithLineRanges, same as Command Action 02).");
                return true;
            }

            try {
                doc.replace(insertAt, endExclusive - insertAt, "");
                cu.reconcile(ICompilationUnit.NO_AST, false, null,
                        new NullProgressMonitor());
            } catch (Exception rollbackEx) {
                System.err.println("[refactor_plugin] rollback after failed extract: "
                        + rollbackEx.getMessage());
            }

            StringBuilder msg = new StringBuilder();
            if (att.jdt().detail() != null && !att.jdt().detail().isBlank()) {
                msg.append(att.jdt().detail()).append("\n\n");
            } else if (att.jdt().title() != null && !att.jdt().title().isBlank()) {
                msg.append(att.jdt().title()).append("\n\n");
            }
            msg.append("The inserted snippet was removed because Extract Method could not run.\n\n");
            msg.append("Fix red error markers in the enclosing method (JDT needs a clean parse), "
                    + "then drop again or use Refactor \u2192 Extract Method.\n\n");
            String det = att.jdt().detail() != null ? att.jdt().detail() : "";
            if (det.contains("not valid") || det.contains("selection")) {
                msg.append("Small selections: JDT usually needs a full block of statements, "
                        + "not a fragment.\n\n");
            }
            if (traceAbsPath != null
                    && ExtractMethodHandlerDemo.editorFileMatchesDemo(traceAbsPath)) {
                msg.append("This is the ExportQuarkus Command Action 02 demo file: insert-at-drop "
                        + "often fails for the clone loop. Add the exact demo clone block to the "
                        + "Dropzone (or use the menu Command Action 02 (EM)) so the plugin runs "
                        + "the two-site extract without pasting at the drop offset.\n\n");
                msg.append(demoCommandAction02ReferenceRangesLabel()).append(
                        "\n\nThe line numbers in JDT's message above refer to the single pasted "
                        + "snippet at your drop offset, not the two reference windows.\n");
            } else if (CloneContext.get().recordMap.isEmpty()) {
                msg.append("Clone JSON is not loaded (recordMap is empty), so drag-drop used the "
                        + "generic extract path only. The menu Command Action 02 (EM) demo uses "
                        + "hardcoded ranges and does not need JSON.\n"
                        + "To match clones on drop from JSON, use all_refactor_results.json under "
                        + "runtime-refactor_plugin/systems/ (see CloneContext).");
            }
            MessageDialog.openWarning(shell, "JDT Extract Method failed", msg.toString());
            return true;

        } catch (Exception e) {
            MessageDialog.openError(shell, "Drop failed",
                    "Could not insert snippet for JDT extract:\n" + e.getMessage());
            return true;
        }
    }

    /**
     * @return {@code 0} when {@code body} matches any Command Action 02 clone window in
     *         {@code fullSource} (fixed 316\u2013335 / 476\u2013495 or the same block at discovered
     *         lines), else {@code -1}
     */
    private static int demoCloneSiteIndexForBodyInSource(String fullSource, String body) {
        return MultiSiteJdtExtract.droppedTextMatchesDemoCloneWindows(fullSource, body) ? 0 : -1;
    }

    private DemoJdtRun runDemoMultiSiteJdtExtract(ICompilationUnit cu) throws Exception {
        List<int[]> ranges = MultiSiteJdtExtract.resolveExportQuarkusDemoCloneRanges(cu.getSource());
        Result result = MultiSiteJdtExtract.applyWithLineRanges(cu, ranges,
                MultiSiteJdtExtract.ExtractMethodHandlerDemo.UNIFIED_METHOD_NAME);
        return new DemoJdtRun(result, ranges);
    }

    private void presentDemoMultiSiteResult(Shell shell, ICompilationUnit cu, Result res,
            String logTag, List<int[]> rangesApplied, ITextEditor reuseEditorForReveal) {
        String rangesStr = rangesApplied != null && !rangesApplied.isEmpty()
                ? Arrays.deepToString(rangesApplied.toArray(new int[0][]))
                : Arrays.deepToString(
                        MultiSiteJdtExtract.ExtractMethodHandlerDemo.SAME_FILE_CLONE_RANGES);
        if (logTag != null) {
            System.out.println("[refactor_plugin] " + logTag + ": javaRelative="
                    + MultiSiteJdtExtract.ExtractMethodHandlerDemo.javaRelativePathForCu(cu)
                    + " ranges=" + rangesStr + " method="
                    + MultiSiteJdtExtract.ExtractMethodHandlerDemo.UNIFIED_METHOD_NAME);
        }
        if (res.ok()) {
            try {
                MultiSiteJdtExtract.revealMethodInEditor(cu,
                        MultiSiteJdtExtract.ExtractMethodHandlerDemo.UNIFIED_METHOD_NAME,
                        reuseEditorForReveal);
            } catch (Exception e) {
                System.err.println("[refactor_plugin] reveal demo extract: " + e.getMessage());
            }
            MessageDialog.openInformation(shell, res.title(),
                    res.detail() + "\n\nLine ranges applied (1-based inclusive): " + rangesStr);
        } else {
            String detail = res.detail() != null ? res.detail() : "";
            String extra = "\n\n" + demoCommandAction02ReferenceRangesLabel()
                    + "\n\nRanges attempted in this run (discovered or fallback): " + rangesStr + ".";
            MessageDialog.openWarning(shell, res.title(), detail + extra);
        }
    }

    /**
     * When the Dropzone payload matches the demo clone bodies (see
     * {@link MultiSiteJdtExtract#matchesDemoCloneBody} and/or
     * {@link MultiSiteJdtExtract#droppedTextMatchesDemoCloneWindows}), offer the same two-site
     * extract as the menu (discovered or fixed ranges; no snippet insert).
     *
     * @param doc          editor document (post-EM placement uses {@code dropOffset})
     * @param dropOffset   character offset from {@link #resolveDropOffset} (cursor / drop site)
     * @return {@code true} if this path handled the drop (confirmed and ran, or error dialog);
     *         {@code false} if the user cancelled (caller must not insert or run single-site EM)
     */
    private boolean tryCommandAction02DemoDrop(Shell shell, ICompilationUnit cu, IDocument doc,
            int dropOffset, ITextEditor editor) {
        String rangesStr = Arrays.deepToString(
                MultiSiteJdtExtract.ExtractMethodHandlerDemo.SAME_FILE_CLONE_RANGES);
        boolean confirm = MessageDialog.openConfirm(shell,
                "Command Action 02 (EM) via drop",
                "The dropped text matches the demo clone block in this file.\n\n"
                        + "Run the same two-site Extract Method as the menu command "
                        + "(discovered 1-based line ranges; reference fallback " + rangesStr
                        + " \u2192 "
                        + MultiSiteJdtExtract.ExtractMethodHandlerDemo.UNIFIED_METHOD_NAME
                        + ")?\n\n"
                        + "Refactoring is identical to Command Action 02 (EM). The snippet is "
                        + "not inserted. The extracted method is then moved to a valid class "
                        + "position: right after the method that contains your drop (or the "
                        + "nearest member gap).\n\n"
                        + "Cancel to abort (nothing is pasted from Dropzone).");
        if (!confirm) {
            return false;
        }
        try {
            applyDemoMultiSiteExtractWithMethodRelocation(shell, cu, doc, dropOffset,
                    "dropzone\u2192Command Action 02 (EM)", editor);
            return true;
        } catch (Exception e) {
            MessageDialog.openError(shell, "Extract failed",
                    e.getMessage() != null ? e.getMessage() : e.toString());
            return true;
        }
    }

    /**
     * Runs {@link #runDemoMultiSiteJdtExtract} (same edits as the menu), then moves
     * {@link ExtractMethodHandlerDemo#UNIFIED_METHOD_NAME} to a class-member position derived from
     * {@code userDropOffset} (after the enclosing method, or a valid gap between methods).
     */
    private void applyDemoMultiSiteExtractWithMethodRelocation(Shell shell, ICompilationUnit cu,
            IDocument doc, int userDropOffset, String logTag, ITextEditor editorForReveal)
            throws Exception {
        cu.reconcile(ICompilationUnit.NO_AST, false, null, new NullProgressMonitor());
        DemoJdtRun run = runDemoMultiSiteJdtExtract(cu);
        if (!run.result().ok()) {
            presentDemoMultiSiteResult(shell, cu, run.result(), logTag, run.rangesApplied(),
                    editorForReveal);
            return;
        }
        try {
            pushCuSourceToEditorDocument(cu, doc);
            DemoExtractRelocation.relocateExtractedMethodNearUserDrop(doc,
                    ExtractMethodHandlerDemo.UNIFIED_METHOD_NAME, userDropOffset);
            pushEditorDocumentToCu(cu, doc);
            cu.reconcile(ICompilationUnit.NO_AST, false, null, new NullProgressMonitor());
        } catch (Exception ex) {
            System.err.println("[refactor_plugin] relocate after EM: " + ex.getMessage());
        }
        presentDemoMultiSiteResult(shell, cu, run.result(), logTag, run.rangesApplied(),
                editorForReveal);
    }

    private static void logJdtSelectionTrace(String traceTag, String absPath,
            ICompilationUnit cu, IDocument doc, int insertAt, int endExclusive,
            int[] oneBasedInclusive, int charCount) {
        String rel = ExtractMethodHandlerDemo.javaRelativePathForCu(cu);
        StringBuilder sb = new StringBuilder();
        sb.append("[refactor_plugin] JDT trace [").append(traceTag).append("]: path=")
                .append(absPath != null ? absPath : "?");
        sb.append(" javaRelative=").append(rel != null ? rel : "?");
        sb.append(" insertOffset=").append(insertAt).append(" endExclusive=").append(endExclusive);
        sb.append(" oneBasedInclusiveLines=").append(oneBasedInclusive[0]).append("-")
                .append(oneBasedInclusive[1]);
        try {
            int len = doc.getLength();
            int lastOff = endExclusive > insertAt ? endExclusive - 1 : insertAt;
            if (lastOff >= len) {
                lastOff = Math.max(0, len - 1);
            }
            int line0 = doc.getLineOfOffset(insertAt);
            int line1 = doc.getLineOfOffset(lastOff);
            sb.append(" document0BasedLine=").append(line0).append("-").append(line1);
            int lo = doc.getLineOffset(line0);
            int plen = Math.min(200, doc.getLineLength(line0));
            if (plen > 0) {
                String preview = doc.get(lo, plen).replace('\r', ' ').replace('\n', ' ');
                sb.append(" firstPhysicalLinePreview=").append(preview);
            }
        } catch (Exception e) {
            sb.append(" lineMapError=").append(e.getMessage());
        }
        sb.append(" charCount=").append(charCount);
        System.out.println(sb);
    }

    private static ICompilationUnit compilationUnitFromJavaIFileEditor(ITextEditor editor) {
        var input = editor.getEditorInput();
        if (!(input instanceof IFileEditorInput fei)) {
            return null;
        }
        IFile file = fei.getFile();
        if (file == null || !file.exists()) {
            return null;
        }
        return JavaCore.createCompilationUnitFrom(file);
    }

    private static String getIndentAtOffset(ITextEditor editor, int offset) {
        IDocument doc = editor.getDocumentProvider().getDocument(editor.getEditorInput());
        if (doc == null) {
            return "";
        }
        try {
            int line = doc.getLineOfOffset(Math.max(0, Math.min(offset, doc.getLength())));
            String lineText = doc.get(doc.getLineOffset(line), doc.getLineLength(line));
            Matcher m = Pattern.compile("^(\\s*)").matcher(lineText);
            return m.find() ? m.group(1) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static void insertAtOffset(ITextEditor editor, int offset, String text) {
        IDocument doc = editor.getDocumentProvider().getDocument(editor.getEditorInput());
        if (doc == null) {
            return;
        }
        try {
            int o = Math.max(0, Math.min(offset, doc.getLength()));
            doc.replace(o, 0, text);
        } catch (Exception e) { /* ignore */ }
    }

    /**
     * Same-file Java clone on the JDT classpath: {@link MultiSiteJdtExtract#applyForCloneRecord}
     * (same multi-site temp-name / unify / delete / rename pipeline as Command Action 02 when there
     * are multiple sites). Otherwise {@link CloneRefactoring}.
     * <p>
     * When {@code relocateEditor} is non-null and {@code userDropOffset} is valid, the unified
     * extracted method declaration is moved to a class-member position derived from the drop
     * (same {@link DemoExtractRelocation} rules as the ExportQuarkus Dropzone demo).
     */
    private void applyCloneRefactoringPreferJdt(Shell shell, String filePath, CloneRecord record,
            ITextEditor relocateEditor, int userDropOffset) {
        ICompilationUnit cu =
                MultiSiteJdtExtract.findCompilationUnitForAbsolutePath(filePath);
        if (cu == null && relocateEditor != null) {
            ICompilationUnit fromDrop = compilationUnitFromJavaIFileEditor(relocateEditor);
            if (fromDrop != null) {
                cu = fromDrop;
            }
        }
        boolean strictSameFile = MultiSiteJdtExtract.isSameFileJavaCloneForEditor(record, filePath);
        boolean relaxedSameFile = isLikelySameFileCloneForEditor(record, filePath);
        if (cu != null && (strictSameFile || relaxedSameFile)) {
            Result res = MultiSiteJdtExtract.applyForCloneRecord(cu, record);
            if (res.ok()) {
                String unified = MultiSiteJdtExtract.unifiedNameFromRecord(record);
                if (relocateEditor != null && userDropOffset >= 0) {
                    IDocument edDoc = relocateEditor.getDocumentProvider()
                            .getDocument(relocateEditor.getEditorInput());
                    if (edDoc != null) {
                        try {
                            pushCuSourceToEditorDocument(cu, edDoc);
                            DemoExtractRelocation.relocateExtractedMethodNearUserDrop(edDoc, unified,
                                    userDropOffset);
                            pushEditorDocumentToCu(cu, edDoc);
                            cu.reconcile(ICompilationUnit.NO_AST, false, null,
                                    new NullProgressMonitor());
                        } catch (Exception ex) {
                            System.err.println("[refactor_plugin] relocate after clone JDT: "
                                    + ex.getMessage());
                        }
                    }
                }
                try {
                    MultiSiteJdtExtract.revealMethodInEditor(cu, unified, relocateEditor);
                } catch (Exception e) {
                    System.err.println("[refactor_plugin] reveal after JDT extract: "
                            + e.getMessage());
                }
                MessageDialog.openInformation(shell, res.title(),
                        res.detail() + "\n\nClone group: " + record.classid
                                + " \u2014 " + record.sources.size() + " site(s).");
                return;
            }
            System.out.println("[refactor_plugin] JDT extract skipped, using JSON: "
                    + res.title() + " \u2014 " + res.detail()
                    + " [strictSameFile=" + strictSameFile
                    + ", relaxedSameFile=" + relaxedSameFile + "]");
        }
        CloneRefactoring.apply(shell, record, relocateEditor);
        MessageDialog.openInformation(shell, "Extract Method Applied",
                "Applied precomputed JSON edits for " + record.classid + " \u2014 "
                        + record.sources.size()
                        + " site(s). (JDT was not used: different files, no Java model, "
                        + "or extract preconditions failed \u2014 see console.)");
    }

    /**
     * Relaxed same-file check for mirrored workspaces:
     * clone JSON may point to systems/.../Foo.java while editor is project_target.../Foo.java.
     */
    private boolean isLikelySameFileCloneForEditor(CloneRecord record, String editorAbsPath) {
        if (record == null || record.sources == null || record.sources.isEmpty()
                || editorAbsPath == null || record.same_file != 1) {
            return false;
        }
        String editorBase = java.nio.file.Paths.get(editorAbsPath.replace('\\', '/'))
                .getFileName().toString();
        for (CloneRecord.CloneSource s : record.sources) {
            if (s == null || s.file == null || s.range == null || s.range.isBlank()) {
                return false;
            }
            String[] parts = s.range.split("-");
            if (parts.length < 2) {
                return false;
            }
            try {
                Integer.parseInt(parts[0].trim());
                Integer.parseInt(parts[1].trim());
            } catch (Exception ex) {
                return false;
            }
            String srcBase = java.nio.file.Paths.get(s.file.replace('\\', '/'))
                    .getFileName().toString();
            if (!editorBase.equals(srcBase)) {
                return false;
            }
        }
        return true;
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

                // Command Action 02 demo clone: run fixed/discovered two-site extract even when
                // JSON also lists this file (clone record would otherwise steal the drag path).
                final boolean exportQuarkusDemoEditor = ExtractMethodHandlerDemo
                        .editorFileMatchesDemo(filePath)
                        && "java".equals(detectLanguage(editor))
                        && compilationUnitFromJavaIFileEditor(editor) != null;
                final boolean demoDragStrict = exportQuarkusDemoEditor
                        && MultiSiteJdtExtract.intraEditorDragMatchesDemoClone(preDrag, delOff,
                                body);
                /* Fallback when strict offset/body pairing fails but the moved text is still the
                 * demo clone and the pre-drag file still has two windows (matches Dropzone logic). */
                final boolean demoDragLoose = exportQuarkusDemoEditor
                        && MultiSiteJdtExtract.discoverDemoCloneLineRanges(preDrag).size() >= 2
                        && MultiSiteJdtExtract.matchesDemoCloneBody(body);
                final boolean commandAction02Demo = demoDragStrict || demoDragLoose;
                if (commandAction02Demo) {
                    final String preDragSnapshot = preDrag;
                    /* After reverting to pre-drag text, this offset marks the paste gap in pre coords. */
                    final int userDropForRelocate = insOff;
                    /* Hold until the runnable finishes: a short window expires while the success
                     * dialog is open and JDT/reconcile events then look like a second drag. */
                    ignoreUntil[0] = Long.MAX_VALUE;
                    Display.getDefault().asyncExec(() -> {
                        Shell shell = widget.isDisposed()
                                ? Display.getDefault().getActiveShell()
                                : widget.getShell();
                        System.out.println("[refactor_plugin] intra-editor drag matches "
                                + "Command Action 02 demo clone block; reverting drag and "
                                + "running multi-site extract (discovered 1-based ranges, "
                                + "else fallback "
                                + Arrays.deepToString(
                                        ExtractMethodHandlerDemo.SAME_FILE_CLONE_RANGES)
                                + ").");
                        try {
                            doc.replace(0, doc.getLength(), preDragSnapshot);
                            ICompilationUnit cu = compilationUnitFromJavaIFileEditor(editor);
                            if (cu != null) {
                                pushEditorDocumentToCu(cu, doc);
                                cu.reconcile(ICompilationUnit.NO_AST, false, null,
                                        new NullProgressMonitor());
                                DemoJdtRun run = runDemoMultiSiteJdtExtract(cu);
                                if (run.result().ok()) {
                                    pushCuSourceToEditorDocument(cu, doc);
                                    DemoExtractRelocation.relocateExtractedMethodNearUserDrop(doc,
                                            ExtractMethodHandlerDemo.UNIFIED_METHOD_NAME,
                                            userDropForRelocate);
                                    pushEditorDocumentToCu(cu, doc);
                                    cu.reconcile(ICompilationUnit.NO_AST, false, null,
                                            new NullProgressMonitor());
                                }
                                presentDemoMultiSiteResult(shell, cu, run.result(),
                                        "intra-editor-move\u2192Command Action 02 (EM)",
                                        run.rangesApplied(), editor);
                            }
                        } catch (Exception ex) {
                            MessageDialog.openError(shell, "Extract failed",
                                    ex.getMessage() != null ? ex.getMessage()
                                            : ex.toString());
                        } finally {
                            ignoreUntil[0] = System.currentTimeMillis()
                                    + INTRA_DRAG_SILENCE_AFTER_CA02_MS;
                            e1Off[0] = -1;
                            e1Time[0] = 0L;
                        }
                    });
                    return;
                }

                if (record == null) {
                    if (!"java".equals(detectLanguage(editor))
                            || compilationUnitFromJavaIFileEditor(editor) == null) {
                        return;
                    }
                    /* ExportQuarkus demo: Command Action 02 is the only intra-editor extract path
                     * (handled above). Do not open the single-site name dialog — it can stack under
                     * the CA02 result and uses invalid ranges after multi-site edits. */
                    if (ExtractMethodHandlerDemo.editorFileMatchesDemo(filePath)) {
                        return;
                    }
                    final int movedStart = gapStart;
                    final int movedEndEx = insOff;
                    final String fp = filePath;
                    /* Hold through dialog + JDT; short 3s window expires if user pauses before
                     * confirm + doc.replace, then a second fake drag can stack InputDialog. */
                    ignoreUntil[0] = Long.MAX_VALUE;

                    Display.getDefault().asyncExec(() -> {
                        Shell shell = widget.isDisposed()
                                ? Display.getDefault().getActiveShell()
                                : widget.getShell();

                        InputDialog dlg = new InputDialog(shell,
                                "Extract Method (JDT)",
                                "Method name (same as Command Action 02 / JDT Extract Method):",
                                "extractedMethod",
                                v -> v.matches("[a-zA-Z_$][\\w$]*") ? null
                                        : "Please enter a valid Java identifier.");
                        if (dlg.open() != Window.OK) {
                            ignoreUntil[0] = System.currentTimeMillis()
                                    + INTRA_DRAG_SILENCE_AFTER_INTRA_REFACTOR_MS;
                            return;
                        }
                        String methodName = dlg.getValue().trim();
                        if (methodName.isEmpty()) {
                            methodName = "extractedMethod";
                        }
                        try {
                            tryJdtExtractExistingRange(editor, shell, movedStart, movedEndEx,
                                    methodName, fp, "intra-editor-move");
                        } finally {
                            ignoreUntil[0] = System.currentTimeMillis()
                                    + INTRA_DRAG_SILENCE_AFTER_INTRA_REFACTOR_MS;
                        }
                    });
                    return;
                }

                final CloneRecord rec = record;
                /* Same as CA02: 3s is too short if user reads confirm slowly; revert then looks
                 * like a second drag and stacks generic JDT dialog on top of clone confirm. */
                ignoreUntil[0] = Long.MAX_VALUE;

                Display.getDefault().asyncExec(() -> {
                    Shell shell = widget.isDisposed()
                            ? Display.getDefault().getActiveShell()
                            : widget.getShell();

                    boolean ok = MessageDialog.openConfirm(shell,
                            "Apply Extract Method",
                            "Apply \"Extract Method\" for clone group \""
                            + rec.classid + "\"?\n\n"
                            + rec.sources.size()
                            + " clone site(s) will be updated.\nUse Ctrl+Z to undo.");

                    if (!ok) {
                        ignoreUntil[0] = System.currentTimeMillis()
                                + INTRA_DRAG_SILENCE_AFTER_INTRA_REFACTOR_MS;
                        return;
                    }

                    // 1. Revert the drag so line numbers in the JSON match again
                    try {
                        doc.replace(0, doc.getLength(), preDrag);
                    } catch (Exception ex) {
                        MessageDialog.openError(shell, "Revert Error",
                                "Could not revert drag: " + ex.getMessage());
                        ignoreUntil[0] = System.currentTimeMillis()
                                + INTRA_DRAG_SILENCE_AFTER_INTRA_REFACTOR_MS;
                        return;
                    }

                    // 2. JDT multi-site (same file) or pre-computed JSON
                    try {
                        applyCloneRefactoringPreferJdt(shell, filePath, rec, editor, -1);
                    } finally {
                        ignoreUntil[0] = System.currentTimeMillis()
                                + INTRA_DRAG_SILENCE_AFTER_INTRA_REFACTOR_MS;
                    }
                });
            }
        });
    }

    // ── Clone-record lookup (used by both paths) ──────────────────────────────

    /**
     * Finds the clone record for {@code filePath} using three strategies
     * in order of specificity:
     *
     * <ol>
     *   <li>Exact match via {@code lastOpenedByFile} (fastest — set when the
     *       user double-clicks a source node in the Clone Tree).</li>
     *   <li>Exact resolved-path match using {@code CloneContext.resolvePath()}
     *       (works when {@code workspaceRoot} is set correctly).</li>
     *   <li>Path-suffix match — the absolute OS path always ends with the
     *       relative JSON source path, regardless of whether
     *       {@code workspaceRoot} is set or what path prefix Eclipse reports.
     *       This is the most robust fallback and mirrors how VS Code's
     *       {@code document.uri.fsPath} always gives an absolute path that
     *       contains the relative JSON path as a suffix.</li>
     * </ol>
     */
    private CloneRecord findRecordForFile(String filePath) {
        if (filePath == null) { return null; }
        CloneContext ctx = CloneContext.get();

        // Strategy 1: exact hit from Clone Tree navigation
        String cid = ctx.lastOpenedByFile.get(filePath);
        CloneRecord found = cid != null ? ctx.recordMap.get(cid) : null;
        if (found != null) { return found; }

        List<CloneRecord> candidates = new ArrayList<>();
        for (CloneRecord r : ctx.recordMap.values()) {
            if (r.sources == null) { continue; }
            for (CloneRecord.CloneSource src : r.sources) {
                if (src.file == null) { continue; }
                if (ClonePathMatch.editorMatchesSourceFile(filePath, src.file, ctx)) {
                    candidates.add(r);
                    break;
                }
            }
        }
        if (candidates.isEmpty()) { return null; }
        if (candidates.size() == 1) { return candidates.get(0); }

        CloneRecord byGraph = chooseByGraphFocus(candidates, ctx);
        return byGraph != null ? byGraph : candidates.get(0);
    }

    private CloneRecord chooseByGraphFocus(List<CloneRecord> candidates, CloneContext ctx) {
        if (ctx.preferredClassId != null && !ctx.preferredClassId.isBlank()) {
            for (CloneRecord r : candidates) {
                if (ctx.preferredClassId.equals(r.classid)) {
                    return r;
                }
            }
        }

        List<CloneRecord> projectMatches = new ArrayList<>();
        if (ctx.preferredProject != null && !ctx.preferredProject.isBlank()) {
            for (CloneRecord r : candidates) {
                if (ctx.preferredProject.equals(r.project)) {
                    projectMatches.add(r);
                }
            }
        } else {
            projectMatches.addAll(candidates);
        }
        if (projectMatches.size() == 1) {
            return projectMatches.get(0);
        }

        if (ctx.preferredClassName != null && !ctx.preferredClassName.isBlank()) {
            for (CloneRecord r : projectMatches) {
                if (recordContainsClassName(r, ctx.preferredClassName)) {
                    return r;
                }
            }
        }

        return projectMatches.isEmpty() ? null : projectMatches.get(0);
    }

    private boolean recordContainsClassName(CloneRecord r, String className) {
        if (r == null || r.sources == null || className == null) { return false; }
        for (CloneRecord.CloneSource src : r.sources) {
            String srcClass = classNameFromSource(src);
            if (className.equals(srcClass)) {
                return true;
            }
        }
        return false;
    }

    private String classNameFromSource(CloneRecord.CloneSource src) {
        if (src == null) { return null; }
        String qn = (src.enclosing_function != null) ? src.enclosing_function.qualified_name : null;
        if (qn != null && !qn.isBlank()) {
            int lastDot = qn.lastIndexOf('.');
            if (lastDot > 0) {
                String owner = qn.substring(0, lastDot);
                int classDot = owner.lastIndexOf('.');
                return classDot >= 0 ? owner.substring(classDot + 1) : owner;
            }
        }
        if (src.file != null && !src.file.isBlank()) {
            String name = java.nio.file.Paths.get(src.file.replace('\\', '/')).getFileName().toString();
            int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(0, dot) : name;
        }
        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the absolute OS file path for the editor's input.
     *
     * Three strategies are tried in order:
     *
     * 1. {@code ILocationProvider} adapter — implemented by
     *    {@code FileEditorInput} (Package Explorer files).  Returns the
     *    absolute OS path via {@code IFile.getLocation()}.  This is the
     *    primary path for files opened from the Package Explorer and was the
     *    missing link: {@code IFileEditorInput.getAdapter(IPath.class)}
     *    returns {@code null}, so the previous IPath-first logic always fell
     *    through to {@code null} for those files.
     *
     * 2. {@code IPath} adapter — works for {@code IFileStoreEditorInput}
     *    (EFS / external files, e.g. opened by CloneTreeView).  Uses
     *    {@code makeRelative()} before {@code IPath.append()} to prevent
     *    the silent "absolute IPath passed to append() returns itself" bug.
     *
     * 3. {@code URI} adapter — generic fallback.
     */
    private String getEditorFilePath(ITextEditor editor) {
        var input = editor.getEditorInput();

        // ── Strategy 1: reflective IFileEditorInput path ─────────────────────
        // FileEditorInput (Package Explorer files) exposes getFile() → IFile,
        // and IFile.getLocation() returns the absolute OS IPath.
        // We call both methods reflectively to avoid a compile-time dependency
        // on org.eclipse.core.resources (IFile is defined there), while still
        // getting the absolute OS path that IFile.getLocation() provides.
        try {
            java.lang.reflect.Method getFile = input.getClass().getMethod("getFile");
            Object iFile = getFile.invoke(input);
            if (iFile != null) {
                java.lang.reflect.Method getLoc = iFile.getClass().getMethod("getLocation");
                Object loc = getLoc.invoke(iFile);
                if (loc instanceof org.eclipse.core.runtime.IPath p && !p.isEmpty()) {
                    return p.toOSString();
                }
            }
        } catch (Exception ignored) { /* not an IFileEditorInput — try next strategy */ }

        // ── Strategy 2: IPath adapter (EFS / external files) ─────────────────
        var ipath = input.getAdapter(org.eclipse.core.runtime.IPath.class);
        if (ipath != null) {
            String s = ipath.toOSString();
            if (new java.io.File(s).exists()) { return s; }
            // Workspace-relative IPath has a leading '/'.  IPath.append() treats
            // any IPath whose isAbsolute()==true as absolute and returns it
            // unchanged.  makeRelative() strips the leading separator first.
            org.eclipse.core.runtime.IPath wsLoc =
                    org.eclipse.core.runtime.Platform.getLocation();
            if (wsLoc != null) {
                return wsLoc.append(ipath.makeRelative()).toOSString();
            }
            return s;
        }

        // ── Strategy 3: URI adapter ───────────────────────────────────────────
        try {
            URI uri = input.getAdapter(URI.class);
            if (uri != null) { return Paths.get(uri).toString(); }
        } catch (Exception ignored) {}

        return null;
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
