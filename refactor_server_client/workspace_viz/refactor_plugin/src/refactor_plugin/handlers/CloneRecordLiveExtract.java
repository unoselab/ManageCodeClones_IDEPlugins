package refactor_plugin.handlers;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.texteditor.ITextEditor;

import refactor_plugin.model.CloneContext;
import refactor_plugin.model.CloneRecord;
import refactor_plugin.model.CloneRecord.CloneSource;

/**
 * Drag-and-drop / clone-aware entry point aligned with {@code README_multi_0408.md}:
 * one user gesture on <em>one</em> instance runs <strong>all</strong> same-file
 * {@link CloneRecord} ranges through {@link ExtractMethodWorkflow} (bottom-up LTK,
 * {@code IMethod.delete}, batched {@code ASTRewrite}, optional {@code ListRewrite} move).
 *
 * <p>The professor’s reference handler is not modified for this path; concepts are
 * applied here and in {@link ExtractMethodWorkflow} per the README four-step pipeline.</p>
 *
 * <p><b>Clone Graph → JSON:</b> use {@link #tryApplyLiveForClassid} with the focused
 * {@code classid} and the active Java editor; ranges come from {@code sources[].range} in
 * {@code all_refactor_results.json} (same pipeline as Dropzone drag-and-drop).</p>
 */
public final class CloneRecordLiveExtract {

    private static final Pattern RANGE_PAT = Pattern.compile("^(\\d+)-(\\d+)$");

    public enum Result {
        SUCCESS,
        NOT_APPLICABLE,
        FAILED
    }

    private CloneRecordLiveExtract() {}

    /**
     * Resolves an absolute OS path for a Java editor (workspace {@code IFile}, EFS / file-store,
     * or URI). Used by drag-drop and Clone Graph when {@code ICompilationUnit#getResource()} is
     * {@code null}.
     */
    public static String absoluteFilePathForEditor(ITextEditor editor) {
        if (editor == null) {
            return null;
        }
        var input = editor.getEditorInput();
        if (input == null) {
            return null;
        }

        try {
            java.lang.reflect.Method getFile = input.getClass().getMethod("getFile");
            Object iFile = getFile.invoke(input);
            if (iFile != null) {
                java.lang.reflect.Method getLoc = iFile.getClass().getMethod("getLocation");
                Object loc = getLoc.invoke(iFile);
                if (loc instanceof IPath p && !p.isEmpty()) {
                    return p.toOSString();
                }
            }
        } catch (Exception ignored) { /* not IFileEditorInput */ }

        IPath ipath = input.getAdapter(IPath.class);
        if (ipath != null) {
            String s = ipath.toOSString();
            if (new java.io.File(s).exists()) {
                return s;
            }
            IPath wsLoc = Platform.getLocation();
            if (wsLoc != null) {
                return wsLoc.append(ipath.makeRelative()).toOSString();
            }
            return s;
        }

        try {
            URI uri = input.getAdapter(URI.class);
            if (uri != null) {
                return Paths.get(uri).normalize().toString();
            }
        } catch (Exception ignored) { /* */ }

        try {
            java.lang.reflect.Method getUri = input.getClass().getMethod("getURI");
            Object o = getUri.invoke(input);
            if (o instanceof URI uri2 && "file".equalsIgnoreCase(uri2.getScheme())) {
                return Paths.get(uri2).normalize().toString();
            }
        } catch (Exception ignored) { /* not FileStoreEditorInput */ }

        try {
            IJavaElement je = JavaUI.getEditorInputJavaElement(editor.getEditorInput());
            if (je instanceof ICompilationUnit cu) {
                IResource res = cu.getResource();
                if (res != null && res.getLocation() != null) {
                    return res.getLocation().toOSString();
                }
                var root = ResourcesPlugin.getWorkspace().getRoot();
                if (root != null) {
                    IFile f = root.getFile(cu.getPath());
                    if (f.exists() && f.getLocation() != null) {
                        return f.getLocation().toOSString();
                    }
                }
            }
        } catch (Exception ignored) { /* */ }

        return null;
    }

    /**
     * Runs the fixed two-site ExportQuarkus demo (same ranges as Command Action 02) when the
     * editor file matches {@link ExtractMethodWorkflow#DEMO_EXPORT_QUARKUS_RELATIVE_PATH}.
     * Use this for drag-and-drop when no clone JSON matches but the user is on the demo file.
     *
     * @param placementSourceOffset document offset from drop or caret ({@code >= 0}); if
     *                                {@code < 0}, uses the live editor caret.
     */
    public static Result tryApplyExportQuarkusDemo(ITextEditor editor, Shell shell,
            String editorFilePath, int placementSourceOffset) {
        if (!ExtractMethodWorkflow.isExportQuarkusDemoWorkspacePath(editorFilePath)) {
            return Result.NOT_APPLICABLE;
        }
        try {
            ICompilationUnit cu = resolveCompilationUnit(editor, editorFilePath);
            if (cu == null) {
                return Result.NOT_APPLICABLE;
            }
            List<ExtractMethodWorkflow.ExtractionTarget> targets =
                    ExtractMethodWorkflow.exportQuarkusDemoTargets();
            String moveAfter = resolveMoveAfterWithCaret(cu, editor,
                    ExtractMethodWorkflow.DEMO_EXPORT_QUARKUS_MOVE_AFTER_LOCATION,
                    placementSourceOffset);
            int placementOff = placementSourceOffset >= 0 ? placementSourceOffset
                    : caretOffset(editor);
            ExtractMethodWorkflow.runWorkflow(cu, targets, moveAfter, placementOff);
            ExtractMethodWorkflow.revealExtractedMethod(cu, "extractedM1Block1", editor);
            return Result.SUCCESS;
        } catch (Exception e) {
            MessageDialog.openError(shell, "Live Extract Method failed",
                    e.getMessage() != null ? e.getMessage() : e.toString());
            return Result.FAILED;
        }
    }

    /**
     * {@code true} when every site in {@code record} is the same Java file as the editor
     * (including JSON {@code systems/…} vs workspace copy under another project root).
     */
    public static boolean isSameFileCloneRecordForEditor(CloneRecord record, String editorFilePath) {
        if (record == null || record.sources == null || record.sources.isEmpty()) {
            return false;
        }
        if (editorFilePath == null || editorFilePath.isBlank()) {
            return false;
        }
        return allSourcesSameFileAs(record, editorFilePath);
    }

    /**
     * Looks up {@code classid} in {@link CloneContext#get()}{@code .recordMap} and runs the
     * same live extract as drag-and-drop: {@code sources[].range} from JSON →
     * {@link ExtractMethodWorkflow#runWorkflow}. This is the programmatic counterpart to
     * choosing that clone in the graph then dropping on the editor.
     *
     * @param placementSourceOffset see {@link #tryApplyExportQuarkusDemo}
     */
    public static Result tryApplyLiveForClassid(ITextEditor editor, Shell shell, String classid,
            String editorFilePath, int placementSourceOffset) {
        if (classid == null || classid.isBlank()) {
            return Result.NOT_APPLICABLE;
        }
        CloneRecord record = CloneContext.get().recordMap.get(classid.trim());
        if (record == null) {
            return Result.NOT_APPLICABLE;
        }
        return tryApplyLive(editor, shell, record, editorFilePath, placementSourceOffset);
    }

    /**
     * @param placementSourceOffset see {@link #tryApplyExportQuarkusDemo}
     */
    public static Result tryApplyLive(ITextEditor editor, Shell shell, CloneRecord record,
            String editorFilePath, int placementSourceOffset) {
        if (record == null || record.sources == null || record.sources.isEmpty()) {
            return Result.NOT_APPLICABLE;
        }
        try {
            ICompilationUnit cu = resolveCompilationUnit(editor, editorFilePath);
            if (cu == null) {
                return Result.NOT_APPLICABLE;
            }
            // Prefer the Java model path — it can differ from Dropzone / toolbar string (URI, links).
            String pathForSameFile = editorFilePath;
            IResource resCheck = cu.getResource();
            if (resCheck != null && resCheck.getLocation() != null) {
                pathForSameFile = resCheck.getLocation().toOSString();
            }
            if (!allSourcesSameFileAs(record, pathForSameFile)) {
                return Result.NOT_APPLICABLE;
            }

            List<ExtractMethodWorkflow.ExtractionTarget> targets = buildTargets(record);
            if (targets.isEmpty()) {
                return Result.NOT_APPLICABLE;
            }

            String moveAfter = resolveMoveAfterWithCaret(cu, editor,
                    buildMoveAfterLocation(record), placementSourceOffset);
            int placementOff = placementSourceOffset >= 0 ? placementSourceOffset
                    : caretOffset(editor);
            ExtractMethodWorkflow.runWorkflow(cu, targets, moveAfter, placementOff);
            String primaryRevealName = targets.stream()
                    .filter(t -> t.isPrimary)
                    .map(t -> t.methodName)
                    .findFirst()
                    .orElse("extractedM1Block1");
            ExtractMethodWorkflow.revealExtractedMethod(cu, primaryRevealName, editor);
            return Result.SUCCESS;
        } catch (Exception e) {
            MessageDialog.openError(shell, "Live Extract Method failed",
                    e.getMessage() != null ? e.getMessage() : e.toString());
            return Result.FAILED;
        }
    }

    /**
     * Same naming as {@link ExtractMethodWorkflow#exportQuarkusDemoTargets()}: primary
     * {@code extractedM1Block1} (lowest line), further sites {@code extractedM1Block2}, …
     * then merged via {@link ExtractMethodWorkflow} (delete secondaries, rebind calls).
     */
    private static List<ExtractMethodWorkflow.ExtractionTarget> buildTargets(CloneRecord record) {
        List<CloneSourceWithRange> with = new ArrayList<>();
        for (CloneSource s : record.sources) {
            if (s == null || s.range == null) {
                continue;
            }
            int[] r = parseRangeInts(s.range);
            if (r == null) {
                continue;
            }
            with.add(new CloneSourceWithRange(r[0], r[1]));
        }
        with.sort(Comparator.comparingInt(a -> a.startLine));

        if (with.isEmpty()) {
            return List.of();
        }

        List<ExtractMethodWorkflow.ExtractionTarget> out = new ArrayList<>();
        for (int i = 0; i < with.size(); i++) {
            CloneSourceWithRange w = with.get(i);
            boolean primary = (i == 0);
            String name = "extractedM1Block" + (i + 1);
            out.add(new ExtractMethodWorkflow.ExtractionTarget(w.startLine, w.endLine, name,
                    primary));
        }
        return out;
    }

    private record CloneSourceWithRange(int startLine, int endLine) {}

    /**
     * Prefers {@code placementSourceOffset} when {@code >= 0} (Dropzone drop pixel → offset);
     * else the editor caret. Uses {@link ExtractMethodWorkflow#moveAfterLocationForCaret}; when
     * that returns {@code null} (e.g. between members, imports), uses {@code fallback}.
     */
    private static String resolveMoveAfterWithCaret(ICompilationUnit cu, ITextEditor editor,
            String fallback, int placementSourceOffset) {
        int off = placementSourceOffset >= 0 ? placementSourceOffset : caretOffset(editor);
        if (off < 0) {
            return fallback;
        }
        String fromPlacement = ExtractMethodWorkflow.moveAfterLocationForCaret(cu, off);
        return fromPlacement != null ? fromPlacement : fallback;
    }

    private static int caretOffset(ITextEditor editor) {
        if (editor == null) {
            return -1;
        }
        var sel = editor.getSelectionProvider().getSelection();
        if (sel instanceof ITextSelection ts) {
            return ts.getOffset();
        }
        return -1;
    }

    private static String buildMoveAfterLocation(CloneRecord record) {
        for (CloneSource s : record.sources) {
            if (s == null || s.enclosing_function == null) {
                continue;
            }
            String qn = s.enclosing_function.qualified_name;
            if (qn == null || qn.isBlank()) {
                continue;
            }
            int dot = qn.lastIndexOf('.');
            String simple = dot >= 0 ? qn.substring(dot + 1).trim() : qn.trim();
            if (simple.isEmpty()) {
                continue;
            }
            return "After void " + simple + "()";
        }
        return null;
    }

    private static boolean allSourcesSameFileAs(CloneRecord record, String editorAbsPath) {
        if (editorAbsPath == null) {
            return false;
        }
        String norm = editorAbsPath.replace('\\', '/');
        CloneContext ctx = CloneContext.get();
        String keyEditor = CloneContext.canonicalJavaSourceKey(norm);
        for (CloneSource s : record.sources) {
            if (s == null || s.file == null) {
                return false;
            }
            if (ctx.pathsEqualForCloneData(norm, s.file)) {
                continue;
            }
            if (keyEditor != null) {
                String resolved = ctx.resolvePath(s.file).replace('\\', '/');
                String keySrc = CloneContext.canonicalJavaSourceKey(resolved);
                if (keySrc != null && keyEditor.equals(keySrc)) {
                    continue;
                }
            }
            return false;
        }
        return true;
    }

    private static ICompilationUnit resolveCompilationUnit(ITextEditor editor, String editorFilePath)
            throws Exception {
        IJavaElement je = JavaUI.getEditorInputJavaElement(editor.getEditorInput());
        if (je instanceof ICompilationUnit cu) {
            // Keep the editor's working copy when present — offsets and LTK must match the buffer
            // the user is editing; getPrimary() can disagree and yield "selection is not valid".
            return cu;
        }
        return findCompilationUnitForAbsolutePath(editorFilePath);
    }

    private static ICompilationUnit findCompilationUnitForAbsolutePath(String absPath) {
        if (absPath == null) {
            return null;
        }
        Path want = Paths.get(absPath).normalize();

        try {
            IFile f = ResourcesPlugin.getWorkspace().getRoot()
                    .getFileForLocation(org.eclipse.core.runtime.Path.fromOSString(absPath));
            if (f != null && f.exists()) {
                IJavaElement je = JavaCore.create(f);
                if (je instanceof ICompilationUnit cu) {
                    return cu.getPrimary();
                }
            }
        } catch (Exception ignored) { /* continue */ }

        String wantKey = CloneContext.canonicalJavaSourceKey(want.toString().replace('\\', '/'));

        for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (!p.isOpen()) {
                continue;
            }
            try {
                if (!p.hasNature(JavaCore.NATURE_ID)) {
                    continue;
                }
            } catch (Exception e) {
                continue;
            }
            IJavaProject jp = JavaCore.create(p);
            try {
                for (IPackageFragmentRoot root : jp.getPackageFragmentRoots()) {
                    if (root.getKind() != IPackageFragmentRoot.K_SOURCE) {
                        continue;
                    }
                    for (IJavaElement el : root.getChildren()) {
                        if (!(el instanceof IPackageFragment pkg)) {
                            continue;
                        }
                        for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                            IResource res = cu.getResource();
                            if (res == null || res.getLocation() == null) {
                                continue;
                            }
                            Path got = Paths.get(res.getLocation().toOSString()).normalize();
                            if (want.equals(got)) {
                                return cu.getPrimary();
                            }
                            if (wantKey != null) {
                                String keyGot = CloneContext.canonicalJavaSourceKey(
                                        got.toString().replace('\\', '/'));
                                if (keyGot != null && wantKey.equals(keyGot)) {
                                    return cu.getPrimary();
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) { /* next project */ }
        }
        return null;
    }

    private static int[] parseRangeInts(String range) {
        if (range == null) {
            return null;
        }
        Matcher m = RANGE_PAT.matcher(range.trim());
        if (!m.matches()) {
            return null;
        }
        return new int[]{ Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) };
    }
}
