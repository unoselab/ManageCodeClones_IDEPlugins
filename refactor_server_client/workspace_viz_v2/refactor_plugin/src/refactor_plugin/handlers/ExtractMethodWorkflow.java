package refactor_plugin.handlers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jdt.internal.corext.refactoring.code.ExtractMethodRefactoring;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.text.Document;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Shared implementation of the four-step pipeline described in {@code README_multi_0408.md}
 * (bottom-up extraction, Java model deletion, batched AST rebind, relocation). The course
 * reference handler {@code ExtractMethodHandler.java} is kept unchanged and mirrors these
 * steps inline; drag-and-drop and other features use this class via
 * {@link CloneRecordLiveExtract} when all clone sites share one workspace
 * {@link ICompilationUnit}.
 *
 * <p><b>Mapping to the README</b>
 * <ul>
 *   <li><b>Bottom-up:</b> {@link ExtractionTarget#compareTo} sorts by <em>descending</em>
 *       start line before {@link #applyExtractMethodRefactoring} (line-shift avoidance).</li>
 *   <li><b>Clean deletion:</b> {@link #deleteSecondaryMethods} uses {@link IMethod#delete}.</li>
 *   <li><b>AST manipulation:</b> {@link #renameSecondaryInvocations} — one {@link ASTRewrite},
 *       visitor records {@link MethodInvocation} renames only; {@link #moveExtractedMethod}
 *       uses {@code createMoveTarget} + {@link ListRewrite} and {@code final} one-element
 *       arrays for visitor capture (README “effectively final” pattern).</li>
 *   <li><b>JLS level:</b> {@link #AST_JLS} = {@link AST#JLS25} per README.</li>
 * </ul>
 */
public final class ExtractMethodWorkflow {

    private static final int AST_JLS = AST.JLS25;

    /**
     * Same course demo as {@code ExtractMethodHandler} (Command Action 02): two-site extract
     * on {@code org/apache/camel/dsl/jbang/core/commands/ExportQuarkus.java}.
     */
    public static final String DEMO_EXPORT_QUARKUS_RELATIVE_PATH =
            "org/apache/camel/dsl/jbang/core/commands/ExportQuarkus.java";

    /** Relocation anchor string; parsed by {@link #parseTargetMethodName}. */
    public static final String DEMO_EXPORT_QUARKUS_MOVE_AFTER_LOCATION =
            "After void replaceQuarkusDependencies(List<MavenGav> gavs)";

    /**
     * Move primary extracted method to the last position in its enclosing type
     * ({@link ListRewrite#insertLast}).
     */
    public static final String MOVE_PRIMARY_METHOD_TO_END_OF_TYPE =
            "__MOVE_PRIMARY_METHOD_TO_END_OF_TYPE__";

    private ExtractMethodWorkflow() {}

    /** Line ranges and method names aligned with {@code ExtractMethodHandler}. */
    public static List<ExtractionTarget> exportQuarkusDemoTargets() {
        return List.of(
                new ExtractionTarget(316, 335, "extractedM1Block1", true),
                new ExtractionTarget(476, 495, "extractedM1Block2", false));
    }

    /** {@code true} when {@code absolutePath} ends with the demo file’s package-relative path. */
    public static boolean isExportQuarkusDemoWorkspacePath(String absolutePath) {
        if (absolutePath == null || absolutePath.isBlank()) {
            return false;
        }
        String n = absolutePath.replace('\\', '/');
        String suffix = "/" + DEMO_EXPORT_QUARKUS_RELATIVE_PATH;
        return n.endsWith(suffix);
    }

    /**
     * Maps a document offset (caret or drop) to a {@code moveAfterLocation} for
     * {@link #runWorkflow}:
     * <ul>
     *   <li>Trailing gap (after last member, before the type’s closing brace) →
     *       {@link #MOVE_PRIMARY_METHOD_TO_END_OF_TYPE}.</li>
     *   <li>Inside a method body → {@code After void name()} for the <em>innermost</em>
     *       containing {@link MethodDeclaration}.</li>
     *   <li>Otherwise → {@code null} so callers use JSON / demo default (avoids forcing
     *       end-of-class for “between members” or ambiguous {@link NodeFinder} coverage).</li>
     * </ul>
     */
    public static String moveAfterLocationForCaret(ICompilationUnit cu, int sourceOffset) {
        if (cu == null || sourceOffset < 0) {
            return null;
        }
        try {
            String fileSource = cu.getSource();
            ASTParser parser = ASTParser.newParser(AST_JLS);
            parser.setSource(cu);
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setResolveBindings(false);
            CompilationUnit root =
                    (CompilationUnit) parser.createAST(new NullProgressMonitor());
            NodeFinder finder = new NodeFinder(root, sourceOffset, 0);
            ASTNode n = finder.getCoveringNode();
            if (n == null) {
                return null;
            }
            TypeDeclaration innerType = null;
            for (ASTNode cur = n; cur != null; cur = cur.getParent()) {
                if (cur instanceof TypeDeclaration td) {
                    innerType = td;
                    break;
                }
            }
            if (innerType == null) {
                return null;
            }
            if (fileSource != null
                    && isOffsetInTypeTrailingGapBeforeClose(innerType, sourceOffset, fileSource)) {
                return MOVE_PRIMARY_METHOD_TO_END_OF_TYPE;
            }
            MethodDeclaration innermost = findInnermostMethodDeclarationContaining(root, sourceOffset);
            if (innermost != null) {
                return "After void " + innermost.getName().getIdentifier() + "()";
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Smallest-span {@link MethodDeclaration} whose source range contains {@code offset}
     * (handles {@link NodeFinder} returning a coarse covering node).
     */
    private static MethodDeclaration findInnermostMethodDeclarationContaining(CompilationUnit root,
            int offset) {
        final MethodDeclaration[] best = new MethodDeclaration[1];
        final int[] bestLen = { Integer.MAX_VALUE };
        root.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                int s = node.getStartPosition();
                int e = s + node.getLength();
                if (offset >= s && offset < e) {
                    int len = node.getLength();
                    if (len < bestLen[0]) {
                        bestLen[0] = len;
                        best[0] = node;
                    }
                }
                return true;
            }
        });
        return best[0];
    }

    private static boolean isOffsetInTypeTrailingGapBeforeClose(TypeDeclaration td, int offset,
            String source) {
        int typeStart = td.getStartPosition();
        int typeEndExclusive = typeStart + td.getLength();
        if (offset < typeStart || offset >= typeEndExclusive) {
            return false;
        }
        @SuppressWarnings("unchecked")
        List<BodyDeclaration> bodies = td.bodyDeclarations();
        int tailStart;
        if (bodies.isEmpty()) {
            int brace = source.indexOf('{', typeStart);
            if (brace < 0 || brace >= typeEndExclusive) {
                return false;
            }
            tailStart = brace + 1;
        } else {
            BodyDeclaration last = bodies.get(bodies.size() - 1);
            tailStart = last.getStartPosition() + last.getLength();
        }
        return offset >= tailStart && offset < typeEndExclusive;
    }

    /**
     * Same as {@link #runWorkflow(ICompilationUnit, List, String, int)} with no drop/caret
     * offset (string-based relocation only).
     */
    public static void runWorkflow(ICompilationUnit cu, List<ExtractionTarget> targets,
            String moveAfterLocation) throws Exception {
        runWorkflow(cu, targets, moveAfterLocation, -1);
    }

    /**
     * Runs extract → delete → rebind, then relocates the primary extracted method.
     *
     * @param moveAfterLocation fallback when {@code placementDocumentOffset < 0} or hint cannot
     *                          be applied (JSON / demo anchor string, or
     *                          {@link #MOVE_PRIMARY_METHOD_TO_END_OF_TYPE}).
     * @param placementDocumentOffset if {@code >= 0}, computed <em>before</em> any edit from
     *                                drop/caret: relocate with {@link ListRewrite} immediately
     *                                after the class member nearest that offset (same type as the
     *                                extracted method), or {@link ListRewrite#insertFirst} /
     *                                {@link ListRewrite#insertLast} when appropriate.
     */
    public static void runWorkflow(ICompilationUnit cu, List<ExtractionTarget> targets,
            String moveAfterLocation, int placementDocumentOffset) throws Exception {
        if (cu == null || targets == null || targets.isEmpty()) {
            return;
        }
        List<ExtractionTarget> sorted = new ArrayList<>(targets);
        Collections.sort(sorted);

        NearMemberHint nearHint = null;
        if (placementDocumentOffset >= 0) {
            String src = cu.getSource();
            ASTParser pre = ASTParser.newParser(AST_JLS);
            pre.setSource(cu);
            pre.setKind(ASTParser.K_COMPILATION_UNIT);
            pre.setResolveBindings(false);
            CompilationUnit preRoot =
                    (CompilationUnit) pre.createAST(new NullProgressMonitor());
            nearHint = computeNearMemberHint(preRoot, src, placementDocumentOffset);
        }

        for (ExtractionTarget target : sorted) {
            applyExtractMethodRefactoring(cu, target);
        }
        deleteSecondaryMethods(cu, sorted);
        renameSecondaryInvocations(cu, sorted);

        boolean relocated = false;
        if (nearHint != null) {
            relocated = tryRelocateNearDroppedMember(cu, sorted, nearHint);
        }
        if (!relocated && moveAfterLocation != null && !moveAfterLocation.isBlank()) {
            moveExtractedMethod(cu, sorted, moveAfterLocation);
        }
    }

    /** Where to place the primary extracted method relative to an existing member (pre-extract). */
    private enum NearKind {
        END_OF_TYPE,
        INSERT_FIRST,
        AFTER_METHOD,
        AFTER_FIELD,
        AFTER_NESTED_TYPE
    }

    private static final class NearMemberHint {
        final NearKind kind;
        /** Simple name for AFTER_* kinds. */
        final String name;

        NearMemberHint(NearKind kind, String name) {
            this.kind = kind;
            this.name = name;
        }
    }

    /**
     * Finds the class member whose position best matches the drop/caret: trailing gap → end;
     * before first member → {@link ListRewrite#insertFirst}; inside or just after a member →
     * {@code insertAfter} that {@link BodyDeclaration}.
     */
    private static NearMemberHint computeNearMemberHint(CompilationUnit root, String source,
            int offset) {
        if (root == null || source == null || offset < 0) {
            return null;
        }
        NodeFinder finder = new NodeFinder(root, offset, 0);
        ASTNode n = finder.getCoveringNode();
        if (n == null) {
            return null;
        }
        TypeDeclaration innerType = null;
        for (ASTNode cur = n; cur != null; cur = cur.getParent()) {
            if (cur instanceof TypeDeclaration td) {
                innerType = td;
                break;
            }
        }
        if (innerType == null) {
            return null;
        }
        if (isOffsetInTypeTrailingGapBeforeClose(innerType, offset, source)) {
            return new NearMemberHint(NearKind.END_OF_TYPE, null);
        }
        @SuppressWarnings("unchecked")
        List<BodyDeclaration> bodies = innerType.bodyDeclarations();
        if (bodies.isEmpty()) {
            return new NearMemberHint(NearKind.END_OF_TYPE, null);
        }
        if (offset < bodies.get(0).getStartPosition()) {
            return new NearMemberHint(NearKind.INSERT_FIRST, null);
        }
        for (BodyDeclaration bd : bodies) {
            int s = bd.getStartPosition();
            int e = s + bd.getLength();
            if (offset >= s && offset < e) {
                return nearHintForBodyDeclaration(bd);
            }
        }
        int pred = -1;
        for (int j = 0; j < bodies.size(); j++) {
            BodyDeclaration bd = bodies.get(j);
            int end = bd.getStartPosition() + bd.getLength();
            if (end <= offset) {
                pred = j;
            }
        }
        if (pred >= 0) {
            return nearHintForBodyDeclaration(bodies.get(pred));
        }
        return null;
    }

    private static NearMemberHint nearHintForBodyDeclaration(BodyDeclaration bd) {
        if (bd instanceof MethodDeclaration md) {
            return new NearMemberHint(NearKind.AFTER_METHOD, md.getName().getIdentifier());
        }
        if (bd instanceof FieldDeclaration fd) {
            if (fd.fragments().isEmpty()) {
                return null;
            }
            Object f0 = fd.fragments().get(0);
            if (f0 instanceof VariableDeclarationFragment vf) {
                return new NearMemberHint(NearKind.AFTER_FIELD, vf.getName().getIdentifier());
            }
            return null;
        }
        if (bd instanceof TypeDeclaration nested) {
            return new NearMemberHint(NearKind.AFTER_NESTED_TYPE, nested.getName().getIdentifier());
        }
        if (bd instanceof EnumDeclaration en) {
            return new NearMemberHint(NearKind.AFTER_NESTED_TYPE, en.getName().getIdentifier());
        }
        return null;
    }

    private static boolean tryRelocateNearDroppedMember(ICompilationUnit cu,
            List<ExtractionTarget> sorted, NearMemberHint hint) throws Exception {
        if (hint == null) {
            return false;
        }
        if (hint.kind == NearKind.END_OF_TYPE) {
            moveExtractedMethodToEndOfEnclosingType(cu, sorted);
            return true;
        }

        String primaryMethodName = sorted.stream()
                .filter(t -> t.isPrimary)
                .map(t -> t.methodName)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No primary target defined."));

        ASTParser parser = ASTParser.newParser(AST_JLS);
        parser.setSource(cu);
        CompilationUnit astRoot = (CompilationUnit) parser.createAST(new NullProgressMonitor());

        final MethodDeclaration[] extractedNode = new MethodDeclaration[1];
        astRoot.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (primaryMethodName.equals(node.getName().getIdentifier())) {
                    extractedNode[0] = node;
                }
                return true;
            }
        });
        if (extractedNode[0] == null) {
            return false;
        }
        ASTNode p = extractedNode[0].getParent();
        if (!(p instanceof TypeDeclaration parentType)) {
            return false;
        }

        ASTRewrite rewrite = ASTRewrite.create(astRoot.getAST());
        ListRewrite listRewrite =
                rewrite.getListRewrite(parentType, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
        org.eclipse.jdt.core.dom.ASTNode movePlaceholder =
                rewrite.createMoveTarget(extractedNode[0]);

        if (hint.kind == NearKind.INSERT_FIRST) {
            listRewrite.insertFirst(movePlaceholder, null);
        } else {
            BodyDeclaration anchor = resolveAnchorInType(parentType, hint);
            if (anchor == null || anchor == extractedNode[0]) {
                return false;
            }
            listRewrite.insertAfter(movePlaceholder, anchor, null);
        }

        Document document = new Document(cu.getSource());
        TextEdit edits = rewrite.rewriteAST(document, null);
        edits.apply(document);

        cu.getBuffer().setContents(document.get());
        cu.reconcile(ICompilationUnit.NO_AST, false, null, null);
        cu.commitWorkingCopy(true, new NullProgressMonitor());
        return true;
    }

    private static BodyDeclaration resolveAnchorInType(TypeDeclaration parentType, NearMemberHint h) {
        if (h.name == null) {
            return null;
        }
        for (Object o : parentType.bodyDeclarations()) {
            if (!(o instanceof BodyDeclaration bd)) {
                continue;
            }
            switch (h.kind) {
                case AFTER_METHOD:
                    if (bd instanceof MethodDeclaration md
                            && h.name.equals(md.getName().getIdentifier())) {
                        return bd;
                    }
                    break;
                case AFTER_FIELD:
                    if (bd instanceof FieldDeclaration fd) {
                        for (Object frag : fd.fragments()) {
                            if (frag instanceof VariableDeclarationFragment vf
                                    && h.name.equals(vf.getName().getIdentifier())) {
                                return bd;
                            }
                        }
                    }
                    break;
                case AFTER_NESTED_TYPE:
                    if (bd instanceof TypeDeclaration nt
                            && h.name.equals(nt.getName().getIdentifier())) {
                        return bd;
                    }
                    if (bd instanceof EnumDeclaration en
                            && h.name.equals(en.getName().getIdentifier())) {
                        return bd;
                    }
                    break;
                default:
                    break;
            }
        }
        return null;
    }

    public static void revealExtractedMethod(ICompilationUnit cu, String methodName,
            ITextEditor reuseIfSameFile) throws Exception {
        IMethod extractedMethod = findExtractedMethod(cu, methodName);
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
        try {
            IJavaElement je = JavaUI.getEditorInputJavaElement(ed.getEditorInput());
            if (je instanceof ICompilationUnit edCu) {
                return edCu.getPrimary().getPath().equals(cu.getPrimary().getPath());
            }
        } catch (Exception ignored) { /* */ }
        return false;
    }

    /**
     * README Step 4 — {@code ListRewrite} relocation; {@code final} one-slot arrays hold
     * nodes located during {@link ASTVisitor} traversal.
     */
    private static void moveExtractedMethod(ICompilationUnit compilationUnit,
            List<ExtractionTarget> extractionTargets, String extractedMethodLocation)
            throws Exception {
        if (MOVE_PRIMARY_METHOD_TO_END_OF_TYPE.equals(extractedMethodLocation)) {
            moveExtractedMethodToEndOfEnclosingType(compilationUnit, extractionTargets);
            return;
        }
        String targetMethodName = parseTargetMethodName(extractedMethodLocation);
        if (targetMethodName == null) {
            return;
        }
        String primaryMethodName = extractionTargets.stream()
                .filter(t -> t.isPrimary)
                .map(t -> t.methodName)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No primary target defined."));

        ASTParser parser = ASTParser.newParser(AST_JLS);
        parser.setSource(compilationUnit);
        CompilationUnit astRoot = (CompilationUnit) parser.createAST(new NullProgressMonitor());

        final MethodDeclaration[] extractedNode = new MethodDeclaration[1];
        astRoot.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (primaryMethodName.equals(node.getName().getIdentifier())) {
                    extractedNode[0] = node;
                }
                return true;
            }
        });

        if (extractedNode[0] == null) {
            return;
        }
        ASTNode p = extractedNode[0].getParent();
        if (!(p instanceof TypeDeclaration parentType)) {
            return;
        }
        MethodDeclaration destinationNode = null;
        for (Object o : parentType.bodyDeclarations()) {
            if (o instanceof MethodDeclaration md
                    && targetMethodName.equals(md.getName().getIdentifier())) {
                destinationNode = md;
                break;
            }
        }

        if (destinationNode != null && destinationNode != extractedNode[0]) {
            ASTRewrite rewrite = ASTRewrite.create(astRoot.getAST());
            ListRewrite listRewrite =
                    rewrite.getListRewrite(parentType, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
            org.eclipse.jdt.core.dom.ASTNode movePlaceholder =
                    rewrite.createMoveTarget(extractedNode[0]);
            listRewrite.insertAfter(movePlaceholder, destinationNode, null);

            Document document = new Document(compilationUnit.getSource());
            TextEdit edits = rewrite.rewriteAST(document, null);
            edits.apply(document);

            compilationUnit.getBuffer().setContents(document.get());
            compilationUnit.reconcile(ICompilationUnit.NO_AST, false, null, null);
            compilationUnit.commitWorkingCopy(true, new NullProgressMonitor());
        }
    }

    private static void moveExtractedMethodToEndOfEnclosingType(ICompilationUnit compilationUnit,
            List<ExtractionTarget> extractionTargets) throws Exception {
        String primaryMethodName = extractionTargets.stream()
                .filter(t -> t.isPrimary)
                .map(t -> t.methodName)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No primary target defined."));

        ASTParser parser = ASTParser.newParser(AST_JLS);
        parser.setSource(compilationUnit);
        CompilationUnit astRoot = (CompilationUnit) parser.createAST(new NullProgressMonitor());

        final MethodDeclaration[] extractedNode = new MethodDeclaration[1];
        astRoot.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (primaryMethodName.equals(node.getName().getIdentifier())) {
                    extractedNode[0] = node;
                }
                return true;
            }
        });

        if (extractedNode[0] == null) {
            return;
        }
        ASTNode parent = extractedNode[0].getParent();
        if (!(parent instanceof TypeDeclaration parentType)) {
            return;
        }

        ASTRewrite rewrite = ASTRewrite.create(astRoot.getAST());
        ListRewrite listRewrite =
                rewrite.getListRewrite(parentType, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
        org.eclipse.jdt.core.dom.ASTNode movePlaceholder =
                rewrite.createMoveTarget(extractedNode[0]);
        listRewrite.insertLast(movePlaceholder, null);

        Document document = new Document(compilationUnit.getSource());
        TextEdit edits = rewrite.rewriteAST(document, null);
        edits.apply(document);

        compilationUnit.getBuffer().setContents(document.get());
        compilationUnit.reconcile(ICompilationUnit.NO_AST, false, null, null);
        compilationUnit.commitWorkingCopy(true, new NullProgressMonitor());
    }

    public static String parseTargetMethodName(String locationString) {
        if (locationString == null || !locationString.contains("(")) {
            return null;
        }
        String beforeParenthesis = locationString.substring(0, locationString.indexOf('(')).trim();
        int lastSpaceIndex = beforeParenthesis.lastIndexOf(' ');
        if (lastSpaceIndex != -1) {
            return beforeParenthesis.substring(lastSpaceIndex + 1).trim();
        }
        return beforeParenthesis;
    }

    private static void applyExtractMethodRefactoring(ICompilationUnit compilationUnit,
            ExtractionTarget target) throws Exception {
        SourceRange sourceRange = computeSourceRange(compilationUnit, target.startLine,
                target.endLine);
        ExtractMethodRefactoring refactoring = new ExtractMethodRefactoring(compilationUnit,
                sourceRange.offset(), sourceRange.length());
        refactoring.setMethodName(target.methodName);
        refactoring.setReplaceDuplicates(false);

        RefactoringStatus initialStatus = refactoring.checkInitialConditions(new NullProgressMonitor());
        if (initialStatus.hasError() || initialStatus.hasFatalError()) {
            throw new Exception("Initial Condition Failed: " + getStatusMessage(initialStatus));
        }
        RefactoringStatus finalStatus = refactoring.checkFinalConditions(new NullProgressMonitor());
        if (finalStatus.hasError() || finalStatus.hasFatalError()) {
            throw new Exception("Final Condition Failed: " + getStatusMessage(finalStatus));
        }
        Change change = refactoring.createChange(new NullProgressMonitor());
        change.perform(new NullProgressMonitor());
        compilationUnit.reconcile(ICompilationUnit.NO_AST, false, null, null);
    }

    /** README Step 2 — Java Model API, not raw text delete. */
    private static void deleteSecondaryMethods(ICompilationUnit compilationUnit,
            List<ExtractionTarget> extractionTargets) throws Exception {
        for (ExtractionTarget target : extractionTargets) {
            if (!target.isPrimary) {
                IMethod methodToDelete = findExtractedMethod(compilationUnit, target.methodName);
                if (methodToDelete != null && methodToDelete.exists()) {
                    methodToDelete.delete(true, new NullProgressMonitor());
                }
            }
        }
        compilationUnit.reconcile(ICompilationUnit.NO_AST, false, null, null);
    }

    /** README Step 3 — single batched {@link ASTRewrite}; apply once after traversal. */
    private static void renameSecondaryInvocations(ICompilationUnit compilationUnit,
            List<ExtractionTarget> extractionTargets) throws Exception {
        ASTParser parser = ASTParser.newParser(AST_JLS);
        parser.setSource(compilationUnit);
        CompilationUnit astRoot = (CompilationUnit) parser.createAST(new NullProgressMonitor());

        ASTRewrite rewriter = ASTRewrite.create(astRoot.getAST());
        String primaryMethodName = extractionTargets.stream()
                .filter(t -> t.isPrimary)
                .map(t -> t.methodName)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No primary target defined."));

        List<String> secondaryNames = new ArrayList<>();
        for (ExtractionTarget target : extractionTargets) {
            if (!target.isPrimary) {
                secondaryNames.add(target.methodName);
            }
        }

        astRoot.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation mi) {
                String currentName = mi.getName().getIdentifier();
                if (secondaryNames.contains(currentName)) {
                    org.eclipse.jdt.core.dom.AST ast = mi.getAST();
                    SimpleName newName = ast.newSimpleName(primaryMethodName);
                    rewriter.set(mi, MethodInvocation.NAME_PROPERTY, newName, null);
                }
                return super.visit(mi);
            }
        });

        Document document = new Document(compilationUnit.getSource());
        TextEdit edits = rewriter.rewriteAST(document, null);
        edits.apply(document);

        compilationUnit.getBuffer().setContents(document.get());
        compilationUnit.reconcile(ICompilationUnit.NO_AST, false, null, null);
        compilationUnit.commitWorkingCopy(true, new NullProgressMonitor());
    }

    static IMethod findExtractedMethod(ICompilationUnit compilationUnit, String methodName)
            throws Exception {
        for (IType type : compilationUnit.getAllTypes()) {
            for (IMethod method : type.getMethods()) {
                if (methodName.equals(method.getElementName())) {
                    return method;
                }
            }
        }
        return null;
    }

    private static SourceRange computeSourceRange(ICompilationUnit compilationUnit, int startLine,
            int endLine) throws Exception {
        String source = compilationUnit.getSource();
        int startOffset = getLineStartOffset(source, startLine);
        int endOffset = getLineEndOffset(source, endLine);
        return new SourceRange(startOffset, endOffset - startOffset);
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

    /** One clone site: line range, unique method name during extract, primary survives merge. */
    public static final class ExtractionTarget implements Comparable<ExtractionTarget> {
        public final int startLine;
        public final int endLine;
        public final String methodName;
        public final boolean isPrimary;

        public ExtractionTarget(int startLine, int endLine, String methodName, boolean isPrimary) {
            this.startLine = startLine;
            this.endLine = endLine;
            this.methodName = methodName;
            this.isPrimary = isPrimary;
        }

        @Override
        public int compareTo(ExtractionTarget other) {
            return Integer.compare(other.startLine, this.startLine);
        }
    }

    private record SourceRange(int offset, int length) {}
}
