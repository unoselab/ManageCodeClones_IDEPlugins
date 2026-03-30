package view;

import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Item;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.zest.core.widgets.Graph;
import org.eclipse.zest.core.widgets.GraphConnection;
import org.eclipse.zest.core.widgets.GraphNode;
import org.eclipse.zest.core.widgets.ZestStyles;
import org.eclipse.zest.layouts.algorithms.SpringLayoutAlgorithm;
import org.eclipse.zest.layouts.algorithms.TreeLayoutAlgorithm;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.Label;

public class SimpleZestView extends ViewPart {

   public static final String ID = "view.SimpleZestView"; // Good practice to define the ID in e3

   private Graph graph;
   private int layout = 1;

   GraphNode root = null, methodA = null, clone1 = null, clone2 = null;

   public SimpleZestView() {
   }

   /**
    * Create contents of the view part.
    */
   @Override
   public void createPartControl(Composite parent) {
      graph = new Graph(parent, SWT.NONE); // Graph will hold all other objects
      createGraphNode();
      createConnection(parent);
      graph.setLayoutAlgorithm(new TreeLayoutAlgorithm(), true);
      addSelectionListener();
   }

   void createGraphNode() {
      root = new GraphNode(graph, SWT.NONE);
      root.setText("Root");

      methodA = new GraphNode(graph, SWT.NONE);
      methodA.setText("methodA");

      clone1 = new GraphNode(graph, SWT.NONE);
      clone1.setText("Clone1");

      clone2 = new GraphNode(graph, SWT.NONE);
      clone2.setText("Clone2");
   }

   void createConnection(Composite parent) {
      // new GraphConnection(graph, ZestStyles.CONNECTIONS_DIRECTED, root, methodA);
      GraphConnection graphConnection = new GraphConnection(graph, SWT.NONE, root, methodA);
      new GraphConnection(graph, ZestStyles.CONNECTIONS_DOT, methodA, clone1);
      new GraphConnection(graph, ZestStyles.CONNECTIONS_DOT, methodA, clone2);
      // new GraphConnection(graph, SWT.NONE, clone1, root);

      // Change line color and line width
      // graphConnection.changeLineColor(parent.getDisplay().getSystemColor(SWT.COLOR_BLACK));
      // Also set a text
      graphConnection.setText("This is a text");
      graphConnection.setHighlightColor(parent.getDisplay().getSystemColor(SWT.COLOR_RED));
      // graphConnection.setLineWidth(3);
      IFigure figure = graphConnection.getConnectionFigure();
      for (Object child : figure.getChildren()) {
         if (child instanceof Label) {
            Label label = (Label) child;
            // Overrides the inherited green line color to make the text black
            label.setForegroundColor(parent.getDisplay().getSystemColor(SWT.COLOR_BLACK));
         }
      }
   }

   private void addSelectionListener() {
      // Selection listener on graphConnect
      SelectionAdapter selectionAdapter = new SelectionAdapter() {
         @Override
         public void widgetSelected(SelectionEvent e) {
            List<?> list = ((Graph) e.widget).getSelection();
            for (Object o : list) {
               if (o instanceof GraphNode) {
                  GraphNode iNode = (GraphNode) o;
                  findUpdateSimpleView(iNode);
               }
            }
         }
      };
      graph.addSelectionListener(selectionAdapter);
   }

   void findUpdateSimpleView(Item iNode) {
      // Find a view using e3 PlatformUI standard
      try {
         IViewPart view = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().findView(SimpleView.ID);
         if (view instanceof SimpleView) {
            SimpleView simpleView = (SimpleView) view;
            simpleView.appendText(iNode.getText() + "\n");
         }
      } catch (Exception e) {
         // Failsafe if the window or page isn't currently accessible
         e.printStackTrace();
      }
   }

   @Override
   public void dispose() {
      super.dispose();
   }

   public void setLayoutManager() {
      switch (layout) {
      case 1:
         graph.setLayoutAlgorithm(new TreeLayoutAlgorithm(TreeLayoutAlgorithm.LEFT_RIGHT), true);
         layout++;
         break;
      case 2:
         graph.setLayoutAlgorithm(new TreeLayoutAlgorithm(TreeLayoutAlgorithm.TOP_DOWN), true);
         layout = 1;
         break;
      }
   }

   @Override
   public void setFocus() {
      if (this.graph != null && !this.graph.isDisposed()) {
         this.graph.setFocus();
      }
   }
}