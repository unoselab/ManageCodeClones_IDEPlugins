package refactor_plugin.handlers;

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
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
// Eclipse JDT internal refactoring class
import org.eclipse.jdt.internal.corext.refactoring.code.ExtractMethodRefactoring;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ui.handlers.HandlerUtil;

import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.texteditor.ITextEditor;

public class ExtractMethodHandler extends AbstractHandler {

   // Let us assume that we have this file location from the previous workflow step (JSON loading).
   // Let us assume that we already know the extraction location from the previous workflow step.
   // private final String targetRelativePath = "org/apache/A.java";
   private final String targetRelativePath = "org/apache/camel/dsl/jbang/core/commands/ExportQuarkus.java";
   private final int extractStartLine = 316; // 6;
   private final int extractEndLine = 335; // 8;

   // Let us assume that we already know the new method name from the previous workflow step.
   private final String extractedMethodName = "extractedM1Block";

   @Override
   public Object execute(ExecutionEvent event) throws ExecutionException {
      IWorkspace workspace = ResourcesPlugin.getWorkspace();

      try {
         // Let us find this file from any opened Java project by using the iteration over all CompilationUnits.
         SearchResult result = findCompilationUnitInOpenJavaProjects(workspace);

         if (result == null) {
            showMessage(event, "Not Found", "Could not find " + targetRelativePath + " in any open Java project.");
            return null;
         }

         applyExtractMethodRefactoring(event, result.compilationUnit);
      } catch (Exception e) {
         throw new ExecutionException("Failed to apply Extract Method refactoring.", e);
      }

      return null;
   }

   private void applyExtractMethodRefactoring(ExecutionEvent event, ICompilationUnit compilationUnit) throws Exception {
      SourceRange sourceRange = computeSourceRange(compilationUnit, extractStartLine, extractEndLine);

      ExtractMethodRefactoring refactoring = new ExtractMethodRefactoring(compilationUnit, sourceRange.offset, sourceRange.length);

      refactoring.setMethodName(extractedMethodName);
      refactoring.setReplaceDuplicates(false);

      RefactoringStatus initialStatus = refactoring.checkInitialConditions(new NullProgressMonitor());
      if (initialStatus.hasError() || initialStatus.hasFatalError()) {
         showMessage(event, "Initial Condition Failed", getStatusMessage(initialStatus));
         return;
      }

      RefactoringStatus finalStatus = refactoring.checkFinalConditions(new NullProgressMonitor());
      if (finalStatus.hasError() || finalStatus.hasFatalError()) {
         showMessage(event, "Final Condition Failed", getStatusMessage(finalStatus));
         return;
      }

      Change change = refactoring.createChange(new NullProgressMonitor());
      change.perform(new NullProgressMonitor());

      // Let us refresh/reconcile the compilation unit after the refactoring change.
      compilationUnit.reconcile(ICompilationUnit.NO_AST, false, null, null);

      // Let us move the cursor to the extracted method in the Java editor.
      revealExtractedMethod(compilationUnit);

      System.out.println("Success: Extract Method refactoring was applied successfully." + //
            "\nFile: " + targetRelativePath + "\nExtracted lines: " + //
            extractStartLine + "-" + extractEndLine + "\nNew method name: " + extractedMethodName);
   }

   private void revealExtractedMethod(ICompilationUnit compilationUnit) throws Exception {
      IMethod extractedMethod = findExtractedMethod(compilationUnit);
      if (extractedMethod == null || !extractedMethod.exists()) {
         return;
      }

      IEditorPart editor = JavaUI.openInEditor(compilationUnit);
      JavaUI.revealInEditor(editor, (IJavaElement) extractedMethod);
      if (editor instanceof ITextEditor) {
         ITextEditor textEditor = (ITextEditor) editor;

         // Put the key cursor on the method name if possible.
         if (extractedMethod.getNameRange() != null) {
            textEditor.selectAndReveal(extractedMethod.getNameRange().getOffset(), extractedMethod.getNameRange().getLength());
         }
         else {
            textEditor.selectAndReveal(extractedMethod.getSourceRange().getOffset(), 0);
         }
      }
   }

   private IMethod findExtractedMethod(ICompilationUnit compilationUnit) throws Exception {
      for (IType type : compilationUnit.getAllTypes()) {
         for (IMethod method : type.getMethods()) {
            if (extractedMethodName.equals(method.getElementName())) {
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

   private int getLineEndOffset(String source, int targetLine) {
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

   private String getStatusMessage(RefactoringStatus status) {
      String message = status.getMessageMatchingSeverity(RefactoringStatus.FATAL);
      if (message != null) {
         return message;
      }

      message = status.getMessageMatchingSeverity(RefactoringStatus.ERROR);
      if (message != null) {
         return message;
      }

      message = status.getMessageMatchingSeverity(RefactoringStatus.WARNING);
      if (message != null) {
         return message;
      }

      return "Unknown refactoring status.";
   }

   private SearchResult findCompilationUnitInOpenJavaProjects(IWorkspace workspace) throws Exception {
      for (IProject project : workspace.getRoot().getProjects()) {
         if (!isOpenJavaProject(project)) {
            continue;
         }

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
         if (!isSourceRoot(root)) {
            continue;
         }

         SearchResult result = findCompilationUnitInSourceRoot(root);
         if (result != null) {
            return result;
         }
      }

      return null;
   }

   private SearchResult findCompilationUnitInSourceRoot(IPackageFragmentRoot root) throws Exception {
      for (IJavaElement element : root.getChildren()) {
         if (!(element instanceof IPackageFragment)) {
            continue;
         }

         IPackageFragment pkg = (IPackageFragment) element;
         SearchResult result = findCompilationUnitInPackage(pkg);

         if (result != null) {
            return result;
         }
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

      if (packagePath.isEmpty()) {
         return cu.getElementName();
      }

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
}