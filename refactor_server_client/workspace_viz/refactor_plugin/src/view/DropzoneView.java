package view;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.action.Action;
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

/**
 * "Dropzone" sidebar view.
 *
 * Users collect code snippets here (via the "Add from Editor Selection" toolbar
 * button or by dragging text onto the list), then drag a snippet from this view
 * onto any open editor to trigger either:
 *
 *   • Clone-aware path  — if the target file was opened from the CloneGraphView,
 *                         EditorDropStartup applies the pre-computed Extract Method.
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
        public final String label;

        public DropItem(String content) {
            this.content = content;
            String t = content.trim().replace('\n', ' ');
            this.label = t.length() > 50 ? t.substring(0, 50) + "\u2026" : t;
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
                if (sel.isEmpty()) { event.doit = false; return; }
                dragging = ((DropItem) sel.getFirstElement()).content;
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

        tbm.add(addAction);
        tbm.add(removeAction);
        tbm.add(clearAction);
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

        addSnippet(ts.getText());
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Adds a snippet to the list. Safe to call from any thread. */
    public void addSnippet(String content) {
        getSite().getShell().getDisplay().asyncExec(() -> {
            items.add(new DropItem(content));
            listViewer.refresh();
        });
    }

    @Override
    public void setFocus() {
        listViewer.getControl().setFocus();
    }
}
