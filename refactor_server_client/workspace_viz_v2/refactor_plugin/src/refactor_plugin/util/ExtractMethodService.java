package refactor_plugin.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.TypeDeclaration;
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

import refactor_plugin.handlers.extract.ExtractionTarget;

public class ExtractMethodService {

   /**
    * The main entry point to perform the refactoring workflow.
    */
   public void performExtraction(ICompilationUnit cu, List<ExtractionTarget> extractionTargets, String extractedMethodLocation) throws Exception {
      // Step 1: Extract all locations (Bottom-up to prevent line shifts)
      Collections.sort(extractionTargets);
      for (ExtractionTarget target : extractionTargets) {
         applyExtractMethodRefactoring(cu, target);
      }

      // Step 2: Delete secondary methods
      deleteSecondaryMethods(cu, extractionTargets);

      // Step 3: Rename the function calls to point to the primary method
      renameSecondaryInvocations(cu, extractionTargets);

      // Step 4: Move the primary extracted method to the requested location
      moveExtractedMethod(cu, extractionTargets, extractedMethodLocation);

      // Reveal the primary method in the editor
      ExtractionTarget primary = extractionTargets.stream().filter(ExtractionTarget::isPrimary).findFirst().orElse(null);
      if (primary != null) {
         revealExtractedMethod(cu, primary.getMethodName());
      }
   }

   @SuppressWarnings("restriction")
   private void applyExtractMethodRefactoring(ICompilationUnit compilationUnit, ExtractionTarget target) throws Exception {
      SourceRange sourceRange = computeSourceRange(compilationUnit, target.getStartLine(), target.getEndLine());
      ExtractMethodRefactoring refactoring = new ExtractMethodRefactoring(compilationUnit, sourceRange.offset, sourceRange.length);

      refactoring.setMethodName(target.getMethodName());
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

   private void deleteSecondaryMethods(ICompilationUnit compilationUnit, List<ExtractionTarget> extractionTargets) throws Exception {
      for (ExtractionTarget target : extractionTargets) {
         if (!target.isPrimary()) {
            IMethod methodToDelete = findExtractedMethod(compilationUnit, target.getMethodName());
            if (methodToDelete != null && methodToDelete.exists()) {
               methodToDelete.delete(true, new NullProgressMonitor());
            }
         }
      }
      compilationUnit.reconcile(ICompilationUnit.NO_AST, false, null, null);
   }

   private void renameSecondaryInvocations(ICompilationUnit compilationUnit, List<ExtractionTarget> extractionTargets) throws Exception {
      ASTParser parser = ASTParser.newParser(AST.JLS25);
      parser.setSource(compilationUnit);
      CompilationUnit astRoot = (CompilationUnit) parser.createAST(new NullProgressMonitor());

      ASTRewrite rewriter = ASTRewrite.create(astRoot.getAST());

      String primaryMethodName = extractionTargets.stream().filter(ExtractionTarget::isPrimary).map(ExtractionTarget::getMethodName).findFirst().orElseThrow(() -> new IllegalStateException("No primary target defined."));

      List<String> secondaryNames = new ArrayList<>();
      for (ExtractionTarget target : extractionTargets) {
         if (!target.isPrimary())
            secondaryNames.add(target.getMethodName());
      }

      astRoot.accept(new ASTVisitor() {
         @Override
         public boolean visit(MethodInvocation mi) {
            String currentName = mi.getName().getIdentifier();
            if (secondaryNames.contains(currentName)) {
               AST ast = mi.getAST();
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

   private void moveExtractedMethod(ICompilationUnit compilationUnit, List<ExtractionTarget> extractionTargets, String extractedMethodLocation) throws Exception {
      String targetMethodName = parseTargetMethodName(extractedMethodLocation);
      if (targetMethodName == null)
         return;

      String primaryMethodName = extractionTargets.stream().filter(ExtractionTarget::isPrimary).map(ExtractionTarget::getMethodName).findFirst().orElseThrow(() -> new IllegalStateException("No primary target defined."));

      ASTParser parser = ASTParser.newParser(AST.JLS25);
      parser.setSource(compilationUnit);
      CompilationUnit astRoot = (CompilationUnit) parser.createAST(new NullProgressMonitor());

      final TypeDeclaration[] parentType = new TypeDeclaration[1];
      final MethodDeclaration[] extractedNode = new MethodDeclaration[1];
      final MethodDeclaration[] destinationNode = new MethodDeclaration[1];

      astRoot.accept(new ASTVisitor() {
         @Override
         public boolean visit(TypeDeclaration node) {
            if (parentType[0] == null) {
               parentType[0] = node;
            }
            return super.visit(node);
         }

         @Override
         public boolean visit(MethodDeclaration node) {
            String name = node.getName().getIdentifier();
            if (name.equals(primaryMethodName)) {
               extractedNode[0] = node;
            }
            else if (name.equals(targetMethodName)) {
               destinationNode[0] = node;
            }
            return super.visit(node);
         }
      });

      if (parentType[0] != null && extractedNode[0] != null && destinationNode[0] != null) {
         ASTRewrite rewrite = ASTRewrite.create(astRoot.getAST());
         ListRewrite listRewrite = rewrite.getListRewrite(parentType[0], TypeDeclaration.BODY_DECLARATIONS_PROPERTY);

         ASTNode moved = rewrite.createMoveTarget(extractedNode[0]);
         listRewrite.insertAfter(moved, destinationNode[0], null);

         Document document = new Document(compilationUnit.getSource());
         TextEdit edits = rewrite.rewriteAST(document, null);
         edits.apply(document);

         compilationUnit.getBuffer().setContents(document.get());
         compilationUnit.reconcile(ICompilationUnit.NO_AST, false, null, null);
         compilationUnit.commitWorkingCopy(true, new NullProgressMonitor());
      }
   }

   private void revealExtractedMethod(ICompilationUnit compilationUnit, String methodName) throws Exception {
      IMethod extractedMethod = findExtractedMethod(compilationUnit, methodName);
      if (extractedMethod == null || !extractedMethod.exists())
         return;

      IEditorPart editor = JavaUI.openInEditor(compilationUnit);
      JavaUI.revealInEditor(editor, (IJavaElement) extractedMethod);
      if (editor instanceof ITextEditor) {
         ITextEditor textEditor = (ITextEditor) editor;
         if (extractedMethod.getNameRange() != null) {
            textEditor.selectAndReveal(extractedMethod.getNameRange().getOffset(), extractedMethod.getNameRange().getLength());
         }
         else {
            textEditor.selectAndReveal(extractedMethod.getSourceRange().getOffset(), 0);
         }
      }
   }

   private String parseTargetMethodName(String locationString) {
      if (locationString == null || !locationString.contains("("))
         return null;
      String beforeParenthesis = locationString.substring(0, locationString.indexOf('(')).trim();
      int lastSpaceIndex = beforeParenthesis.lastIndexOf(' ');
      if (lastSpaceIndex != -1) {
         return beforeParenthesis.substring(lastSpaceIndex + 1).trim();
      }
      return beforeParenthesis;
   }

   private IMethod findExtractedMethod(ICompilationUnit compilationUnit, String methodName) throws Exception {
      for (IType type : compilationUnit.getAllTypes()) {
         for (IMethod method : type.getMethods()) {
            if (methodName.equals(method.getElementName()))
               return method;
         }
      }
      return null;
   }

   private SourceRange computeSourceRange(ICompilationUnit compilationUnit, int startLine, int endLine) throws Exception {
      String source = compilationUnit.getSource();
      int startOffset = getLineStartOffset(source, startLine);
      int endOffset = getLineEndOffset(source, endLine);
      return new SourceRange(startOffset, endOffset - startOffset);
   }

   private int getLineStartOffset(String source, int targetLine) {
      if (targetLine <= 1)
         return 0;
      int currentLine = 1;
      for (int i = 0; i < source.length(); i++) {
         if (source.charAt(i) == '\n') {
            currentLine++;
            if (currentLine == targetLine)
               return i + 1;
         }
      }
      throw new IllegalArgumentException("Start line out of range: " + targetLine);
   }

   private int getLineEndOffset(String source, int targetLine) {
      int currentLine = 1;
      for (int i = 0; i < source.length(); i++) {
         if (source.charAt(i) == '\n') {
            if (currentLine == targetLine)
               return i + 1;
            currentLine++;
         }
      }
      if (currentLine == targetLine)
         return source.length();
      throw new IllegalArgumentException("End line out of range: " + targetLine);
   }

   private String getStatusMessage(RefactoringStatus status) {
      String message = status.getMessageMatchingSeverity(RefactoringStatus.FATAL);
      if (message != null)
         return message;
      message = status.getMessageMatchingSeverity(RefactoringStatus.ERROR);
      if (message != null)
         return message;
      message = status.getMessageMatchingSeverity(RefactoringStatus.WARNING);
      return message != null ? message : "Unknown refactoring status.";
   }

   // --- Workspace Search Utilities ---

   public ICompilationUnit findCompilationUnitInOpenJavaProjects(IWorkspace workspace, String targetRelativePath) throws Exception {
      for (IProject project : workspace.getRoot().getProjects()) {
         if (!project.isOpen() || !project.hasNature(JavaCore.NATURE_ID))
            continue;
         IJavaProject javaProject = JavaCore.create(project);

         for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE)
               continue;

            for (IJavaElement element : root.getChildren()) {
               if (!(element instanceof IPackageFragment))
                  continue;
               IPackageFragment pkg = (IPackageFragment) element;

               for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                  String packagePath = pkg.getElementName().replace('.', '/');
                  String currentRelativePath = packagePath.isEmpty() ? cu.getElementName() : packagePath + "/" + cu.getElementName();

                  if (targetRelativePath.equals(currentRelativePath)) {
                     return cu;
                  }
               }
            }
         }
      }
      return null;
   }

   private static class SourceRange {
      private final int offset;
      private final int length;

      private SourceRange(int offset, int length) {
         this.offset = offset;
         this.length = length;
      }
   }
}