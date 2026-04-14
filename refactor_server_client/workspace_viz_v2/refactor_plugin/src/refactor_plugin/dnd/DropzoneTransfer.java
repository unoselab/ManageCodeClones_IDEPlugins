package refactor_plugin.dnd;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import org.eclipse.swt.dnd.ByteArrayTransfer;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.TransferData;

import view.clone.CloneDragPayload;

/**
 * Custom SWT transfer type that tags a drag as originating from the Dropzone view. This lets EditorDropStartup distinguish a Dropzone drag (→ clone-aware or generic-wrap path) from an ordinary editor-to-editor text drag.
 *
 * The payload is a UTF-8 encoded snippet string.
 */
public class DropzoneTransfer extends ByteArrayTransfer {

   // private static final String TYPE_NAME = "refactor_plugin.dropzone_snippet";
   private static final String TYPE_NAME = "refactor_plugin.dropzone_payload";
   private static final int TYPE_ID = registerType(TYPE_NAME);
   private static final DropzoneTransfer INSTANCE = new DropzoneTransfer();

   private DropzoneTransfer() {
   }

   public static DropzoneTransfer getInstance() {
      return INSTANCE;
   }

   @Override
   protected String[] getTypeNames() {
      return new String[] { TYPE_NAME };
   }

   @Override
   protected int[] getTypeIds() {
      return new int[] { TYPE_ID };
   }

   @Override
   protected void javaToNative(Object object, TransferData transferData) {
      System.out.println("[DBG] DropzoneTransfer.javaToNative object=" + (object == null ? "null" : object.getClass().getName()));

      if (!(object instanceof CloneDragPayload payload)) {
         DND.error(DND.ERROR_INVALID_DATA);
         return;
      }

      try {
         ByteArrayOutputStream bos = new ByteArrayOutputStream();
         try (ObjectOutputStream out = new ObjectOutputStream(bos)) {
            out.writeObject(payload);
         }

         byte[] bytes = bos.toByteArray();

         System.out.println("[DBG] DropzoneTransfer.javaToNative bytes=" + bytes.length);

         if (bytes.length == 0) {
            DND.error(DND.ERROR_INVALID_DATA);
            return;
         }

         super.javaToNative(bytes, transferData);
      } catch (Exception e) {
         e.printStackTrace();
         DND.error(DND.ERROR_INVALID_DATA);
      }
   }

   @Override
   protected Object nativeToJava(TransferData transferData) {
      try {
         Object raw = super.nativeToJava(transferData);
         System.out.println("[DBG] DropzoneTransfer.nativeToJava raw=" + (raw == null ? "null" : raw.getClass().getName()));

         if (!(raw instanceof byte[] bytes) || bytes.length == 0) {
            System.out.println("[DBG] DropzoneTransfer.nativeToJava bytes=null-or-empty");
            return null;
         }

         try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            Object obj = in.readObject();
            System.out.println("[DBG] DropzoneTransfer.nativeToJava returned=" + (obj == null ? "null" : obj.getClass().getName()));
            return obj;
         }
      } catch (Exception e) {
         e.printStackTrace();
         return null;
      }
   }

   /*   @Override
   protected void javaToNative(Object object, TransferData transferData) {
      if (!(object instanceof String))
         return;
      try {
         super.javaToNative(((String) object).getBytes("UTF-8"), transferData);
      } catch (java.io.UnsupportedEncodingException e) {
          UTF-8 always supported  }
   }
   
   @Override
   protected Object nativeToJava(TransferData transferData) {
      byte[] bytes = (byte[]) super.nativeToJava(transferData);
      if (bytes == null)
         return null;
      try {
         return new String(bytes, "UTF-8");
      } catch (java.io.UnsupportedEncodingException e) {
         return null;
      }
   }*/
}
