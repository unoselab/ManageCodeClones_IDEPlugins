package view;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
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

/**
 * Zest visualization of clones as a <strong>single tree</strong> (like the VS Code
 * D3 panel): root → project → clone group → source file. This avoids the old
 * “two horizontal stripes” layout from many disconnected hub→leaf pairs under
 * {@link SpringLayoutAlgorithm}.
 */
public class CloneGraphView extends ViewPart {

    public static final String ID = "view.CloneGraphView";

    private Graph  graph;
    private Label  hintLabel;
    /** 0 = tree left→right, 1 = tree top→down, 2 = spring (free) */
    private int    layoutMode;

    /** Attached to {@link GraphNode#setData(Object)} for double-click handling. */
    private static final class NodeData {
        final CloneRecord record;
        final CloneSource source;
        final String      classid;

        NodeData(CloneRecord record, CloneSource source, String classid) {
            this.record  = record;
            this.source  = source;
            this.classid = classid;
        }

        static NodeData hub(CloneRecord r) {
            return new NodeData(r, null, r.classid);
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
        hintLabel.setText("Tree: Code clones → project → group → file. "
                + "Yellow = clone group, cyan = source. Double-click a file node to open. "
                + "Toolbar: Refresh, Toggle layout.");

        graph = new Graph(container, SWT.NONE);
        graph.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        graph.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseDoubleClick(MouseEvent e) {
                Object sel = graph.getSelection().isEmpty()
                        ? null : graph.getSelection().get(0);
                if (sel instanceof GraphNode gn) {
                    Object data = gn.getData();
                    if (data instanceof NodeData nd && nd.isOpenableSite()) {
                        openSource(nd.source, nd.classid);
                    }
                }
            }
        });

        contributeToolbar();
        rebuildGraph();
        // Second pass: recordMap may be filled by startup auto-load or Clone Tree
        // after this view is created; refresh once the UI loop runs.
        getSite().getShell().getDisplay().asyncExec(() -> {
            if (graph != null && !graph.isDisposed()) {
                rebuildGraph();
            }
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
            @Override public void run() { rebuildGraph(); }
        });
        tbm.add(new Action("Toggle layout") {
            @Override public void run() {
                layoutMode = (layoutMode + 1) % 3;
                applyLayout();
            }
        });
    }

    private void applyLayout() {
        if (graph == null || graph.isDisposed()) { return; }
        switch (layoutMode) {
            case 0 -> graph.setLayoutAlgorithm(
                    new TreeLayoutAlgorithm(TreeLayoutAlgorithm.LEFT_RIGHT), true);
            case 1 -> graph.setLayoutAlgorithm(new TreeLayoutAlgorithm(), true);
            case 2 -> graph.setLayoutAlgorithm(new SpringLayoutAlgorithm(), true);
            default -> graph.setLayoutAlgorithm(
                    new TreeLayoutAlgorithm(TreeLayoutAlgorithm.LEFT_RIGHT), true);
        }
    }

    /**
     * Rebuilds a <em>connected</em> tree: synthetic root → each distinct
     * {@code record.project} → each clone hub → each source leaf.
     */
    public void rebuildGraph() {
        if (graph == null || graph.isDisposed()) { return; }

        List<?> oldNodes = new ArrayList<>(graph.getNodes());
        for (Object n : oldNodes) {
            if (n instanceof GraphNode gn) { gn.dispose(); }
        }

        Map<String, CloneRecord> map = CloneContext.get().recordMap;
        if (map.isEmpty()) {
            GraphNode empty = new GraphNode(graph, SWT.NONE);
            empty.setText("No clone data loaded\n(Load JSON in Clone Tree)");
            layoutMode = 0;
            applyLayout();
            setPartName("Clone Graph");
            return;
        }

        Display d = graph.getDisplay();
        Color cRoot   = d.getSystemColor(SWT.COLOR_GRAY);
        Color cProj   = d.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND);
        Color cHub    = d.getSystemColor(SWT.COLOR_YELLOW);
        Color cLeaf   = d.getSystemColor(SWT.COLOR_CYAN);

        List<CloneRecord> records = new ArrayList<>(map.values());
        records.sort(Comparator
                .comparing((CloneRecord r) -> r.project != null ? r.project : "")
                .thenComparing(r -> r.classid != null ? r.classid : ""));

        GraphNode root = new GraphNode(graph, SWT.NONE);
        root.setText("Code clones");
        root.setData(null);
        root.setBackgroundColor(cRoot);

        Map<String, GraphNode> projectNodes = new LinkedHashMap<>();
        for (CloneRecord r : records) {
            String pName = r.project != null && !r.project.isBlank() ? r.project : "?";
            projectNodes.computeIfAbsent(pName, name -> {
                GraphNode pn = new GraphNode(graph, SWT.NONE);
                pn.setText(name);
                pn.setData(null);
                pn.setBackgroundColor(cProj);
                new GraphConnection(graph, ZestStyles.CONNECTIONS_DIRECTED, root, pn);
                return pn;
            });
        }

        for (CloneRecord r : records) {
            if (r.sources == null || r.sources.isEmpty()) { continue; }

            String pName = r.project != null && !r.project.isBlank() ? r.project : "?";
            GraphNode proj = projectNodes.get(pName);
            if (proj == null) { continue; }

            GraphNode hub = new GraphNode(graph, SWT.NONE);
            hub.setText(hubLabel(r));
            hub.setData(NodeData.hub(r));
            hub.setBackgroundColor(cHub);
            new GraphConnection(graph, ZestStyles.CONNECTIONS_DIRECTED, proj, hub);

            for (CloneSource src : r.sources) {
                GraphNode leaf = new GraphNode(graph, SWT.NONE);
                leaf.setText(leafLabel(src));
                leaf.setData(new NodeData(r, src, r.classid));
                leaf.setBackgroundColor(cLeaf);
                new GraphConnection(graph, ZestStyles.CONNECTIONS_DIRECTED, hub, leaf);
            }
        }

        layoutMode = 0;
        applyLayout();
        setPartName("Clone Graph (" + map.size() + ")");
    }

    private static String hubLabel(CloneRecord r) {
        String type = r.refactoring_type != null ? r.refactoring_type : "clone";
        if (r.sources != null) {
            java.util.LinkedHashSet<String> methods = new java.util.LinkedHashSet<>();
            String className = null;
            for (CloneSource src : r.sources) {
                if (src.enclosing_function != null
                        && src.enclosing_function.qualified_name != null) {
                    String qn = src.enclosing_function.qualified_name;
                    int dot = qn.lastIndexOf('.');
                    methods.add(dot >= 0 ? qn.substring(dot + 1) : qn);
                    if (className == null && dot > 0) {
                        className = qn.substring(0, dot);
                    }
                }
            }
            if (!methods.isEmpty()) {
                String s = String.join(" / ", methods);
                if (className != null) {
                    return s + "\n(" + className + " · " + r.nclones + ")";
                }
                return s + "\n(" + r.nclones + " · " + type + ")";
            }
        }
        String cid = r.classid != null ? r.classid : "?";
        return cid + "\n[" + type + " · " + r.nclones + " clones]";
    }

    private static String leafLabel(CloneSource src) {
        String fname = src.file != null
                ? java.nio.file.Paths.get(src.file).getFileName().toString()
                : "?";
        String range = src.range != null ? src.range : "?";
        return fname + "\n(lines " + range + ")";
    }

    private void openSource(CloneSource src, String classid) {
        if (src.file == null || src.file.isBlank()) { return; }
        String absPath = CloneContext.get().resolvePath(src.file);

        try {
            URI        fileUri = new File(absPath).toURI();
            IFileStore store   = EFS.getLocalFileSystem().getStore(fileUri);
            IWorkbenchPage page = PlatformUI.getWorkbench()
                                            .getActiveWorkbenchWindow()
                                            .getActivePage();
            IEditorPart editor = IDE.openEditorOnFileStore(page, store);

            CloneContext.get().lastOpenedByFile.put(absPath, classid);

            if (editor instanceof ITextEditor te && src.range != null) {
                String[] parts = src.range.split("-");
                if (parts.length >= 2) {
                    IDocument doc = te.getDocumentProvider()
                                      .getDocument(te.getEditorInput());
                    if (doc != null) {
                        int startLine = Math.max(0,
                                Integer.parseInt(parts[0].trim()) - 1);
                        int endLine   = Math.min(doc.getNumberOfLines() - 1,
                                Integer.parseInt(parts[1].trim()) - 1);
                        int startOff  = doc.getLineOffset(startLine);
                        int endOff    = doc.getLineOffset(endLine)
                                      + doc.getLineLength(endLine);
                        te.selectAndReveal(startOff, endOff - startOff);
                    }
                }
            }
        } catch (Exception e) {
            MessageDialog.openError(getSite().getShell(), "Open File Error",
                    "Cannot open: " + absPath + "\n\n" + e.getMessage());
        }
    }

    @Override
    public void setFocus() {
        if (graph != null && !graph.isDisposed()) {
            graph.setFocus();
        }
    }
}
