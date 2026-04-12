package view.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;

public class CheckExtractableHandler extends AbstractHandler {

   @Override
   public Object execute(ExecutionEvent event) throws ExecutionException {
      try {
         IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();

         if (page == null) {
            return null;
         }

         /*
          * TODO:
          * Replace this stub with your actual "check extractable clone" logic.
          */

         System.out.println("[Clone Viz] Check Extractable Clone triggered.");
      } catch (Exception e) {
         throw new ExecutionException("Failed to run Check Extractable Clone.", e);
      }

      return null;
   }
}