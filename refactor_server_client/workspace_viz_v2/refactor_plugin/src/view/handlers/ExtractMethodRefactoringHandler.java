package view.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;

public class ExtractMethodRefactoringHandler extends AbstractHandler {

   @Override
   public Object execute(ExecutionEvent event) throws ExecutionException {
      try {
         IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();

         if (page == null) {
            return null;
         }

         System.out.println("[Clone Viz] Extract Method Refactoring triggered.");
      } catch (Exception e) {
         throw new ExecutionException("Failed to run Extract Method Refactoring.", e);
      }

      return null;
   }
}