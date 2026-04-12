package view;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.zest.core.widgets.Graph;
import org.eclipse.zest.core.widgets.GraphConnection;
import org.eclipse.zest.core.widgets.GraphNode;
import org.eclipse.zest.core.widgets.ZestStyles;
import org.eclipse.zest.layouts.algorithms.SpringLayoutAlgorithm;
import org.eclipse.zest.layouts.algorithms.TreeLayoutAlgorithm;

import refactor_plugin.model.CloneContext;
import refactor_plugin.model.CloneRecord;
import refactor_plugin.model.CloneRecord.CloneSource;

import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.jface.action.IMenuManager;

/**
 * Zest visualization of clones as a <strong>single tree</strong> (like the VS Code D3 panel) with click-to-expand levels: root → package(project) → class → clone instance.
 */
public class CloneGraphView extends ViewPart {

   public static final String ID = "view.CloneGraphView";

   private Graph graph;
   private Label hintLabel;
   /** 0 = tree left→right, 1 = tree top→down, 2 = spring (free) */
   private int layoutMode;
   private final java.util.Set<String> expandedPackages = new java.util.LinkedHashSet<>();
   private final java.util.Set<String> expandedClasses = new java.util.LinkedHashSet<>();

   private enum NodeType {
      PACKAGE, CLASS, INSTANCE
   }

   /** Attached to {@link GraphNode#setData(Object)} for double-click handling. */
   private static final class NodeData {
      final NodeType type;
      final String packageName;
      final String classKey;
      final String className;
      final CloneRecord record;
      final CloneSource source;
      final String classid;

      NodeData(NodeType type, String packageName, String classKey, String className, CloneRecord record, CloneSource source, String classid) {
         this.type = type;
         this.packageName = packageName;
         this.classKey = classKey;
         this.className = className;
         this.record = record;
         this.source = source;
         this.classid = classid;
      }

      static NodeData packageNode(String packageName) {
         return new NodeData(NodeType.PACKAGE, packageName, null, null, null, null, null);
      }

      static NodeData classNode(String packageName, String classKey, String className) {
         return new NodeData(NodeType.CLASS, packageName, classKey, className, null, null, null);
      }

      static NodeData instanceNode(CloneRecord r, CloneSource src) {
         String packageName = (r != null && r.project != null && !r.project.isBlank()) ? r.project : null;
         SourceMeta sm = SourceMeta.from(src);
         return new NodeData(NodeType.INSTANCE, packageName, null, sm.className, r, src, r.classid);
      }

      boolean isOpenableSite() {
         return source != null && source.file != null && !source.file.isBlank();
      }
   }

   @Override
   public void createPartControl(Composite parent) {
      Composite container = new Composite(parent, SWT.NONE);
      container.setLayout(new GridLayout(1, false));
      parent.setLayout(new org.eclipse.swt.layout.FillLayout());

      hintLabel = new Label(container, SWT.WRAP);
      hintLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
      hintLabel.setText("Click-to-expand tree: package(project) → class → clone instance. " + "Single-click package/class to expand or collapse. " + "Double-click an instance to open source. Toolbar: Refresh, Toggle layout.");

      graph = new Graph(container, SWT.NONE);
      graph.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
      createContextMenu();

      graph.addMouseListener(new MouseAdapter() {
         @Override
         public void mouseUp(MouseEvent e) {
            // Zest Graph selection is updated after mouse processing; run on next UI tick.
            graph.getDisplay().asyncExec(() -> {
               if (graph == null || graph.isDisposed()) {
                  return;
               }
               Object sel = graph.getSelection().isEmpty() ? null : graph.getSelection().get(0);
               if (sel instanceof GraphNode gn) {
                  Object data = gn.getData();
                  if (data instanceof NodeData nd) {
                     onNodeSingleClick(nd);
                  }
               }
            });
         }

         @Override
         public void mouseDoubleClick(MouseEvent e) {
            Object sel = graph.getSelection().isEmpty() ? null : graph.getSelection().get(0);
            if (sel instanceof GraphNode gn) {
               Object data = gn.getData();
               if (data instanceof NodeData nd && nd.isOpenableSite()) {
                  openSource(nd.source, nd.classid);
               }
            }
         }
      });

      contributeToolbar();
      contributeMainMenu();
      rebuildGraph();
      // Second pass: recordMap may be filled by startup auto-load or Clone Tree
      // after this view is created; refresh once the UI loop runs.
      getSite().getShell().getDisplay().asyncExec(() -> {
         if (graph != null && !graph.isDisposed()) {
            rebuildGraph();
         }
      });
   }

   private void createContextMenu() {
      Menu menu = new Menu(graph);
      graph.setMenu(menu);

      menu.addListener(SWT.Show, event -> {
         // Clear old items every time before showing the popup
         for (MenuItem item : menu.getItems()) {
            item.dispose();
         }

         Object sel = graph.getSelection().isEmpty() ? null : graph.getSelection().get(0);

         if (!(sel instanceof GraphNode gn)) {
            MenuItem refreshItem = new MenuItem(menu, SWT.PUSH);
            refreshItem.setText("Refresh");
            refreshItem.addListener(SWT.Selection, e -> rebuildGraph());
            return;
         }

         Object data = gn.getData();
         if (!(data instanceof NodeData nd)) {
            MenuItem refreshItem = new MenuItem(menu, SWT.PUSH);
            refreshItem.setText("Refresh");
            refreshItem.addListener(SWT.Selection, e -> rebuildGraph());
            return;
         }

         switch (nd.type) {
         case PACKAGE -> buildPackageMenu(menu, nd);
         case CLASS -> buildClassMenu(menu, nd);
         case INSTANCE -> buildInstanceMenu(menu, nd);
         }

         new MenuItem(menu, SWT.SEPARATOR);

         MenuItem refreshItem = new MenuItem(menu, SWT.PUSH);
         refreshItem.setText("Refresh");
         refreshItem.addListener(SWT.Selection, e -> rebuildGraph());
      });
   }

   private void contributeMainMenu() {
      IMenuManager mm = getViewSite().getActionBars().getMenuManager();

      mm.add(new Action("Refresh") {
         @Override
         public void run() {
            rebuildGraph();
         }
      });

      mm.add(new Action("Toggle Layout") {
         @Override
         public void run() {
            layoutMode = (layoutMode + 1) % 3;
            applyLayout();
         }
      });

      mm.add(new Action("Collapse All") {
         @Override
         public void run() {
            expandedPackages.clear();
            expandedClasses.clear();
            CloneContext.get().setGraphFocus(null, null, null);
            rebuildGraph();
         }
      });

      mm.add(new Action("Expand All") {
         @Override
         public void run() {
            expandAllNodes();
            rebuildGraph();
         }
      });

      mm.add(new Action("Open Selected Source") {
         @Override
         public void run() {
            NodeData nd = getSelectedNodeData();
            if (nd != null && nd.type == NodeType.INSTANCE && nd.isOpenableSite()) {
               openSource(nd.source, nd.classid);
            }
         }
      });
   }

   private NodeData getSelectedNodeData() {
      if (graph == null || graph.isDisposed() || graph.getSelection().isEmpty()) {
         return null;
      }
      Object sel = graph.getSelection().get(0);
      if (sel instanceof GraphNode gn) {
         Object data = gn.getData();
         if (data instanceof NodeData nd) {
            return nd;
         }
      }
      return null;
   }

   private void expandAllNodes() {
      expandedPackages.clear();
      expandedClasses.clear();

      Map<String, CloneRecord> map = CloneContext.get().recordMap;
      for (CloneRecord r : map.values()) {
         if (r.sources == null || r.sources.isEmpty()) {
            continue;
         }

         String packageName = (r.project != null && !r.project.isBlank()) ? r.project : "?";
         expandedPackages.add(packageName);

         for (CloneSource src : r.sources) {
            SourceMeta meta = SourceMeta.from(src);
            String classKey = packageName + "|" + meta.className;
            expandedClasses.add(classKey);
         }
      }
   }

   private void buildPackageMenu(Menu menu, NodeData nd) {
      boolean expanded = expandedPackages.contains(nd.packageName);

      MenuItem toggleItem = new MenuItem(menu, SWT.PUSH);
      toggleItem.setText(expanded ? "Collapse Package" : "Expand Package");
      toggleItem.addListener(SWT.Selection, e -> onNodeSingleClick(nd));

      MenuItem focusItem = new MenuItem(menu, SWT.PUSH);
      focusItem.setText("Set Focus to Package");
      focusItem.addListener(SWT.Selection, e -> {
         CloneContext.get().setGraphFocus(nd.packageName, null, null);
         rebuildGraph();
      });
   }

   private void buildClassMenu(Menu menu, NodeData nd) {
      boolean expanded = expandedClasses.contains(nd.classKey);

      MenuItem toggleItem = new MenuItem(menu, SWT.PUSH);
      toggleItem.setText(expanded ? "Collapse Class" : "Expand Class");
      toggleItem.addListener(SWT.Selection, e -> onNodeSingleClick(nd));

      MenuItem focusItem = new MenuItem(menu, SWT.PUSH);
      focusItem.setText("Set Focus to Class");
      focusItem.addListener(SWT.Selection, e -> {
         CloneContext.get().setGraphFocus(nd.packageName, nd.className, null);
         rebuildGraph();
      });
   }

   private void buildInstanceMenu(Menu menu, NodeData nd) {
      MenuItem openItem = new MenuItem(menu, SWT.PUSH);
      openItem.setText("Open Source for Extract Method");
      openItem.setEnabled(nd.isOpenableSite());
      openItem.addListener(SWT.Selection, e -> {
         if (nd.isOpenableSite()) {
            openSource(nd.source, nd.classid);
         }
      });

      MenuItem focusItem = new MenuItem(menu, SWT.PUSH);
      focusItem.setText("Set Focus to Clone Group");
      focusItem.addListener(SWT.Selection, e -> {
         CloneContext.get().setGraphFocus(nd.packageName, nd.className, nd.classid);
         rebuildGraph();
      });
   }

   /** If the Clone Graph part is open, rebuild it (e.g. after JSON load elsewhere). */
   public static void refreshIfOpen() {
      try {
         if (PlatformUI.getWorkbench() == null) {
            return;
         }
         var win = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
         if (win == null) {
            return;
         }
         IWorkbenchPage page = win.getActivePage();
         if (page == null) {
            return;
         }
         IViewPart v = page.findView(CloneGraphView.ID);
         if (v instanceof CloneGraphView cg) {
            cg.rebuildGraph();
         }
      } catch (Exception ignored) {
         /* workbench not ready */
      }
   }

   private void contributeToolbar() {
      IToolBarManager tbm = getViewSite().getActionBars().getToolBarManager();

      tbm.add(new Action("Refresh") {
         @Override
         public void run() {
            rebuildGraph();
         }
      });
      tbm.add(new Action("Toggle layout") {
         @Override
         public void run() {
            layoutMode = (layoutMode + 1) % 3;
            applyLayout();
         }
      });
   }

   private void applyLayout() {
      if (graph == null || graph.isDisposed()) {
         return;
      }
      switch (layoutMode) {
      case 0 -> graph.setLayoutAlgorithm(new TreeLayoutAlgorithm(TreeLayoutAlgorithm.LEFT_RIGHT), true);
      case 1 -> graph.setLayoutAlgorithm(new TreeLayoutAlgorithm(), true);
      case 2 -> graph.setLayoutAlgorithm(new SpringLayoutAlgorithm(), true);
      default -> graph.setLayoutAlgorithm(new TreeLayoutAlgorithm(TreeLayoutAlgorithm.LEFT_RIGHT), true);
      }
   }

   /** Rebuilds connected tree with click-based expand/collapse state. */
   public void rebuildGraph() {
      if (graph == null || graph.isDisposed()) {
         return;
      }

      List<?> oldNodes = new ArrayList<>(graph.getNodes());
      for (Object n : oldNodes) {
         if (n instanceof GraphNode gn) {
            gn.dispose();
         }
      }

      Map<String, CloneRecord> map = CloneContext.get().recordMap;
      if (map.isEmpty()) {
         GraphNode empty = new GraphNode(graph, SWT.NONE);
         empty.setText("No clone data loaded\n(Load JSON in Clone Tree)");
         applyLayout();
         setPartName("CODE CLONE VISUALIZATION (CloneViz)");
         return;
      }

      Display d = graph.getDisplay();
      Color cRoot = d.getSystemColor(SWT.COLOR_GRAY);
      Color cPackage = d.getSystemColor(SWT.COLOR_WIDGET_LIGHT_SHADOW);
      Color cClass = d.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND);
      Color cInstance = d.getSystemColor(SWT.COLOR_CYAN);

      List<CloneRecord> records = new ArrayList<>(map.values());
      records.sort(Comparator.comparing((CloneRecord r) -> r.project != null ? r.project : "").thenComparing(r -> r.classid != null ? r.classid : ""));

      GraphNode root = new GraphNode(graph, SWT.NONE);
      root.setText("Code clones");
      root.setData(null);
      root.setBackgroundColor(cRoot);

      Map<String, GraphNode> packageNodes = new LinkedHashMap<>();
      Map<String, GraphNode> classNodes = new HashMap<>();

      for (CloneRecord r : records) {
         if (r.sources == null || r.sources.isEmpty()) {
            continue;
         }
         for (CloneSource src : r.sources) {
            SourceMeta meta = SourceMeta.from(src);
            String packageName = (r.project != null && !r.project.isBlank()) ? r.project : "?";
            GraphNode pkg = packageNodes.computeIfAbsent(packageName, name -> {
               GraphNode node = new GraphNode(graph, SWT.NONE);
               node.setText(name);
               node.setData(NodeData.packageNode(name));
               node.setBackgroundColor(cPackage);
               new GraphConnection(graph, ZestStyles.CONNECTIONS_DIRECTED, root, node);
               return node;
            });

            if (!expandedPackages.contains(packageName)) {
               continue;
            }

            String classKey = packageName + "|" + meta.className;
            GraphNode cls = classNodes.computeIfAbsent(classKey, key -> {
               GraphNode node = new GraphNode(graph, SWT.NONE);
               node.setText(meta.className);
               node.setData(NodeData.classNode(packageName, classKey, meta.className));
               node.setBackgroundColor(cClass);
               new GraphConnection(graph, ZestStyles.CONNECTIONS_DIRECTED, pkg, node);
               return node;
            });

            if (!expandedClasses.contains(classKey)) {
               continue;
            }

            GraphNode leaf = new GraphNode(graph, SWT.NONE);
            leaf.setText(instanceLabel(src, r));
            leaf.setData(NodeData.instanceNode(r, src));
            leaf.setBackgroundColor(cInstance);
            new GraphConnection(graph, ZestStyles.CONNECTIONS_DIRECTED, cls, leaf);
         }
      }

      applyLayout();
      setPartName("CODE CLONE VISUALIZATION (CloneViz)");
      System.out.println("[DBG] Clone Graph (" + map.size() + ")");
   }

   private static String instanceLabel(CloneSource src, CloneRecord r) {
      String fname = src.file != null ? java.nio.file.Paths.get(src.file).getFileName().toString() : "?";
      String range = src.range != null ? src.range : "?";
      String cid = r.classid != null ? r.classid : "?";
      return fname + "\n(" + range + ", group " + cid + ")";
   }

   private void onNodeSingleClick(NodeData nd) {
      CloneContext ctx = CloneContext.get();
      if (nd.type == NodeType.PACKAGE && nd.packageName != null) {
         ctx.setGraphFocus(nd.packageName, null, null);
         if (!expandedPackages.add(nd.packageName)) {
            expandedPackages.remove(nd.packageName);
            String prefix = nd.packageName + "|";
            expandedClasses.removeIf(k -> k.startsWith(prefix));
         }
         rebuildGraph();
         return;
      }
      if (nd.type == NodeType.CLASS && nd.classKey != null) {
         ctx.setGraphFocus(nd.packageName, nd.className, null);
         if (!expandedClasses.add(nd.classKey)) {
            expandedClasses.remove(nd.classKey);
         }
         rebuildGraph();
         return;
      }
      if (nd.type == NodeType.INSTANCE) {
         ctx.setGraphFocus(nd.packageName, nd.className, nd.classid);
      }
   }

   private static final class SourceMeta {
      final String packageName;
      final String className;

      SourceMeta(String packageName, String className) {
         this.packageName = packageName;
         this.className = className;
      }

      static SourceMeta from(CloneSource src) {
         String packageName = "(default package)";
         String className = "(unknown class)";

         String qn = (src != null && src.enclosing_function != null) ? src.enclosing_function.qualified_name : null;
         if (qn != null && !qn.isBlank()) {
            String[] parts = qn.split("\\.");
            if (parts.length >= 2) {
               className = parts[parts.length - 2];
            }
            if (parts.length >= 3) {
               packageName = String.join(".", java.util.Arrays.copyOf(parts, parts.length - 2));
            }
         }

         if ((className.equals("(unknown class)") || packageName.equals("(default package)")) && src != null && src.file != null && !src.file.isBlank()) {
            java.nio.file.Path p = java.nio.file.Paths.get(src.file.replace('\\', '/'));
            java.nio.file.Path fileName = p.getFileName();
            if (fileName != null && className.equals("(unknown class)")) {
               String f = fileName.toString();
               int dot = f.lastIndexOf('.');
               className = dot > 0 ? f.substring(0, dot) : f;
            }
            String norm = src.file.replace('\\', '/');
            int idx = norm.lastIndexOf("/src/");
            if (idx >= 0) {
               String rest = norm.substring(idx + 5);
               int slash = rest.lastIndexOf('/');
               if (slash > 0 && packageName.equals("(default package)")) {
                  packageName = rest.substring(0, slash).replace('/', '.');
               }
            }
         }

         return new SourceMeta(packageName, className);
      }
   }

   private void openSource(CloneSource src, String classid) {
      if (src.file == null || src.file.isBlank()) {
         return;
      }
      String absPath = CloneContext.get().resolvePath(src.file);

      try {
         IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
         IEditorPart editor;
         IFile wsFile = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(Path.fromOSString(absPath));
         if (wsFile != null && wsFile.exists()) {
            editor = IDE.openEditor(page, wsFile, true);
         }
         else {
            URI fileUri = new File(absPath).toURI();
            IFileStore store = EFS.getLocalFileSystem().getStore(fileUri);
            editor = IDE.openEditorOnFileStore(page, store);
         }

         CloneContext.get().lastOpenedByFile.put(absPath, classid);

         if (editor instanceof ITextEditor te && src.range != null) {
            String[] parts = src.range.split("-");
            if (parts.length >= 2) {
               IDocument doc = te.getDocumentProvider().getDocument(te.getEditorInput());
               if (doc != null) {
                  int startLine = Math.max(0, Integer.parseInt(parts[0].trim()) - 1);
                  int endLine = Math.min(doc.getNumberOfLines() - 1, Integer.parseInt(parts[1].trim()) - 1);
                  int startOff = doc.getLineOffset(startLine);
                  int endOff = doc.getLineOffset(endLine) + doc.getLineLength(endLine);
                  te.selectAndReveal(startOff, endOff - startOff);
               }
            }
         }
      } catch (Exception e) {
         MessageDialog.openError(getSite().getShell(), "Open File Error", "Cannot open: " + absPath + "\n\n" + e.getMessage());
      }
   }

   @Override
   public void setFocus() {
      if (graph != null && !graph.isDisposed()) {
         graph.setFocus();
      }
   }

   public void expandAll() {
      expandAllNodes();
      rebuildGraph();
   }

   public void collapseAll() {
      expandedPackages.clear();
      expandedClasses.clear();
      CloneContext.get().setGraphFocus(null, null, null);
      rebuildGraph();
   }

   public static CloneGraphView getOpenView() {
      try {
         if (PlatformUI.getWorkbench() == null) {
            return null;
         }
         var win = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
         if (win == null) {
            return null;
         }
         IWorkbenchPage page = win.getActivePage();
         if (page == null) {
            return null;
         }
         IViewPart v = page.findView(CloneGraphView.ID);
         return (v instanceof CloneGraphView cg) ? cg : null;
      } catch (Exception e) {
         return null;
      }
   }
}
