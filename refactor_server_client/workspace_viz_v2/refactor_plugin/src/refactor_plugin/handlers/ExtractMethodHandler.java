package refactor_plugin.handlers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;

import refactor_plugin.handlers.extract.ExtractionTarget;
import refactor_plugin.util.ExtractMethodService;

public class ExtractMethodHandler extends AbstractHandler {

    private final String targetRelativePath = "org/apache/camel/dsl/jbang/core/commands/ExportQuarkus.java";
    private final String extractedMethodLocation = "After void replaceQuarkusDependencies(List<MavenGav> gavs)";

    private final List<ExtractionTarget> extractionTargets = new ArrayList<>(Arrays.asList(
          new ExtractionTarget(316, 335, "extractedM1Block1", true), 
          new ExtractionTarget(476, 495, "extractedM1Block2", false) 
    ));

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        ExtractMethodService refactorService = new ExtractMethodService();

        try {
            // Find the Compilation Unit
            ICompilationUnit cu = refactorService.findCompilationUnitInOpenJavaProjects(workspace, targetRelativePath);

            if (cu == null) {
                showMessage(event, "Not Found", "Could not find " + targetRelativePath);
                return null;
            }

            // Execute the extracted, modular logic
            refactorService.performExtraction(cu, extractionTargets, extractedMethodLocation);

            System.out.println("Success: Extraction, deletion, rebinding, and relocation completed.");

        } catch (Exception e) {
            throw new ExecutionException("Failed to apply refactoring workflow.", e);
        }

        return null;
    }

    private void showMessage(ExecutionEvent event, String title, String message) {
        MessageDialog.openInformation(HandlerUtil.getActiveShell(event), title, message);
    }
}