package view;

import org.eclipse.jface.action.MenuManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.ui.part.ViewPart;

import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.ui.IWorkbenchActionConstants;

public class SimpleView extends ViewPart {

   StyledText styledText;
   public static final String ID = "simplezestproject1.partdescriptor.simpleview";
   public final static String POPUPMENU = "simplezestproject1.popupmenu.mypopupmenu";

   public SimpleView() {
   }

   @Override
   public void createPartControl(Composite parent) {
      parent.setLayout(new FillLayout(SWT.HORIZONTAL));
      styledText = new StyledText(parent, SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL);

      // Register context menu the Eclipse 3 way
      MenuManager menuManager = new MenuManager("Context Menu", POPUPMENU);

      menuManager.setRemoveAllWhenShown(true);
      menuManager.addMenuListener(new IMenuListener() {
         @Override
         public void menuAboutToShow(IMenuManager manager) {
            // This acts as the anchor point for your plugin.xml contributions
            manager.add(new Separator(IWorkbenchActionConstants.MB_ADDITIONS));
         }
      });

      Menu menu = menuManager.createContextMenu(styledText);
      styledText.setMenu(menu);

      // Register with the site so extensions in plugin.xml can contribute to it
      // Passing null for the selection provider since StyledText doesn't natively implement ISelectionProvider
      getSite().registerContextMenu(POPUPMENU, menuManager, null);
   }

   public void appendText(String s) {
      if (styledText != null && !styledText.isDisposed()) {
         this.styledText.append(s);
      }
   }

   @Override
   public void dispose() {
      super.dispose();
   }

   @Override
   public void setFocus() {
      if (styledText != null && !styledText.isDisposed()) {
         this.styledText.setFocus();
      }
   }

   public void clearText() {
      if (styledText != null && !styledText.isDisposed()) {
         this.styledText.setText("");
      }
   }
}