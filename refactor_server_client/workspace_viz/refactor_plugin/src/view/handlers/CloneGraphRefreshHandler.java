package view.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;

import view.CloneGraphView;

public class CloneGraphRefreshHandler extends AbstractHandler {

   @Override
   public Object execute(ExecutionEvent event) throws ExecutionException {
      CloneGraphView view = CloneGraphView.getOpenView();
      if (view != null) {
         view.rebuildGraph();
      }
      return null;
   }
}