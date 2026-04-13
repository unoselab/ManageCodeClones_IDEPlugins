package refactor_plugin.handlers;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
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
 * applied here and in {@link ExtractMethodWorkflow} per the README four-step pipeline.
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
            if (!allSourcesSameFileAs(record, editorFilePath)) {
                return Result.NOT_APPLICABLE;
            }

            String finalName = unifiedMethodName(record);
            List<ExtractMethodWorkflow.ExtractionTarget> targets = buildTargets(record, finalName);
            if (targets.isEmpty()) {
                return Result.NOT_APPLICABLE;
            }

            String moveAfter = resolveMoveAfterWithCaret(cu, editor,
                    buildMoveAfterLocation(record), placementSourceOffset);
            int placementOff = placementSourceOffset >= 0 ? placementSourceOffset
                    : caretOffset(editor);
            ExtractMethodWorkflow.runWorkflow(cu, targets, moveAfter, placementOff);
            ExtractMethodWorkflow.revealExtractedMethod(cu, finalName, editor);
            return Result.SUCCESS;
        } catch (Exception e) {
            MessageDialog.openError(shell, "Live Extract Method failed",
                    e.getMessage() != null ? e.getMessage() : e.toString());
            return Result.FAILED;
        }
    }

    private static String unifiedMethodName(CloneRecord record) {
        if (record.extracted_method != null
                && record.extracted_method.method_name != null
                && !record.extracted_method.method_name.isBlank()) {
            return record.extracted_method.method_name.trim();
        }
        return "extractedMethod";
    }

    /**
     * Primary = lowest line in file (surviving method). Other sites get unique temp names,
     * then merged via {@link ExtractMethodWorkflow} (delete secondaries, rebind calls).
     */
    private static List<ExtractMethodWorkflow.ExtractionTarget> buildTargets(CloneRecord record,
            String primaryName) {
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
            String name = primary ? primaryName : primaryName + "_tmp_" + i;
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
        for (CloneSource s : record.sources) {
            if (s == null || s.file == null) {
                return false;
            }
            String resolved = ctx.resolvePath(s.file).replace('\\', '/');
            if (resolved.equals(norm)) {
                continue;
            }
            String srcNorm = s.file.replace('\\', '/');
            if (norm.endsWith("/" + srcNorm)) {
                continue;
            }
            return false;
        }
        return true;
    }

    private static ICompilationUnit resolveCompilationUnit(ITextEditor editor, String editorFilePath)
            throws Exception {
        IJavaElement je = JavaUI.getEditorInputJavaElement(editor.getEditorInput());
        if (je instanceof ICompilationUnit cu) {
            return cu.getPrimary();
        }
        return findCompilationUnitForAbsolutePath(editorFilePath);
    }

    private static ICompilationUnit findCompilationUnitForAbsolutePath(String absPath) {
        if (absPath == null) {
            return null;
        }
        Path want = Paths.get(absPath).normalize();
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
