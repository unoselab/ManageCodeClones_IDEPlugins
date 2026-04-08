package refactor_plugin.handlers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
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
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.Document;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

public class ExtractMethodHandler extends AbstractHandler {

   private final String targetRelativePath = "org/apache/camel/dsl/jbang/core/commands/ExportQuarkus.java";

   // 1. Define all targets with a primary flag
   private final List<ExtractionTarget> extractionTargets = new ArrayList<>(Arrays.asList(
         new ExtractionTarget(316, 335, "extractedM1Block1", true), // Primary target
         new ExtractionTarget(476, 495, "extractedM1Block2", false) // Secondary target to be merged
   ));

   // The requirement string for placing the extracted method
   private final String extractedMethodLocation = "After void replaceQuarkusDependencies(List<MavenGav> gavs)";

   @Override
   public Object execute(ExecutionEvent event) throws ExecutionException {
      IWorkspace workspace = ResourcesPlugin.getWorkspace();

      try {
         SearchResult result = findCompilationUnitInOpenJavaProjects(workspace);

         if (result == null) {
            showMessage(event, "Not Found", "Could not find " + targetRelativePath);
            return null;
         }

         // Step 1: Extract all locations (Bottom-up to prevent line shifts)
         Collections.sort(extractionTargets);
         for (ExtractionTarget target : extractionTargets) {
             applyExtractMethodRefactoring(event, result.compilationUnit, target);
         }

         // Step 2: Delete secondary methods using the Java Model API
         deleteSecondaryMethods(result.compilationUnit);

         // Step 3: Rename the function calls to point to the primary method
         renameSecondaryInvocations(result.compilationUnit);

         // Step 4: Move the primary extracted method to the requested location
         moveExtractedMethod(result.compilationUnit);

         // Reveal the primary method in the editor
         ExtractionTarget primary = extractionTargets.stream().filter(t -> t.isPrimary).findFirst().orElse(null);
         if (primary != null) {
             revealExtractedMethod(result.compilationUnit, primary.methodName);
         }

         System.out.println("Success: Extraction, deletion, rebinding, and relocation completed.");

      } catch (Exception e) {
         throw new ExecutionException("Failed to apply refactoring workflow.", e);
      }

      return null;
   }

   private void moveExtractedMethod(ICompilationUnit compilationUnit) throws Exception {
      String targetMethodName = parseTargetMethodName(extractedMethodLocation);
      if (targetMethodName == null) {
          System.out.println("Warning: Could not parse target method name from location string.");
          return;
      }

      String primaryMethodName = extractionTargets.stream()
          .filter(t -> t.isPrimary)
          .map(t -> t.methodName)
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("No primary target defined."));

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
              } else if (name.equals(targetMethodName)) {
                  destinationNode[0] = node;
              }
              return super.visit(node);
          }
      });

      if (parentType[0] != null && extractedNode[0] != null && destinationNode[0] != null) {
          ASTRewrite rewrite = ASTRewrite.create(astRoot.getAST());
          ListRewrite listRewrite =
              rewrite.getListRewrite(parentType[0], TypeDeclaration.BODY_DECLARATIONS_PROPERTY);

          ASTNode movePlaceholder = rewrite.createMoveTarget(extractedNode[0]);

          listRewrite.insertAfter(movePlaceholder, destinationNode[0], null);

          Document document = new Document(compilationUnit.getSource());
          TextEdit edits = rewrite.rewriteAST(document, null);
          edits.apply(document);

          compilationUnit.getBuffer().setContents(document.get());
          compilationUnit.reconcile(ICompilationUnit.NO_AST, false, null, null);
          compilationUnit.commitWorkingCopy(true, new NullProgressMonitor());
      } else {
          System.out.println("Warning: Could not locate either the extracted method or the target destination method in the AST.");
      }
   }

   /**
    * Parses "After void createBuildGradle(Path settings, Path gradleBuild, Set<String> deps)"
    * and returns "createBuildGradle".
    */
   private String parseTargetMethodName(String locationString) {
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

   private void applyExtractMethodRefactoring(ExecutionEvent event, ICompilationUnit compilationUnit, ExtractionTarget target) throws Exception {
      SourceRange sourceRange = computeSourceRange(compilationUnit, target.startLine, target.endLine);
      ExtractMethodRefactoring refactoring = new ExtractMethodRefactoring(compilationUnit, sourceRange.offset, sourceRange.length);

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

      // Reconcile after each extraction so the AST is fresh for the next loop iteration
      compilationUnit.reconcile(ICompilationUnit.NO_AST, false, null, null);
   }

   private void deleteSecondaryMethods(ICompilationUnit compilationUnit) throws Exception {
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

   private void renameSecondaryInvocations(ICompilationUnit compilationUnit) throws Exception {
      ASTParser parser = ASTParser.newParser(AST.JLS25);
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
          if (!target.isPrimary) secondaryNames.add(target.methodName);
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

   private void revealExtractedMethod(ICompilationUnit compilationUnit, String methodName) throws Exception {
      IMethod extractedMethod = findExtractedMethod(compilationUnit, methodName);
      if (extractedMethod == null || !extractedMethod.exists()) {
         return;
      }

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

   private IMethod findExtractedMethod(ICompilationUnit compilationUnit, String methodName) throws Exception {
      for (IType type : compilationUnit.getAllTypes()) {
         for (IMethod method : type.getMethods()) {
            if (methodName.equals(method.getElementName())) {
               return method;
            }
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
      if (targetLine <= 1) return 0;

      int currentLine = 1;
      for (int i = 0; i < source.length(); i++) {
         if (source.charAt(i) == '\n') {
            currentLine++;
            if (currentLine == targetLine) return i + 1;
         }
      }
      throw new IllegalArgumentException("Start line out of range: " + targetLine);
   }

   private int getLineEndOffset(String source, int targetLine) {
      int currentLine = 1;
      for (int i = 0; i < source.length(); i++) {
         if (source.charAt(i) == '\n') {
            if (currentLine == targetLine) return i + 1;
            currentLine++;
         }
      }
      if (currentLine == targetLine) return source.length();
      throw new IllegalArgumentException("End line out of range: " + targetLine);
   }

   private String getStatusMessage(RefactoringStatus status) {
      String message = status.getMessageMatchingSeverity(RefactoringStatus.FATAL);
      if (message != null) return message;

      message = status.getMessageMatchingSeverity(RefactoringStatus.ERROR);
      if (message != null) return message;

      message = status.getMessageMatchingSeverity(RefactoringStatus.WARNING);
      if (message != null) return message;

      return "Unknown refactoring status.";
   }

   private SearchResult findCompilationUnitInOpenJavaProjects(IWorkspace workspace) throws Exception {
      for (IProject project : workspace.getRoot().getProjects()) {
         if (!isOpenJavaProject(project)) continue;
         IJavaProject javaProject = JavaCore.create(project);
         SearchResult result = findCompilationUnitInJavaProject(javaProject);
         if (result != null) {
            result.project = project;
            return result;
         }
      }
      return null;
   }

   private SearchResult findCompilationUnitInJavaProject(IJavaProject javaProject) throws Exception {
      for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots()) {
         if (!isSourceRoot(root)) continue;
         SearchResult result = findCompilationUnitInSourceRoot(root);
         if (result != null) return result;
      }
      return null;
   }

   private SearchResult findCompilationUnitInSourceRoot(IPackageFragmentRoot root) throws Exception {
      for (IJavaElement element : root.getChildren()) {
         if (!(element instanceof IPackageFragment)) continue;
         IPackageFragment pkg = (IPackageFragment) element;
         SearchResult result = findCompilationUnitInPackage(pkg);
         if (result != null) return result;
      }
      return null;
   }

   private SearchResult findCompilationUnitInPackage(IPackageFragment pkg) throws Exception {
      for (ICompilationUnit cu : pkg.getCompilationUnits()) {
         String currentRelativePath = buildRelativePath(pkg, cu);
         if (targetRelativePath.equals(currentRelativePath)) {
            SearchResult result = new SearchResult();
            result.compilationUnit = cu;
            result.packageFragment = pkg;
            return result;
         }
      }
      return null;
   }

   private String buildRelativePath(IPackageFragment pkg, ICompilationUnit cu) {
      String packagePath = pkg.getElementName().replace('.', '/');
      if (packagePath.isEmpty()) return cu.getElementName();
      return packagePath + "/" + cu.getElementName();
   }

   private boolean isOpenJavaProject(IProject project) throws Exception {
      return project.isOpen() && project.hasNature(JavaCore.NATURE_ID);
   }

   private boolean isSourceRoot(IPackageFragmentRoot root) throws Exception {
      return root.getKind() == IPackageFragmentRoot.K_SOURCE;
   }

   private void showMessage(ExecutionEvent event, String title, String message) {
      MessageDialog.openInformation(HandlerUtil.getActiveShell(event), title, message);
   }

   private static class SearchResult {
      private IProject project;
      private IPackageFragment packageFragment;
      private ICompilationUnit compilationUnit;
   }

   private static class SourceRange {
      private final int offset;
      private final int length;

      private SourceRange(int offset, int length) {
         this.offset = offset;
         this.length = length;
      }
   }

   private static class ExtractionTarget implements Comparable<ExtractionTarget> {
      final int startLine;
      final int endLine;
      final String methodName;
      final boolean isPrimary;

      ExtractionTarget(int startLine, int endLine, String methodName, boolean isPrimary) {
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
}