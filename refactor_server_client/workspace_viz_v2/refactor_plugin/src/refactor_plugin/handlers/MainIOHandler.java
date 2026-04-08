package refactor_plugin.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;

public class MainIOHandler extends AbstractHandler {

   // Let us assume that we have this file location from the previous workflow step (JSON loading).
   private String targetRelativePath = "org/apache/A.java";

   @Override
   public Object execute(ExecutionEvent event) throws ExecutionException {
      IWorkspace workspace = ResourcesPlugin.getWorkspace();

      try {
         // Let us find this file from any opened Java project by using the iteration over all CompilationUnits.
         SearchResult result = findCompilationUnitInOpenJavaProjects(workspace);

         if (result == null) {
            showFileNotFoundMessage(event);
            return null;
         }

         String content = readCompilationUnitContent(result.compilationUnit);
         showFileContent(event, result.project.getName(), result.packageFragment.getElementName(), content);

      } catch (Exception e) {
         throw new ExecutionException("Failed to search target Java file via JDT", e);
      }

      return null;
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

   private String readCompilationUnitContent(ICompilationUnit compilationUnit) throws Exception {
      return compilationUnit.getSource();
   }

   private void showFileContent(ExecutionEvent event, String projectName, String packageName, String content) {
      MessageDialog.openInformation(HandlerUtil.getActiveShell(event), "Found " + targetRelativePath, "Project: " + projectName + "\nPackage: " + packageName + "\n\n" + content);
   }

   private void showFileNotFoundMessage(ExecutionEvent event) {
      MessageDialog.openError(HandlerUtil.getActiveShell(event), "Not Found", "Could not find " + targetRelativePath + " in any open Java project.");
   }

   private static class SearchResult {
      private IProject project;
      private IPackageFragment packageFragment;
      private ICompilationUnit compilationUnit;
   }
}