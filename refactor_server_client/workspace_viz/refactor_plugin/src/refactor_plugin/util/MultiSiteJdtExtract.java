package refactor_plugin.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.internal.corext.refactoring.code.ExtractMethodRefactoring;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.texteditor.ITextEditor;

import refactor_plugin.model.CloneContext;
import refactor_plugin.model.CloneRecord;
import refactor_plugin.model.CloneRecord.CloneSource;

/**
 * JDT/LTK extract for one or many clone ranges in a single {@link ICompilationUnit},
 * then unify invocations and drop duplicate extracted methods. Used by the menu handler
 * and by drag-and-drop when JSON describes same-file Java clones.
 * <p>
 * For <strong>multiple</strong> sites in one compilation unit, the pipeline matches the
 * multi-selection workflow Eclipse documents: apply Extract Method at each clone range with a
 * <em>different</em> temporary method name; rewrite invocations so every site calls one unified
 * name; delete the extra extracted method declarations; then rename the remaining declaration to
 * the final {@code unifiedMethodName}.
 */
public final class MultiSiteJdtExtract {

    private static final Pattern RANGE_PAT = Pattern.compile("^(\\d+)-(\\d+)$");

    public static final String TEMP_PREFIX = "__cloneExtractTmp";

    public record Result(boolean ok, String title, String detail) {}

    /** Outcome of a single-site {@link ExtractMethodRefactoring} run (with JDT message on failure). */
    public record SingleJdtExtractResult(boolean ok, String errorDetail) {}

    private MultiSiteJdtExtract() {}

    /**
     * True when every {@link CloneSource} maps to {@code editorAbsPath} and has a parseable
     * {@code range} (same-file clone group suitable for multi-site JDT).
     */
    public static boolean isSameFileJavaCloneForEditor(CloneRecord record,
            String editorAbsPath) {
        if (record == null || record.sources == null || editorAbsPath == null) {
            return false;
        }
        CloneContext ctx = CloneContext.get();
        for (CloneSource s : record.sources) {
            if (s.file == null || s.range == null || s.range.isBlank()) {
                return false;
            }
            if (parseLineRange(s.range) == null) {
                return false;
            }
            if (!ClonePathMatch.editorMatchesSourceFile(editorAbsPath, s.file, ctx)) {
                return false;
            }
        }
        return true;
    }

    /** Unified method name from JSON or a default. */
    public static String unifiedNameFromRecord(CloneRecord record) {
        if (record.extracted_method != null
                && record.extracted_method.method_name != null
                && !record.extracted_method.method_name.isBlank()) {
            return record.extracted_method.method_name.trim();
        }
        return "extractedMethod";
    }

    public static List<int[]> lineRangesFromRecord(CloneRecord record) {
        List<int[]> out = new ArrayList<>();
        if (record.sources == null) {
            return out;
        }
        for (CloneSource s : record.sources) {
            int[] r = parseLineRange(s.range);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * Runs single- or multi-site extract for a loaded clone record (caller must ensure
     * {@link #isSameFileJavaCloneForEditor}).
     */
    public static Result applyForCloneRecord(ICompilationUnit cu, CloneRecord record) {
        try {
            String unified = unifiedNameFromRecord(record);
            List<int[]> ranges = lineRangesFromRecord(record);
            if (ranges.isEmpty()) {
                return new Result(false, "No ranges", "Clone record has no parseable sources[].range.");
            }
            if (ranges.size() == 1) {
                int[] r = ranges.get(0);
                SingleJdtExtractResult xr = applySingleJdtExtract(cu, r[0], r[1], unified);
                if (!xr.ok()) {
                    return new Result(false, "Extract Method failed",
                            "JDT could not extract lines " + r[0] + "-" + r[1] + ".\n"
                                    + (xr.errorDetail() != null ? xr.errorDetail() : ""));
                }
                cu.reconcile(ICompilationUnit.NO_AST, false, null, null);
                return new Result(true, "Extract Method",
                        "Single-site JDT extract as " + unified + "().");
            }
            return applyMultiSite(cu, ranges, unified);
        } catch (Exception e) {
            return new Result(false, "Extract Method failed", e.getMessage());
        }
    }

    /**
     * Explicit line ranges (1-based inclusive) and final unified method name. Used by the Command
     * Action 02 menu, Dropzone on the demo file, and JSON-driven same-file clones.
     * <p>
     * When {@code ranges.size() > 1}, runs the internal multi-site pipeline (temporary names per
     * site, unify calls, remove duplicate declarations, rename once).
     */
    public static Result applyWithLineRanges(ICompilationUnit cu, List<int[]> ranges,
            String unifiedMethodName) {
        try {
            if (ranges == null || ranges.isEmpty()) {
                return new Result(false, "No ranges", "No line ranges provided.");
            }
            if (ranges.size() == 1) {
                int[] r = ranges.get(0);
                SingleJdtExtractResult xr = applySingleJdtExtract(cu, r[0], r[1],
                        unifiedMethodName);
                if (!xr.ok()) {
                    return new Result(false, "Extract Method failed",
                            "JDT could not extract lines " + r[0] + "-" + r[1] + ".\n"
                                    + (xr.errorDetail() != null ? xr.errorDetail() : ""));
                }
                cu.reconcile(ICompilationUnit.NO_AST, false, null, null);
                return new Result(true, "Done", "Extracted as " + unifiedMethodName + "().");
            }
            return applyMultiSite(cu, new ArrayList<>(ranges), unifiedMethodName);
        } catch (Exception e) {
            return new Result(false, "Extract Method failed", e.getMessage());
        }
    }

    private static Result applyMultiSite(ICompilationUnit cu, List<int[]> ranges,
            String unifiedMethodName) throws Exception {
        ranges.sort(Comparator.comparingInt((int[] a) -> a[0]).reversed());

        for (int i = 0; i < ranges.size(); i++) {
            int[] r = ranges.get(i);
            String tempName = TEMP_PREFIX + i;
            SingleJdtExtractResult xr = applySingleJdtExtract(cu, r[0], r[1], tempName);
            if (!xr.ok()) {
                return new Result(false, "Extract failed",
                        "JDT extract failed at lines " + r[0] + "-" + r[1]
                                + " (temp " + tempName + ").\n"
                                + (xr.errorDetail() != null ? xr.errorDetail() : ""));
            }
            cu.reconcile(ICompilationUnit.NO_AST, false, null, null);
        }

        String src = cu.getSource();
        if (src == null) {
            return new Result(false, "Error", "No source on compilation unit.");
        }

        for (int i = 1; i < ranges.size(); i++) {
            src = replaceMethodInvocationsOnly(src, TEMP_PREFIX + i, TEMP_PREFIX + 0);
        }
        applySourceAndReconcile(cu, src);

        for (int i = 1; i < ranges.size(); i++) {
            IMethod m = findMethodInCu(cu, TEMP_PREFIX + i);
            if (m != null && m.exists()) {
                m.delete(true, new NullProgressMonitor());
            }
            cu.reconcile(ICompilationUnit.NO_AST, false, null, null);
        }

        src = cu.getSource();
        src = replaceMethodInvocationsOnly(src, TEMP_PREFIX + 0, unifiedMethodName);
        src = renameVoidMethodDeclaration(src, TEMP_PREFIX + 0, unifiedMethodName);
        applySourceAndReconcile(cu, src);

        return new Result(true, "Done",
                "Multi-site JDT extract: " + ranges.size() + " site(s) as "
                        + unifiedMethodName + "().");
    }

    public static ICompilationUnit findCompilationUnitForAbsolutePath(String absPath) {
        if (absPath == null) {
            return null;
        }
        IWorkspace ws = ResourcesPlugin.getWorkspace();
        java.nio.file.Path want = java.nio.file.Paths.get(absPath).normalize();
        java.nio.file.Path wantReal = tryRealPath(want);
        String wantKey = ClonePathMatch.canonicalJavaPathKey(absPath.replace('\\', '/'));

        ICompilationUnit keyMatch = null;
        for (IProject p : ws.getRoot().getProjects()) {
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
                            if (res == null) {
                                continue;
                            }
                            var loc = res.getLocation();
                            if (loc == null) {
                                continue;
                            }
                            java.nio.file.Path got = java.nio.file.Paths.get(loc.toOSString())
                                    .normalize();
                            if (want.equals(got)) {
                                return cu;
                            }
                            java.nio.file.Path gotReal = tryRealPath(got);
                            if (wantReal != null && gotReal != null && wantReal.equals(gotReal)) {
                                return cu;
                            }
                            if (wantKey != null && keyMatch == null) {
                                String gotKey = ClonePathMatch.canonicalJavaPathKey(
                                        loc.toOSString().replace('\\', '/'));
                                if (wantKey.equals(gotKey)) {
                                    keyMatch = cu;
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) { /* next project */ }
        }
        return keyMatch;
    }

    private static java.nio.file.Path tryRealPath(java.nio.file.Path p) {
        try {
            return p.toRealPath();
        } catch (Exception e) {
            return null;
        }
    }

    public static void revealMethodInEditor(ICompilationUnit cu, String methodName)
            throws Exception {
        revealMethodInEditor(cu, methodName, null);
    }

    /**
     * Reveals the extracted method in the workbench. When {@code reuseIfSameFile} already shows
     * this {@code ICompilationUnit}, that editor is reused (no {@link JavaUI#openInEditor});
     * avoids spawning a second Java editor / OS file-handler prompts on drop refactor.
     */
    public static void revealMethodInEditor(ICompilationUnit cu, String methodName,
            ITextEditor reuseIfSameFile) throws Exception {
        IMethod extractedMethod = findMethodInCu(cu, methodName);
        if (extractedMethod == null || !extractedMethod.exists()) {
            return;
        }
        IEditorPart editor;
        if (reuseIfSameFile != null && editorCoversCompilationUnit(reuseIfSameFile, cu)) {
            editor = reuseIfSameFile;
        } else {
            editor = JavaUI.openInEditor(cu);
        }
        JavaUI.revealInEditor(editor, (IJavaElement) extractedMethod);
        if (editor instanceof ITextEditor textEditor) {
            if (extractedMethod.getNameRange() != null) {
                textEditor.selectAndReveal(extractedMethod.getNameRange().getOffset(),
                        extractedMethod.getNameRange().getLength());
            } else {
                textEditor.selectAndReveal(extractedMethod.getSourceRange().getOffset(), 0);
            }
        }
    }

    private static boolean editorCoversCompilationUnit(ITextEditor ed, ICompilationUnit cu) {
        if (ed == null || cu == null) {
            return false;
        }
        try {
            IJavaElement je = JavaUI.getEditorInputJavaElement(ed.getEditorInput());
            if (je instanceof ICompilationUnit edCu) {
                if (edCu.getPrimary().getPath().equals(cu.getPrimary().getPath())) {
                    return true;
                }
            }
            var input = ed.getEditorInput();
            if (input instanceof IFileEditorInput fei) {
                IFile f = fei.getFile();
                IResource res = cu.getResource();
                if (f != null && res != null && f.getLocation() != null
                        && res.getLocation() != null
                        && f.getLocation().equals(res.getLocation())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            /* fall through */
        }
        return false;
    }

    /**
     * 1-based inclusive start/end lines for the half-open offset range
     * {@code [startOffset, endOffsetExclusive)} in the editor document (must match
     * the {@link ICompilationUnit} buffer after reconcile).
     */
    public static int[] oneBasedInclusiveLinesForRange(IDocument doc, int startOffset,
            int endOffsetExclusive) throws BadLocationException {
        if (endOffsetExclusive <= startOffset) {
            int line = doc.getLineOfOffset(startOffset) + 1;
            return new int[]{ line, line };
        }
        int len = doc.getLength();
        int last = endOffsetExclusive - 1;
        if (last >= len) {
            last = Math.max(startOffset, len - 1);
        }
        int startLine = doc.getLineOfOffset(startOffset) + 1;
        int endLine = doc.getLineOfOffset(last) + 1;
        return new int[]{ startLine, endLine };
    }

    /**
     * Runs single-site Extract Method; on failure {@link SingleJdtExtractResult#errorDetail()}
     * carries the JDT {@link RefactoringStatus} message (also printed to stderr).
     */
    public static SingleJdtExtractResult applySingleJdtExtract(ICompilationUnit cu,
            int startLine, int endLine, String methodName) throws Exception {
        SourceRange sourceRange = computeSourceRange(cu, startLine, endLine);
        ExtractMethodRefactoring refactoring = new ExtractMethodRefactoring(cu,
                sourceRange.offset, sourceRange.length);
        refactoring.setMethodName(methodName);
        refactoring.setReplaceDuplicates(false);

        RefactoringStatus initialStatus = refactoring.checkInitialConditions(
                new NullProgressMonitor());
        if (initialStatus.hasError() || initialStatus.hasFatalError()) {
            String msg = getStatusMessage(initialStatus);
            System.err.println("[MultiSiteJdtExtract] initial: " + msg);
            return new SingleJdtExtractResult(false, msg);
        }
        RefactoringStatus finalStatus = refactoring.checkFinalConditions(
                new NullProgressMonitor());
        if (finalStatus.hasError() || finalStatus.hasFatalError()) {
            String msg = getStatusMessage(finalStatus);
            System.err.println("[MultiSiteJdtExtract] final: " + msg);
            return new SingleJdtExtractResult(false, msg);
        }
        Change change = refactoring.createChange(new NullProgressMonitor());
        change.perform(new NullProgressMonitor());
        return new SingleJdtExtractResult(true, null);
    }

    /** @see #applySingleJdtExtract */
    public static boolean applySingleExtractQuiet(ICompilationUnit cu, int startLine,
            int endLine, String methodName) throws Exception {
        return applySingleJdtExtract(cu, startLine, endLine, methodName).ok();
    }

    private static IMethod findMethodInCu(ICompilationUnit cu, String name) throws Exception {
        for (IType type : cu.getAllTypes()) {
            for (IMethod method : type.getMethods()) {
                if (name.equals(method.getElementName())) {
                    return method;
                }
            }
        }
        return null;
    }

    private static String replaceMethodInvocationsOnly(String source, String oldName,
            String newName) {
        String needle = oldName + "(";
        StringBuilder out = new StringBuilder(source.length() + 32);
        int from = 0;
        while (true) {
            int idx = source.indexOf(needle, from);
            if (idx < 0) {
                out.append(source.substring(from));
                break;
            }
            out.append(source, from, idx);
            if (isVoidMethodDeclarationBefore(source, idx)) {
                out.append(needle);
            } else {
                out.append(newName).append("(");
            }
            from = idx + needle.length();
        }
        return out.toString();
    }

    private static boolean isVoidMethodDeclarationBefore(String source, int nameIdx) {
        int lineStart = source.lastIndexOf('\n', nameIdx - 1);
        lineStart++;
        if (nameIdx <= lineStart) {
            return false;
        }
        String before = source.substring(lineStart, nameIdx);
        int voidPos = before.lastIndexOf("void");
        if (voidPos < 0) {
            return false;
        }
        String gap = before.substring(voidPos + 4);
        return gap.trim().isEmpty();
    }

    private static String renameVoidMethodDeclaration(String source, String oldName,
            String newName) {
        String s = source.replace("static void " + oldName + "(",
                "static void " + newName + "(");
        return s.replace("void " + oldName + "(", "void " + newName + "(");
    }

    private static void applySourceAndReconcile(ICompilationUnit cu, String newSource)
            throws Exception {
        IBuffer buf = cu.getBuffer();
        if (buf == null) {
            throw new IllegalStateException("No buffer for compilation unit");
        }
        buf.setContents(newSource);
        cu.reconcile(ICompilationUnit.NO_AST, false, null, null);
    }

    private static SourceRange computeSourceRange(ICompilationUnit cu, int startLine,
            int endLine) throws Exception {
        String source = cu.getSource();
        int startOffset = getLineStartOffset(source, startLine);
        int endOffset = getLineEndOffset(source, endLine);
        return new SourceRange(startOffset, endOffset - startOffset);
    }

    /**
     * Text from {@code source} for 1-based inclusive line numbers (same rules as extract;
     * {@code source} must use {@code \n} line breaks as in {@link ICompilationUnit#getSource()}).
     */
    public static String sourceTextAtLines(String source, int startLine1,
            int endLine1Inclusive) {
        if (source == null || startLine1 < 1 || endLine1Inclusive < startLine1) {
            return null;
        }
        String src = source.replace("\r\n", "\n").replace('\r', '\n');
        try {
            int s = getLineStartOffset(src, startLine1);
            int e = getLineEndOffset(src, endLine1Inclusive);
            return src.substring(s, e);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Line endings + trim trailing spaces per line (for comparing clone bodies). */
    public static String normalizeForCloneCompare(String text) {
        if (text == null) {
            return "";
        }
        String n = text.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = n.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(lines[i].stripTrailing());
            if (i + 1 < lines.length) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * After {@link #normalizeForCloneCompare}, removes the longest common leading whitespace
     * prefix from every non-blank line so Dropzone / editor copies match the golden snippet even
     * when overall indentation differs (e.g. 4 vs 8 spaces).
     */
    public static String indentInvariantFormForCloneCompare(String text) {
        String n = normalizeForCloneCompare(text);
        if (n.isEmpty()) {
            return n;
        }
        String[] lines = n.split("\n", -1);
        int minLead = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            int k = 0;
            while (k < line.length() && (line.charAt(k) == ' ' || line.charAt(k) == '\t')) {
                k++;
            }
            if (k == line.length()) {
                continue;
            }
            minLead = Math.min(minLead, k);
        }
        if (minLead == Integer.MAX_VALUE) {
            minLead = 0;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            sb.append(line.length() <= minLead ? "" : line.substring(minLead));
        }
        return sb.toString();
    }

    private static volatile String indentInvariantDemoSnippetCache;

    private static String indentInvariantDemoSnippetRef() {
        if (indentInvariantDemoSnippetCache == null) {
            indentInvariantDemoSnippetCache = indentInvariantFormForCloneCompare(
                    ExtractMethodHandlerDemo.DEMO_CLONE_SNIPPET);
        }
        return indentInvariantDemoSnippetCache;
    }

    /**
     * True when {@code body} is the Command Action 02 demo clone block (same normalized text as
     * {@link ExtractMethodHandlerDemo#SAME_FILE_CLONE_RANGES} in the reference snapshot), even if
     * that block appears at different 1-based line numbers in the open file.
     */
    public static boolean matchesDemoCloneBody(String body) {
        if (body == null) {
            return false;
        }
        String ib = indentInvariantFormForCloneCompare(body);
        return !ib.isEmpty() && ib.equals(indentInvariantDemoSnippetRef());
    }

    /**
     * Finds every 1-based inclusive [start,end] where a window of
     * {@link ExtractMethodHandlerDemo#DEMO_CLONE_LINE_SPAN} lines equals the demo clone (after
     * {@link #indentInvariantFormForCloneCompare}), in source order. Used when fixed menu line
     * numbers do not match the workspace copy (extra preamble, different project layout).
     */
    public static List<int[]> discoverDemoCloneLineRanges(String fullSource) {
        List<int[]> found = new ArrayList<>();
        if (fullSource == null) {
            return found;
        }
        String g = indentInvariantDemoSnippetRef();
        if (g.isEmpty()) {
            return found;
        }
        String src = fullSource.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = src.split("\n", -1);
        int h = ExtractMethodHandlerDemo.DEMO_CLONE_LINE_SPAN;
        for (int i = 0; i + h <= lines.length; i++) {
            StringBuilder win = new StringBuilder();
            for (int j = 0; j < h; j++) {
                if (j > 0) {
                    win.append('\n');
                }
                win.append(lines[i + j]);
            }
            if (indentInvariantFormForCloneCompare(win.toString()).equals(g)) {
                found.add(new int[]{ i + 1, i + h });
            }
        }
        return found;
    }

    /**
     * When both windows in {@link ExtractMethodHandlerDemo#SAME_FILE_CLONE_RANGES} still contain
     * the demo clone in {@code fullSource} (indent-invariant), returns those 1-based inclusive
     * pairs so JDT uses the original reference lines (e.g. 316\u2013335 / 476\u2013495) instead of
     * another discovered match order.
     */
    public static List<int[]> demoCloneRangesPreferReference(String fullSource) {
        if (fullSource == null) {
            return null;
        }
        String g = indentInvariantDemoSnippetRef();
        if (g.isEmpty()) {
            return null;
        }
        List<int[]> out = new ArrayList<>();
        for (int[] site : ExtractMethodHandlerDemo.SAME_FILE_CLONE_RANGES) {
            String canon = sourceTextAtLines(fullSource, site[0], site[1]);
            if (canon == null || !indentInvariantFormForCloneCompare(canon).equals(g)) {
                return null;
            }
            out.add(new int[]{ site[0], site[1] });
        }
        return out;
    }

    /**
     * Same 1-based inclusive line pairs as {@link refactor_plugin.handlers.ExtractMethodHandler}
     * and the ExportQuarkus Dropzone path: prefer reference windows when they
     * still match {@code source}, else first two discovered sites, else
     * {@link ExtractMethodHandlerDemo#SAME_FILE_CLONE_RANGES}. Logs the branch to the console.
     */
    public static List<int[]> resolveExportQuarkusDemoCloneRanges(String source) {
        if (source == null) {
            return new ArrayList<>();
        }
        List<int[]> ranges = demoCloneRangesPreferReference(source);
        if (ranges != null) {
            System.out.println("[refactor_plugin] Command Action 02 demo: reference windows still "
                    + "match file; using original 1-based ranges "
                    + Arrays.deepToString(ranges.toArray(new int[0][])));
            return ranges;
        }
        ranges = discoverDemoCloneLineRanges(source);
        if (ranges.size() < 2) {
            ranges = new ArrayList<>();
            for (int[] site : ExtractMethodHandlerDemo.SAME_FILE_CLONE_RANGES) {
                ranges.add(new int[]{ site[0], site[1] });
            }
            System.out.println("[refactor_plugin] Command Action 02 demo: <2 discovered clone "
                    + "windows; using fixed ranges "
                    + Arrays.deepToString(ExtractMethodHandlerDemo.SAME_FILE_CLONE_RANGES));
        } else {
            ranges = new ArrayList<>(ranges.subList(0, 2));
            System.out.println("[refactor_plugin] Command Action 02 demo: using discovered ranges "
                    + Arrays.deepToString(ranges.toArray(new int[0][])));
        }
        return ranges;
    }

    /**
     * 1-based inclusive start/end line for the half-open offset range
     * {@code [startInclusive, endExclusive)} in {@code source} (counts only {@code '\n'} as line
     * breaks, consistent with {@link #sourceTextAtLines}).
     */
    public static int[] oneBasedInclusiveLinesForOffsetRange(String source, int startInclusive,
            int endExclusive) {
        if (source == null || startInclusive < 0 || endExclusive < startInclusive
                || endExclusive > source.length()) {
            return null;
        }
        int startLine = 1;
        for (int i = 0; i < startInclusive; i++) {
            if (source.charAt(i) == '\n') {
                startLine++;
            }
        }
        int endLine = startLine;
        for (int i = startInclusive; i < endExclusive; i++) {
            if (source.charAt(i) == '\n') {
                endLine++;
            }
        }
        return new int[]{ startLine, endLine };
    }

    /**
     * True if {@code body} is the same text (after {@link #indentInvariantFormForCloneCompare}) as
     * any Command Action 02 clone window: fixed {@link ExtractMethodHandlerDemo#SAME_FILE_CLONE_RANGES}
     * or any run discovered in {@code fullSource}.
     */
    public static boolean droppedTextMatchesDemoCloneWindows(String fullSource, String body) {
        if (body == null || fullSource == null) {
            return false;
        }
        if (matchesDemoCloneBody(body)) {
            return true;
        }
        String nb = indentInvariantFormForCloneCompare(body);
        if (nb.isEmpty()) {
            return false;
        }
        for (int[] site : ExtractMethodHandlerDemo.SAME_FILE_CLONE_RANGES) {
            String canon = sourceTextAtLines(fullSource, site[0], site[1]);
            if (canon != null
                    && indentInvariantFormForCloneCompare(canon).equals(nb)) {
                return true;
            }
        }
        for (int[] r : discoverDemoCloneLineRanges(fullSource)) {
            String canon = sourceTextAtLines(fullSource, r[0], r[1]);
            if (canon != null
                    && indentInvariantFormForCloneCompare(canon).equals(nb)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Intra-editor drag: run Command Action 02 when the moved text matches either canonical
     * region (316\u2013335 or 476\u2013495) or the same block at discovered line offsets, using
     * normalized body comparison and/or exact 1-based line span vs those ranges. Resolves
     * {@code deleteOffset} against {@code preDrag} when it does not line up with {@code body}
     * (Eclipse event coordinates vs reconstructed source).
     */
    public static boolean intraEditorDragMatchesDemoClone(String preDrag, int deleteOffset,
            String body) {
        if (droppedTextMatchesDemoCloneWindows(preDrag, body)) {
            return true;
        }
        if (preDrag == null || body == null || body.isEmpty()) {
            return false;
        }
        int effDel = findBodyOffsetClosestToHint(preDrag, body, deleteOffset);
        if (effDel < 0) {
            return false;
        }
        int end = effDel + body.length();
        if (end > preDrag.length()) {
            return false;
        }
        int[] span = oneBasedInclusiveLinesForOffsetRange(preDrag, effDel, end);
        if (span == null) {
            return false;
        }
        for (int[] site : ExtractMethodHandlerDemo.SAME_FILE_CLONE_RANGES) {
            if (span[0] == site[0] && span[1] == site[1]) {
                return true;
            }
        }
        for (int[] r : discoverDemoCloneLineRanges(preDrag)) {
            if (span[0] == r[0] && span[1] == r[1]) {
                return true;
            }
        }
        return false;
    }

    private static int findBodyOffsetClosestToHint(String preDrag, String body, int hintDelOff) {
        if (body.isEmpty()) {
            return -1;
        }
        if (hintDelOff >= 0 && hintDelOff + body.length() <= preDrag.length()
                && preDrag.regionMatches(hintDelOff, body, 0, body.length())) {
            return hintDelOff;
        }
        int best = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int pos = preDrag.indexOf(body); pos >= 0; pos = preDrag.indexOf(body, pos + 1)) {
            int d = Math.abs(pos - hintDelOff);
            if (d < bestDist) {
                bestDist = d;
                best = pos;
            }
        }
        return best;
    }

    private static int getLineStartOffset(String source, int targetLine) {
        if (targetLine <= 1) {
            return 0;
        }
        int currentLine = 1;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                currentLine++;
                if (currentLine == targetLine) {
                    return i + 1;
                }
            }
        }
        throw new IllegalArgumentException("Start line out of range: " + targetLine);
    }

    private static int getLineEndOffset(String source, int targetLine) {
        int currentLine = 1;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                if (currentLine == targetLine) {
                    return i + 1;
                }
                currentLine++;
            }
        }
        if (currentLine == targetLine) {
            return source.length();
        }
        throw new IllegalArgumentException("End line out of range: " + targetLine);
    }

    private static int[] parseLineRange(String range) {
        if (range == null) {
            return null;
        }
        Matcher m = RANGE_PAT.matcher(range.trim());
        if (!m.matches()) {
            return null;
        }
        return new int[]{ Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) };
    }

    private static String getStatusMessage(RefactoringStatus status) {
        String message = status.getMessageMatchingSeverity(RefactoringStatus.FATAL);
        if (message != null) {
            return message;
        }
        message = status.getMessageMatchingSeverity(RefactoringStatus.ERROR);
        if (message != null) {
            return message;
        }
        message = status.getMessageMatchingSeverity(RefactoringStatus.WARNING);
        return message != null ? message : "Unknown refactoring status.";
    }

    private record SourceRange(int offset, int length) {}

    /**
     * Shared constants for {@code refactor_plugin.handlers.ExtractMethodHandler}
     * (&quot;Command Action 02 (EM)&quot;) and for dropzone routing on the demo file.
     */
    public static final class ExtractMethodHandlerDemo {

        /** Package-relative path under a Java source root (must match workspace layout). */
        public static final String TARGET_RELATIVE_PATH =
                "org/apache/camel/dsl/jbang/core/commands/ExportQuarkus.java";

        /** 1-based inclusive line pairs for the two clone sites (same as the menu command). */
        public static final int[][] SAME_FILE_CLONE_RANGES = {
                { 316, 335 },
                { 476, 495 },
        };

        /**
         * Line count for each clone site (both entries in {@link #SAME_FILE_CLONE_RANGES} use the
         * same block shape in the Camel {@code ExportQuarkus} demo source).
         */
        public static final int DEMO_CLONE_LINE_SPAN = SAME_FILE_CLONE_RANGES[0][1]
                - SAME_FILE_CLONE_RANGES[0][0] + 1;

        /**
         * Exact text of site 1 in the reference file (lines 316\u2013335): the {@code for (dep)}
         * block only. Matching is done on {@link MultiSiteJdtExtract#normalizeForCloneCompare}.
         * (Built with {@link String#join} so leading indentation is preserved; a text block would
         * strip incidental spaces and break matching.)
         */
        public static final String DEMO_CLONE_SNIPPET = String.join("\n",
                "        for (String dep : deps) {",
                "            MavenGav gav = parseMavenGav(dep);",
                "            String gid = gav.getGroupId();",
                "            String aid = gav.getArtifactId();",
                "            // transform to camel-quarkus extension GAV",
                "            if (\"org.apache.camel\".equals(gid)) {",
                "                String qaid = aid.replace(\"camel-\", \"camel-quarkus-\");",
                "                ArtifactModel<?> am = catalog.modelFromMavenGAV(\"org.apache.camel.quarkus\", qaid, null);",
                "                if (am != null) {",
                "                    // use quarkus extension",
                "                    gav.setGroupId(am.getGroupId());",
                "                    gav.setArtifactId(am.getArtifactId());",
                "                    gav.setVersion(null); // uses BOM so version should not be included",
                "                } else {",
                "                    // there is no quarkus extension so use plain camel",
                "                    gav.setVersion(camelVersion);",
                "                }",
                "            }",
                "            gavs.add(gav);",
                "        }");

        public static final String NORMALIZED_DEMO_CLONE_SNIPPET =
                MultiSiteJdtExtract.normalizeForCloneCompare(DEMO_CLONE_SNIPPET);

        public static final String UNIFIED_METHOD_NAME = "extractedM1Block";

        private ExtractMethodHandlerDemo() {}

        /**
         * True when the editor file is the demo {@link #TARGET_RELATIVE_PATH} (any layout
         * {@link ClonePathMatch#canonicalJavaPathKey} understands).
         */
        public static boolean editorFileMatchesDemo(String editorAbsolutePath) {
            if (editorAbsolutePath == null) {
                return false;
            }
            String key = ClonePathMatch.canonicalJavaPathKey(
                    editorAbsolutePath.replace('\\', '/'));
            return TARGET_RELATIVE_PATH.equals(key);
        }

        /** Same path form as {@code ExtractMethodHandler} uses. */
        public static String javaRelativePathForCu(ICompilationUnit cu) {
            if (cu == null) {
                return null;
            }
            IJavaElement parent = cu.getParent();
            if (!(parent instanceof IPackageFragment pkg)) {
                return null;
            }
            String packagePath = pkg.getElementName().replace('.', '/');
            if (packagePath.isEmpty()) {
                return cu.getElementName();
            }
            return packagePath + "/" + cu.getElementName();
        }
    }
}
