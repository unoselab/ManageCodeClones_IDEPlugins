package view.handlers; // Or whatever package you prefer for handlers

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;
import view.SimpleView;

public class ClearTextHandler extends AbstractHandler {

   @Override
   public Object execute(ExecutionEvent event) throws ExecutionException {
      // Find out which view triggered the command
      IWorkbenchPart activePart = HandlerUtil.getActivePart(event);

      // If it was triggered from our SimpleView, cast it and clear the text
      if (activePart instanceof SimpleView) {
         ((SimpleView) activePart).clearText();
      }

      return null; // Handlers typically return null
   }
}