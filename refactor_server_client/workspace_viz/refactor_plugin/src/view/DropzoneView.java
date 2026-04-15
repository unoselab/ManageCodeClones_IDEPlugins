package view;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.ListViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DragSource;
import org.eclipse.swt.dnd.DragSourceAdapter;
import org.eclipse.swt.dnd.DragSourceEvent;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.texteditor.ITextEditor;

import refactor_plugin.dnd.DropzoneTransfer;
import refactor_plugin.handlers.CloneRecordLiveExtract;
import refactor_plugin.model.CloneContext;
import refactor_plugin.model.CloneRecord;
import refactor_plugin.util.CloneRefactoring;

/**
 * "Dropzone" sidebar view.
 *
 * Users collect code snippets here (via the "Add from Editor Selection" toolbar
 * button or by dragging text onto the list), then drag a snippet from this view
 * onto any open editor to trigger either:
 *
 *   • Clone-aware path  — if the target file was opened from the CloneGraphView,
 *                         EditorDropStartup applies the pre-computed Extract Method.
 *   • Multi-group path  — rows tagged with {@code [classid]}: multi-select, then drag
 *                         onto an editor or use the view menu / toolbar
 *                         "Apply selected clone groups to active editor".
 *   • Generic-wrap path — otherwise, prompts for a method name and inserts the
 *                         snippet wrapped in a method definition.
 *
 * Mirrors the DropzoneProvider class in the VS Code extension's extension.ts.
 */
public class DropzoneView extends ViewPart {

    public static final String ID = "view.DropzoneView";

    // ── Internal list item ────────────────────────────────────────────────────

    public static class DropItem {
        public final String content;
        /** When set (e.g. from graph-focused add), multi-select drag sends class ids for parallel refactor. */
        public final String classid;
        public final String label;

        public DropItem(String content) {
            this(content, null);
        }

        public DropItem(String content, String classid) {
            this.content = content;
            this.classid = classid;
            String t = content.trim().replace('\n', ' ');
            this.label = (classid != null && !classid.isBlank() ? "[" + classid + "] " : "")
                    + (t.length() > 50 ? t.substring(0, 50) + "\u2026" : t);
        }

        @Override public String toString() { return label; }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private ListViewer             listViewer;
    private final List<DropItem>   items     = new ArrayList<>();
    private String                 dragging  = null;  // content being dragged

    // ── View lifecycle ────────────────────────────────────────────────────────

    @Override
    public void createPartControl(Composite parent) {
        listViewer = new ListViewer(parent, SWT.BORDER | SWT.V_SCROLL | SWT.MULTI);
        listViewer.setContentProvider(ArrayContentProvider.getInstance());
        listViewer.setLabelProvider(new LabelProvider());
        listViewer.setInput(items);

        setupDragSource();
        contributeToolbar();
    }

    // ── Drag source (Dropzone → editor) ──────────────────────────────────────

    private void setupDragSource() {
        DragSource dragSource = new DragSource(
                listViewer.getControl(),
                DND.DROP_COPY | DND.DROP_MOVE);

        dragSource.setTransfer(
                new Transfer[]{ DropzoneTransfer.getInstance(), TextTransfer.getInstance() });

        dragSource.addDragListener(new DragSourceAdapter() {
            @Override
            public void dragStart(DragSourceEvent event) {
                IStructuredSelection sel = listViewer.getStructuredSelection();
                if (sel.isEmpty()) {
                    event.doit = false;
                    return;
                }
                Object[] selected = sel.toArray();
                LinkedHashSet<String> classids = new LinkedHashSet<>();
                boolean allHaveClassid = true;
                for (Object o : selected) {
                    if (!(o instanceof DropItem di)) {
                        event.doit = false;
                        return;
                    }
                    if (di.classid == null || di.classid.isBlank()) {
                        allHaveClassid = false;
                    } else {
                        classids.add(di.classid.trim());
                    }
                }
                if (!classids.isEmpty() && allHaveClassid) {
                    dragging = CloneRefactoring.DROPZONE_CLASSIDS_PAYLOAD
                            + String.join("\n", classids);
                } else {
                    dragging = ((DropItem) sel.getFirstElement()).content;
                }
            }

            @Override
            public void dragSetData(DragSourceEvent event) {
                if (DropzoneTransfer.getInstance().isSupportedType(event.dataType)) {
                    event.data = dragging;
                } else if (TextTransfer.getInstance().isSupportedType(event.dataType)) {
                    event.data = dragging;
                }
            }

            @Override
            public void dragFinished(DragSourceEvent event) {
                dragging = null;
            }
        });
    }

    // ── Toolbar ───────────────────────────────────────────────────────────────

    private void contributeToolbar() {
        IToolBarManager tbm = getViewSite().getActionBars().getToolBarManager();

        Action addAction = new Action("Add from Editor Selection") {
            @Override public void run() { addFromEditorSelection(); }
        };
        addAction.setToolTipText(
                "Add the currently selected text from the active editor to the Dropzone");

        Action removeAction = new Action("Remove Selected") {
            @Override public void run() {
                IStructuredSelection sel = listViewer.getStructuredSelection();
                sel.forEach(o -> items.remove(o));
                listViewer.refresh();
            }
        };
        removeAction.setToolTipText("Remove the selected snippet(s) from the Dropzone");

        Action clearAction = new Action("Clear All") {
            @Override public void run() {
                if (!items.isEmpty()
                        && MessageDialog.openConfirm(getSite().getShell(),
                                "Clear Dropzone", "Remove all snippets?")) {
                    items.clear();
                    listViewer.refresh();
                }
            }
        };
        clearAction.setToolTipText("Clear all snippets from the Dropzone");

        String applyTip =
                "Same as multi-select drag to a Java editor: live + parallel JSON refactor "
                + "for every selected row that has a [classid].";
        Action applyMultiToolbar = new Action("Apply selected clone groups to active editor") {
            @Override public void run() {
                applySelectedCloneGroupsToActiveEditor();
            }
        };
        applyMultiToolbar.setToolTipText(applyTip);
        Action applyMultiMenu = new Action("Apply selected clone groups to active editor") {
            @Override public void run() {
                applySelectedCloneGroupsToActiveEditor();
            }
        };
        applyMultiMenu.setToolTipText(applyTip);

        tbm.add(addAction);
        tbm.add(applyMultiToolbar);
        tbm.add(removeAction);
        tbm.add(clearAction);

        IMenuManager menu = getViewSite().getActionBars().getMenuManager();
        menu.add(applyMultiMenu);
    }

    /**
     * Menu/toolbar path for multi-group refactor (caret used for placement when not dropping).
     */
    private void applySelectedCloneGroupsToActiveEditor() {
        IStructuredSelection sel = listViewer.getStructuredSelection();
        if (sel.isEmpty()) {
            MessageDialog.openWarning(getSite().getShell(), "Dropzone",
                    "Select one or more drop rows that show a [classid] prefix.");
            return;
        }
        LinkedHashSet<String> classids = new LinkedHashSet<>();
        for (Object o : sel.toArray()) {
            if (!(o instanceof DropItem di)) {
                continue;
            }
            if (di.classid == null || di.classid.isBlank()) {
                MessageDialog.openWarning(getSite().getShell(), "Dropzone",
                        "Every selected row must have a clone group id (label starts with [classid]).\n"
                        + "Add from the editor while the matching clone is focused in the graph.");
                return;
            }
            classids.add(di.classid.trim());
        }
        if (classids.isEmpty()) {
            return;
        }
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null) {
            return;
        }
        var activeEditor = window.getActivePage().getActiveEditor();
        if (!(activeEditor instanceof ITextEditor editor)) {
            MessageDialog.openWarning(getSite().getShell(), "Dropzone",
                    "No Java/text editor is active. Activate the target editor, then run this action.");
            return;
        }
        String payload = CloneRefactoring.DROPZONE_CLASSIDS_PAYLOAD
                + String.join("\n", classids);
        CloneRefactoring.applyFromDropzoneClassidsPayload(editor, getSite().getShell(), payload,
                -1);
    }

    // ── Add from editor selection ─────────────────────────────────────────────

    private void addFromEditorSelection() {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null) { return; }

        // Use getActiveEditor() — it remembers the last active editor even after
        // the Dropzone toolbar button steals focus from the editor.
        var activeEditor = window.getActivePage().getActiveEditor();
        if (!(activeEditor instanceof ITextEditor editor)) {
            MessageDialog.openWarning(getSite().getShell(), "Dropzone",
                    "No editor is active. Click inside an editor and select some code first.");
            return;
        }

        var sel = editor.getSelectionProvider().getSelection();
        if (!(sel instanceof ITextSelection ts) || ts.getText() == null || ts.getText().isBlank()) {
            MessageDialog.openWarning(getSite().getShell(), "Dropzone",
                    "No text selected. Highlight some code in the editor, then click Add.");
            return;
        }

        String classidToAttach = null;
        CloneContext ctx = CloneContext.get();
        String focus = ctx.graphFocusClassid;
        if (focus != null && !focus.isBlank()) {
            CloneRecord rec = ctx.recordMap.get(focus.trim());
            String fp = CloneRecordLiveExtract.absoluteFilePathForEditor(editor);
            if (rec != null && fp != null
                    && CloneRecordLiveExtract.isSameFileCloneRecordForEditor(rec, fp)) {
                classidToAttach = focus.trim();
            }
        }
        addSnippet(ts.getText(), classidToAttach);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Adds a snippet to the list. Safe to call from any thread. */
    public void addSnippet(String content) {
        addSnippet(content, null);
    }

    /** Adds a snippet; optional {@code classid} enables multi-select drag to refactor several groups. */
    public void addSnippet(String content, String classid) {
        getSite().getShell().getDisplay().asyncExec(() -> {
            items.add(new DropItem(content, classid));
            listViewer.refresh();
        });
    }

    @Override
    public void setFocus() {
        listViewer.getControl().setFocus();
    }
}
