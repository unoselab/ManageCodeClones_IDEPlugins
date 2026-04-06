package refactor_plugin.handlers;

import java.util.List;

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

import refactor_plugin.util.MultiSiteJdtExtract.ExtractMethodHandlerDemo;
import refactor_plugin.util.MultiSiteJdtExtract;
import refactor_plugin.util.MultiSiteJdtExtract.Result;

/**
 * Menu command &quot;Command Action 02 (EM)&quot;: resolves the ExportQuarkus demo clone line ranges
 * (same logic as Dropzone) and runs {@link MultiSiteJdtExtract#applyWithLineRanges}. For two sites,
 * that is the same multi-step pipeline Eclipse documents: extract each clone with a
 * <strong>different</strong> temporary method name; rewrite invocations to one unified name;
 * remove the redundant extracted methods; rename to {@link ExtractMethodHandlerDemo#UNIFIED_METHOD_NAME}.
 * This handler does <strong>not</strong> read {@code all_refactor_results.json} or
 * {@link refactor_plugin.model.CloneContext}.
 * <p>
 * Dropzone on the same file uses the same {@link MultiSiteJdtExtract#resolveExportQuarkusDemoCloneRanges}
 * + {@link MultiSiteJdtExtract#applyWithLineRanges} call, then moves the unified declaration near
 * the user&apos;s drop ({@code EditorDropStartup}). Clone-record drops use JSON ranges with the
 * same JDT engine when the file matches {@code recordMap}.
 *
 * @see MultiSiteJdtExtract.ExtractMethodHandlerDemo shared path/ranges/name
 */
public class ExtractMethodHandler extends AbstractHandler {

   @Override
   public Object execute(ExecutionEvent event) throws ExecutionException {
      IWorkspace workspace = ResourcesPlugin.getWorkspace();

      try {
         ICompilationUnit cu = findCompilationUnitByJavaRelativePath(workspace,
               ExtractMethodHandlerDemo.TARGET_RELATIVE_PATH);
         if (cu == null) {
            showMessage(event, "Not Found",
                  "Could not find " + ExtractMethodHandlerDemo.TARGET_RELATIVE_PATH
                        + " in any open Java project.");
            return null;
         }

         List<int[]> ranges = MultiSiteJdtExtract.resolveExportQuarkusDemoCloneRanges(cu.getSource());
         Result r = MultiSiteJdtExtract.applyWithLineRanges(cu, ranges,
               ExtractMethodHandlerDemo.UNIFIED_METHOD_NAME);
         if (r.ok()) {
            MultiSiteJdtExtract.revealMethodInEditor(cu,
                  ExtractMethodHandlerDemo.UNIFIED_METHOD_NAME);
         }
         MessageDialog.openInformation(HandlerUtil.getActiveShell(event), r.title(), r.detail());

      } catch (Exception e) {
         throw new ExecutionException("Failed to apply Extract Method refactoring.", e);
      }

      return null;
   }

   private static ICompilationUnit findCompilationUnitByJavaRelativePath(IWorkspace workspace,
         String targetRelativePath) throws Exception {
      for (var project : workspace.getRoot().getProjects()) {
         if (!project.isOpen() || !project.hasNature(JavaCore.NATURE_ID)) {
            continue;
         }
         IJavaProject javaProject = JavaCore.create(project);
         for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) {
               continue;
            }
            for (IJavaElement element : root.getChildren()) {
               if (!(element instanceof IPackageFragment pkg)) {
                  continue;
               }
               for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                  if (targetRelativePath.equals(buildRelativePath(pkg, cu))) {
                     return cu;
                  }
               }
            }
         }
      }
      return null;
   }

   private static String buildRelativePath(IPackageFragment pkg, ICompilationUnit cu) {
      String packagePath = pkg.getElementName().replace('.', '/');
      if (packagePath.isEmpty()) {
         return cu.getElementName();
      }
      return packagePath + "/" + cu.getElementName();
   }

   private void showMessage(ExecutionEvent event, String title, String message) {
      MessageDialog.openInformation(HandlerUtil.getActiveShell(event), title, message);
   }
}
