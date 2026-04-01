package refactor_plugin.listeners;

import java.io.FileReader;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.file.Paths;
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
import refactor_plugin.model.CloneContext;
import refactor_plugin.model.CloneRecord;
import refactor_plugin.util.CloneRefactoring;
import refactor_plugin.util.WrapHelper;

/**
 * Early-startup hook that attaches DnD and drag-detection listeners to every
 * text/Java editor opened in the workbench.
 *
 * Three drop/drag paths:
 *
 *  1. DropzoneTransfer drop  — snippet dragged from the Dropzone sidebar.
 *       a. Clone-aware : file is part of a loaded clone group → apply the
 *          pre-computed "Extract Method" across all clone sites.
 *       b. Generic wrap: no clone context → prompt for a method name and
 *          insert the snippet wrapped in a language-appropriate function.
 *
 *  2. Plain TextTransfer drop — ordinary editor-to-editor text drag
 *       (plain copy/move with no refactoring intent).
 *
 *  3. Intra-editor drag detection (mirrors VS Code dragListener):
 *       Watches IDocument for a delete+insert pair that indicates the user
 *       dragged a block of code within the same file.  If the file is part of
 *       a clone group, the drag is reverted and the pre-computed Extract Method
 *       is applied instead.
 */
public class EditorDropStartup implements IStartup {

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
     * Loads all_refactor_results.json from the runtime workspace into
     * {@link CloneContext} so that drag-drop can find clone records even before
     * the user opens the Clone Tree view.
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

            String[] candidates = {
                base + "/systems/all_refactor_results.json",
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
                        ctx.workspaceRoot = base;
                        ctx.recordMap.clear();
                        for (CloneRecord r : records) {
                            ctx.recordMap.put(r.classid, r);
                        }
                        System.out.println("[refactor_plugin] auto-loaded "
                            + records.size() + " records from " + jsonPath);
                    }
                }
                break; // stop after first successful load
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
                            handleDropzoneSnippet(editor, snippet, shell);
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

    private void handleDropzoneSnippet(ITextEditor editor, String snippet, Shell shell) {
        String      filePath = getEditorFilePath(editor);
        CloneRecord record   = filePath != null ? findRecordForFile(filePath) : null;
        System.out.println("[refactor_plugin] drop: filePath=" + filePath
            + "  recordMap.size=" + CloneContext.get().recordMap.size()
            + "  matched=" + (record != null ? record.classid : "null"));

        if (record != null) {
            // ── Clone-aware path ──────────────────────────────────────────
            boolean confirm = MessageDialog.openConfirm(shell,
                    "Apply Extract Method",
                    "Apply \"Extract Method\" for clone group \""
                    + record.classid + "\"?\n\n"
                    + record.sources.size()
                    + " clone site(s) will be updated together.\n"
                    + "Use Ctrl+Z to undo.");
            if (!confirm) { return; }

            CloneRefactoring.apply(shell, record);
            MessageDialog.openInformation(shell, "Extract Method Applied",
                    "Extract method applied for " + record.classid
                    + " \u2014 " + record.sources.size()
                    + " clone site(s) updated.");

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
                if (record == null) { return; }

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
                ignoreUntil[0] = now + 3000; // suppress further events while dialog is open

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
                        ignoreUntil[0] = 0; // allow normal edits again
                        return;
                    }

                    // 1. Revert the drag so line numbers in the JSON match again
                    try {
                        doc.replace(0, doc.getLength(), preDrag);
                    } catch (Exception ex) {
                        MessageDialog.openError(shell, "Revert Error",
                                "Could not revert drag: " + ex.getMessage());
                        ignoreUntil[0] = 0;
                        return;
                    }

                    // 2. Apply the pre-computed Extract Method refactoring
                    CloneRefactoring.apply(shell, rec);
                    MessageDialog.openInformation(shell, "Extract Method Applied",
                            "Done: " + rec.classid
                            + " \u2014 " + rec.sources.size()
                            + " site(s) updated.");
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

        // Normalise separators once for strategies 2 and 3
        String fileNorm = filePath.replace('\\', '/');

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
            }
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
