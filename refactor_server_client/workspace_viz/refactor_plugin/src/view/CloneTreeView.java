package view;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.texteditor.ITextEditor;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import refactor_plugin.model.CloneContext;
import refactor_plugin.model.CloneRecord;
import refactor_plugin.model.CloneRecord.CloneSource;

/**
 * Sidebar tree view that displays all clone groups.
 *
 * On startup it automatically looks for all_refactor_results.json in the
 * Eclipse workspace root (runtime-refactor_plugin/).  The toolbar "Load…"
 * button lets the user pick a different file at any time.
 *
 * File paths in the JSON are relative to the workspace root and are resolved
 * via CloneContext.resolvePath() before opening or refactoring.
 *
 * Tree shape:
 *   <project> (top level)
 *      └─ <classid>  [type · N clones]   ← double-click → expand / collapse
 *         └─ <filename>  (lines N-M)     ← double-click → open file at range
 *
 * Extract Method is applied from the Dropzone / editor drop flow only, not from this view.
 */
public class CloneTreeView extends ViewPart {

    public static final String ID        = "view.CloneTreeView";
    public static final String JSON_NAME = "all_refactor_results.json";

    private TreeViewer treeViewer;
    private Label      statusLabel;

    // ── Tree node types ───────────────────────────────────────────────────────

    interface TreeNode { String getLabel(); }

    static class RootNode implements TreeNode {
        final List<ProjectNode> children = new ArrayList<>();
        @Override public String getLabel() { return "All Code Clones"; }
    }

    static class ProjectNode implements TreeNode {
        final String name;
        final List<CloneGroupNode> children = new ArrayList<>();
        ProjectNode(String name) { this.name = name; }
        @Override public String getLabel() { return name; }
    }

    static class CloneGroupNode implements TreeNode {
        final CloneRecord      record;
        final List<SourceNode> children = new ArrayList<>();
        CloneGroupNode(CloneRecord r) { this.record = r; }
        @Override public String getLabel() {
            return record.classid + "  [" + record.refactoring_type
                    + " · " + record.nclones + " clones]";
        }
    }

    static class SourceNode implements TreeNode {
        final CloneSource src;
        final String      classid;
        SourceNode(CloneSource src, String classid) {
            this.src     = src;
            this.classid = classid;
        }
        @Override public String getLabel() {
            String fname = src.file != null
                    ? Paths.get(src.file).getFileName().toString() : "?";
            return fname + "  (lines " + src.range + ")";
        }
    }

    // ── Content provider ──────────────────────────────────────────────────────

    private static class CloneContentProvider implements ITreeContentProvider {
        @Override public Object[] getElements(Object input) {
            if (input instanceof RootNode r) { return r.children.toArray(); }
            return new Object[0];
        }
        @Override public Object[] getChildren(Object element) {
            if (element instanceof ProjectNode p)    { return p.children.toArray(); }
            if (element instanceof CloneGroupNode g) { return g.children.toArray(); }
            return new Object[0];
        }
        @Override public Object  getParent(Object element) { return null; }
        @Override public boolean hasChildren(Object element) {
            return element instanceof ProjectNode || element instanceof CloneGroupNode;
        }
    }

    // ── View lifecycle ────────────────────────────────────────────────────────

    @Override
    public void createPartControl(Composite parent) {
        // Wrap in our own composite so we control the layout independently
        // of whatever Eclipse's workbench has set on the parent.
        org.eclipse.swt.widgets.Composite container =
                new org.eclipse.swt.widgets.Composite(parent, SWT.NONE);
        container.setLayout(new GridLayout(1, false));

        // Fill the parent (Eclipse usually gives us a FillLayout or no layout)
        parent.setLayout(new org.eclipse.swt.layout.FillLayout());

        statusLabel = new Label(container, SWT.WRAP);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        setStatus("Searching for " + JSON_NAME + " …");

        treeViewer = new TreeViewer(container, SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        treeViewer.getControl().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        treeViewer.setContentProvider(new CloneContentProvider());
        treeViewer.setLabelProvider(new LabelProvider() {
            @Override public String getText(Object element) {
                return element instanceof TreeNode n ? n.getLabel() : super.getText(element);
            }
        });

        treeViewer.addDoubleClickListener(event -> {
            IStructuredSelection sel = treeViewer.getStructuredSelection();
            if (sel.isEmpty()) { return; }
            Object node = sel.getFirstElement();

            if (node instanceof SourceNode sn) {
                openSourceFile(sn);
            } else {
                treeViewer.setExpandedState(node, !treeViewer.getExpandedState(node));
            }
        });

        contributeToolbar();

        // Auto-load all_refactor_results.json from the workspace root
        getSite().getShell().getDisplay().asyncExec(this::autoLoad);
    }

    private void setStatus(String msg) {
        if (statusLabel != null && !statusLabel.isDisposed()) {
            statusLabel.setText(msg);
            // Relayout the container (the Composite we own, not Eclipse's parent)
            statusLabel.getParent().layout(true, true);
        }
    }

    // ── Toolbar ───────────────────────────────────────────────────────────────

    private void contributeToolbar() {
        IToolBarManager tbm = getViewSite().getActionBars().getToolBarManager();

        Action loadAction = new Action("Load Clone Data…") {
            @Override public void run() { browseAndLoad(); }
        };
        loadAction.setToolTipText("Open all_refactor_results.json (auto-loaded from workspace root on startup)");
        tbm.add(loadAction);
    }

    // ── Auto-load from workspace root ─────────────────────────────────────────

    /**
     * Tries to locate all_refactor_results.json automatically.
     * Checks the Eclipse workspace root first, then a fixed known path as fallback.
     * Shows a clear status message in the view so the user always knows what happened.
     */
    private void autoLoad() {
        // Do NOT use ResourcesPlugin here — it may throw NoClassDefFoundError
        // if org.eclipse.core.resources hasn't fully activated yet.
        // Instead, probe the filesystem directly using known absolute paths.
        try {
            String base = "/Users/dreamxia/2025_Dr.Song/ManageCodeClones_IDEPlugins"
                        + "/refactor_server_client/runtime-refactor_plugin";

            // Check every likely location for the JSON file
            String[][] candidates = {
                // { jsonPath, workspaceRoot }
                { base + "/systems/" + JSON_NAME, base },
                { base + "/" + JSON_NAME,          base },
            };

            for (String[] pair : candidates) {
                File f = new File(pair[0]);
                if (f.exists()) {
                    setStatus("Auto-loading: " + f.getAbsolutePath());
                    loadFromPath(f.getAbsolutePath(), pair[1]);
                    return;
                }
            }

            setStatus("'" + JSON_NAME + "' not found automatically.\n"
                    + "Checked:\n"
                    + "  " + base + "/systems/" + JSON_NAME + "\n"
                    + "  " + base + "/" + JSON_NAME + "\n\n"
                    + "Click 'Load Clone Data…' to pick it manually.");

        } catch (Throwable t) {
            // Catch Throwable (not just Exception) to handle NoClassDefFoundError etc.
            setStatus("Auto-load error: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage()
                    + "\n\nClick 'Load Clone Data…' to load manually.");
        }
    }

    // ── Manual load via FileDialog ────────────────────────────────────────────

    private void browseAndLoad() {
        // Use the active shell rather than getSite().getShell() to avoid
        // the dialog appearing behind other windows on macOS.
        org.eclipse.swt.widgets.Shell shell =
                org.eclipse.swt.widgets.Display.getDefault().getActiveShell();
        if (shell == null) { shell = getSite().getShell(); }

        FileDialog dlg = new FileDialog(shell, SWT.OPEN);
        dlg.setText("Open all_refactor_results.json");
        dlg.setFilterNames(new String[]{"JSON files", "All files"});
        dlg.setFilterExtensions(new String[]{"*.json", "*.*"});

        // Pre-open to the known workspace root
        dlg.setFilterPath("/Users/dreamxia/2025_Dr.Song/ManageCodeClones_IDEPlugins"
                + "/refactor_server_client/runtime-refactor_plugin");

        setStatus("Opening file chooser …");
        String path = dlg.open();
        if (path == null) {
            setStatus("File selection cancelled. Use 'Load Clone Data…' to pick the file.");
            return;
        }

        // Base dir for resolving relative source paths is always runtime-refactor_plugin/
        String base = "/Users/dreamxia/2025_Dr.Song/ManageCodeClones_IDEPlugins"
                    + "/refactor_server_client/runtime-refactor_plugin";
        loadFromPath(path, base);
    }

    // ── Core load logic ───────────────────────────────────────────────────────

    /**
     * Parses the JSON at {@code jsonPath}, stores records in CloneContext, and
     * populates the tree.
     *
     * @param jsonPath  absolute path to the JSON file
     * @param baseDir   directory used to resolve relative file paths in the JSON
     */
    private void loadFromPath(String jsonPath, String baseDir) {
        try (FileReader reader = new FileReader(jsonPath)) {
            Gson gson = new Gson();
            Type listType = new TypeToken<List<CloneRecord>>() {}.getType();
            List<CloneRecord> records = gson.fromJson(reader, listType);

            if (records == null || records.isEmpty()) {
                String msg = "Parsed 0 records from:\n" + jsonPath
                           + "\n\nCheck the file is a valid JSON array of clone records.";
                setStatus(msg);
                MessageDialog.openWarning(getSite().getShell(), "Empty Data", msg);
                return;
            }

            // Confirm parse success before touching the tree
            MessageDialog.openInformation(getSite().getShell(), "Clone Data Parsed",
                    "Parsed " + records.size() + " clone groups.\n"
                    + "Base dir: " + baseDir + "\n"
                    + "First classid: " + records.get(0).classid);

            CloneContext ctx = CloneContext.get();
            ctx.recordMap.clear();
            ctx.workspaceRoot = baseDir;
            for (CloneRecord r : records) { ctx.recordMap.put(r.classid, r); }

            RootNode root = buildTree(records);

            treeViewer.setInput(root);
            treeViewer.refresh();
            treeViewer.expandToLevel(2);
            treeViewer.getControl().getParent().layout(true, true);

            setPartName("Clone Tree  (" + records.size() + ")");
            setStatus("Loaded " + records.size() + " clone groups  |  base: " + baseDir);

        } catch (Exception e) {
            String msg = "Failed to parse:\n" + jsonPath + "\n\n"
                       + e.getClass().getSimpleName() + ": " + e.getMessage();
            setStatus(msg);
            MessageDialog.openError(getSite().getShell(), "Load Error", msg);
        }
    }

    // ── Build in-memory tree ──────────────────────────────────────────────────

    private static RootNode buildTree(List<CloneRecord> records) {
        RootNode root = new RootNode();
        Map<String, ProjectNode> projectMap = new LinkedHashMap<>();

        for (CloneRecord r : records) {
            ProjectNode project = projectMap.computeIfAbsent(r.project, ProjectNode::new);
            CloneGroupNode group = new CloneGroupNode(r);
            for (CloneSource src : r.sources) {
                group.children.add(new SourceNode(src, r.classid));
            }
            project.children.add(group);
        }

        root.children.addAll(projectMap.values());
        return root;
    }

    // ── Open source file at clone range ──────────────────────────────────────

    private void openSourceFile(SourceNode node) {
        if (node.src.file == null || node.src.file.isBlank()) { return; }

        // Resolve relative path against workspace root
        String absPath = CloneContext.get().resolvePath(node.src.file);

        try {
            URI        fileUri = new File(absPath).toURI();
            IFileStore store   = EFS.getLocalFileSystem().getStore(fileUri);
            IWorkbenchPage page = PlatformUI.getWorkbench()
                                            .getActiveWorkbenchWindow()
                                            .getActivePage();
            IEditorPart editor = IDE.openEditorOnFileStore(page, store);

            // Track which clone group this file was opened from (for drag-drop)
            CloneContext.get().lastOpenedByFile.put(absPath, node.classid);

            // Highlight the clone range in the editor
            if (editor instanceof ITextEditor te && node.src.range != null) {
                String[] parts = node.src.range.split("-");
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
        treeViewer.getControl().setFocus();
    }
}
