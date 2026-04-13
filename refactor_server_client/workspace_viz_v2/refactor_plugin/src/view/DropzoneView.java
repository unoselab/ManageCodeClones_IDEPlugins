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
import refactor_plugin.handlers.extract.ExtractionTarget;
import refactor_plugin.model.CloneContext;
import refactor_plugin.model.CloneRecord;
import refactor_plugin.util.UtilClone;
import view.clone.CloneDragPayload;

/**
 * "Dropzone" sidebar view.
 *
 * Users collect code snippets here (via the "Add from Editor Selection" toolbar button or by dragging text onto the list), then drag a snippet from this view onto any open editor to trigger either:
 *
 * • Clone-aware path — if the target file was opened from the CloneTreeView, EditorDropStartup applies the pre-computed Extract Method. • Generic-wrap path — otherwise, prompts for a method name and inserts the snippet wrapped in a method definition.
 *
 * Mirrors the DropzoneProvider class in the VS Code extension's extension.ts.
 */
public class DropzoneView extends ViewPart {

   public static final String ID = "view.DropzoneView";

   // ── Internal list item ────────────────────────────────────────────────────

   public static class DropItem {
      public final String content;
      public final String label;
      public final CloneDragPayload payload;

      public DropItem(String content, CloneDragPayload payload) {
         this.content = content;
         this.payload = payload;
         String t = content.trim().replace('\n', ' ');
         this.label = t.length() > 50 ? t.substring(0, 50) + "\u2026" : t;
      }

      public DropItem(String content) {
         this.content = content;
         this.payload = null;
         String t = content.trim().replace('\n', ' ');
         this.label = t.length() > 50 ? t.substring(0, 50) + "\u2026" : t;
      }

      @Override
      public String toString() {
         return label;
      }
   }

   // ── State ─────────────────────────────────────────────────────────────────

   private ListViewer listViewer;
   private final List<DropItem> items = new ArrayList<>();
   private String dragging = null; // content being dragged

   private DropItem draggingItem = null;

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
      DragSource dragSource = new DragSource(listViewer.getControl(), DND.DROP_COPY | DND.DROP_MOVE);

      dragSource.setTransfer(new Transfer[] { DropzoneTransfer.getInstance(), TextTransfer.getInstance() });

      dragSource.addDragListener(new DragSourceAdapter() {
         @Override
         public void dragStart(DragSourceEvent event) {
            IStructuredSelection sel = listViewer.getStructuredSelection();
            if (sel.isEmpty()) {
               event.doit = false;
               return;
            }
            dragging = ((DropItem) sel.getFirstElement()).content;
         }

         @Override
         public void dragSetData(DragSourceEvent event) {
            if (DropzoneTransfer.getInstance().isSupportedType(event.dataType)) {
               event.data = dragging;
            }
            else if (TextTransfer.getInstance().isSupportedType(event.dataType)) {
               event.data = dragging;
            }
         }

         @Override
         public void dragFinished(DragSourceEvent event) {
            dragging = null;
         }
      });
   }

   private void setupDragSource_future() {
      DragSource dragSource = new DragSource(listViewer.getControl(), DND.DROP_COPY | DND.DROP_MOVE);

      dragSource.setTransfer(new Transfer[] { DropzoneTransfer.getInstance(), TextTransfer.getInstance() });

      dragSource.addDragListener(new DragSourceAdapter() {
         @Override
         public void dragStart(DragSourceEvent event) {
            IStructuredSelection sel = listViewer.getStructuredSelection();
            if (sel.isEmpty()) {
               event.doit = false;
               return;
            }
            draggingItem = (DropItem) sel.getFirstElement();
         }

         @Override
         public void dragSetData(DragSourceEvent event) {
            if (draggingItem == null) {
               event.doit = false;
               return;
            }

            if (DropzoneTransfer.getInstance().isSupportedType(event.dataType)) {
               event.data = draggingItem.payload;
            }
            else if (TextTransfer.getInstance().isSupportedType(event.dataType)) {
               event.data = draggingItem.content;
            }
         }

         @Override
         public void dragFinished(DragSourceEvent event) {
            draggingItem = null;
         }
      });
   }

   // ── Toolbar ───────────────────────────────────────────────────────────────

   private void contributeToolbar() {
      IToolBarManager tbm = getViewSite().getActionBars().getToolBarManager();

      Action addAction = new Action("Add from Editor Selection") {
         @Override
         public void run() {
            addFromEditorSelection();
         }
      };
      addAction.setToolTipText("Add the currently selected text from the active editor to the Dropzone");

      Action removeAction = new Action("Remove Selected") {
         @Override
         public void run() {
            IStructuredSelection sel = listViewer.getStructuredSelection();
            sel.forEach(o -> items.remove(o));
            listViewer.refresh();
         }
      };
      removeAction.setToolTipText("Remove the selected snippet(s) from the Dropzone");

      Action clearAction = new Action("Clear All") {
         @Override
         public void run() {
            if (!items.isEmpty() && MessageDialog.openConfirm(getSite().getShell(), "Clear Dropzone", "Remove all snippets?")) {
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
      if (window == null) {
         return;
      }

      // Use getActiveEditor() — it remembers the last active editor even after
      // the Dropzone toolbar button steals focus from the editor.
      var activeEditor = window.getActivePage().getActiveEditor();
      if (!(activeEditor instanceof ITextEditor editor)) {
         MessageDialog.openWarning(getSite().getShell(), "Dropzone", "No editor is active. Click inside an editor and select some code first.");
         return;
      }

      var sel = editor.getSelectionProvider().getSelection();
      if (!(sel instanceof ITextSelection ts) || ts.getText() == null || ts.getText().isBlank()) {
         MessageDialog.openWarning(getSite().getShell(), "Dropzone", "No text selected. Highlight some code in the editor, then click Add.");
         return;
      }
      int startLine = ts.getStartLine() + 1; // 1-based
      int endLine = ts.getEndLine() + 1; // 1-based
      String currentFilePath = getEditorFilePath(editor);

      System.out.println("[DBG] [dropzone] selected snippet lines: startLine=" + startLine + ", endLine=" + endLine);
      System.out.println("[DBG] [dropzone] current file path=" + currentFilePath);

      CloneRecord matched = findCloneRecordForSelection(currentFilePath, startLine, endLine);
      CloneRecord.CloneSource selectedSource = findSelectedSource(matched, currentFilePath, startLine, endLine);
      CloneDragPayload payload = buildDragPayload(matched, selectedSource);
      if (payload != null) {
         System.out.println("[DBG] [dropzone] payload prepared:" + " relativePath=" + payload.getRelativePath() + //
               ", extractedMethodLocation=" + payload.getExtractedMethodLocation() + ", targets=" + payload.getExtractionTargets().size());

         for (ExtractionTarget t : payload.getExtractionTargets()) {
            System.out.println("[DBG] target: " + ", startLine=" + t.getStartLine() + //
                  ", endLine=" + t.getEndLine() + ", methodName=" + t.getMethodName() + ", primary=" + t.isPrimary());
         }
      }
      else {
         System.out.println("[DBG] [dropzone] payload not prepared; snippet will be treated as generic text.");
      }
      // printSiblingCloneInstances(matched, currentFilePath, startLine, endLine);
      addSnippet(ts.getText());
   }

   private CloneDragPayload buildDragPayload(CloneRecord record, CloneRecord.CloneSource selectedSource) {
      if (record == null || selectedSource == null) {
         return null;
      }

      String relativePath = UtilClone.toProjectRelativeJavaPath(selectedSource);
      List<ExtractionTarget> extractionTargets = UtilClone.buildExtractionTargets(record);
      String extractedMethodLocation = UtilClone.inferExtractedMethodLocation(selectedSource);

      return new CloneDragPayload(record, selectedSource, relativePath, extractionTargets, extractedMethodLocation);
   }

   private String getEditorFilePath(ITextEditor editor) {
      if (editor == null || editor.getEditorInput() == null) {
         return null;
      }

      var input = editor.getEditorInput();

      if (input instanceof org.eclipse.ui.IFileEditorInput fei) {
         var loc = fei.getFile().getLocation();
         return loc != null ? loc.toOSString() : null;
      }

      return input.getToolTipText();
   }

   private CloneRecord findCloneRecordForSelection(String currentFilePath, int startLine, int endLine) {
      if (currentFilePath == null || currentFilePath.isBlank()) {
         return null;
      }

      CloneContext ctx = CloneContext.get();
      String normalizedCurrent = normalizePath(currentFilePath);

      for (CloneRecord record : ctx.recordMap.values()) {
         if (record == null || record.sources == null) {
            continue;
         }

         for (CloneRecord.CloneSource src : record.sources) {
            if (src == null || src.file == null) {
               continue;
            }

            String resolved = ctx.resolvePath(src.file);
            String normalizedSource = normalizePath(resolved != null ? resolved : src.file);

            if (!normalizedCurrent.equals(normalizedSource)) {
               continue;
            }

            int[] cloneRange = parseRange(src.range);
            if (cloneRange == null) {
               continue;
            }

            if (rangeOverlaps(startLine, endLine, cloneRange[0], cloneRange[1])) {
               return record;
            }
         }
      }

      return null;
   }

   private CloneRecord.CloneSource findSelectedSource(CloneRecord record, String currentFilePath, int startLine, int endLine) {
      if (record == null || record.sources == null || currentFilePath == null) {
         return null;
      }

      CloneContext ctx = CloneContext.get();
      String normalizedCurrent = normalizePath(currentFilePath);

      for (CloneRecord.CloneSource src : record.sources) {
         if (src == null || src.file == null) {
            continue;
         }

         String resolved = ctx.resolvePath(src.file);
         String normalizedSource = normalizePath(resolved != null ? resolved : src.file);

         if (!normalizedCurrent.equals(normalizedSource)) {
            continue;
         }

         int[] cloneRange = parseRange(src.range);
         if (cloneRange == null) {
            continue;
         }

         if (rangeOverlaps(startLine, endLine, cloneRange[0], cloneRange[1])) {
            return src;
         }
      }

      return null;
   }

   @SuppressWarnings("unused")
   private void printSiblingCloneInstances(CloneRecord record, String currentFilePath, int startLine, int endLine) {
      if (record == null) {
         System.out.println("[DBG] [dropzone] no matching clone record found for file=" + currentFilePath + " lines=" + startLine + "-" + endLine);
         return;
      }

      System.out.println("[DBG] [dropzone] matched clone classid=" + record.classid + " for file=" + currentFilePath + " lines=" + startLine + "-" + endLine);

      CloneContext ctx = CloneContext.get();
      String normalizedCurrent = normalizePath(currentFilePath);

      int siblingCount = 0;

      if (record.sources != null) {
         for (CloneRecord.CloneSource src : record.sources) {
            if (src == null || src.file == null) {
               continue;
            }

            String resolved = ctx.resolvePath(src.file);
            String normalizedSource = normalizePath(resolved != null ? resolved : src.file);

            int[] cloneRange = parseRange(src.range);
            if (cloneRange == null) {
               continue;
            }

            boolean isCurrentSelection = normalizedCurrent.equals(normalizedSource) && rangeOverlaps(startLine, endLine, cloneRange[0], cloneRange[1]);

            if (isCurrentSelection) {
               continue;
            }

            siblingCount++;
            String relativePath = UtilClone.toProjectRelativeJavaPath(src);
            List<ExtractionTarget> extractionTargets = UtilClone.buildExtractionTargets(record);
            for (ExtractionTarget t : extractionTargets) {
               System.out.println("[DBG] target: " + ", startLine=" + t.getStartLine() + //
                     ", endLine=" + t.getEndLine() + ", methodName=" + t.getMethodName() + ", primary=" + t.isPrimary());
            }
            System.out.println("[DBG] [dropzone] sibling clone #" + siblingCount + ": file=" + (relativePath != null ? relativePath : src.file) + ", startLine=" + cloneRange[0] + ", endLine=" + cloneRange[1]);
         }
      }

      if (siblingCount == 0) {
         System.out.println("[DBG] [dropzone] no sibling clone instances found.");
      }
   }

   private int[] parseRange(String range) {
      if (range == null || range.isBlank()) {
         return null;
      }

      String[] parts = range.trim().split("-");
      if (parts.length != 2) {
         return null;
      }

      try {
         int start = Integer.parseInt(parts[0].trim());
         int end = Integer.parseInt(parts[1].trim());
         return new int[] { start, end };
      } catch (NumberFormatException e) {
         return null;
      }
   }

   private boolean rangeOverlaps(int selStart, int selEnd, int cloneStart, int cloneEnd) {
      return selStart <= cloneEnd && cloneStart <= selEnd;
   }

   private String normalizePath(String path) {
      return path == null ? null : path.replace('\\', '/');
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
