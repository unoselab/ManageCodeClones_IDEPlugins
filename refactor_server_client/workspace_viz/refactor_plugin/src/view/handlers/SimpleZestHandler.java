package view.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.PlatformUI;

import view.SimpleZestView;

public class SimpleZestHandler extends AbstractHandler {

   // Updated to match the ID registered in the plugin.xml and the view class
   private static final String SIMPLEZESTVIEW = "view.SimpleZestView";

   @Override
   public Object execute(ExecutionEvent event) throws ExecutionException {
      try {
         IViewPart findPart = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().findView(SIMPLEZESTVIEW);

         if (findPart instanceof SimpleZestView) {
            SimpleZestView viewPart = (SimpleZestView) findPart;
            viewPart.setLayoutManager();
         }
      } catch (Exception e) {
         e.printStackTrace();
      }

      return null;
   }
}