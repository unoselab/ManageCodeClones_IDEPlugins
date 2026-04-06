package refactor_plugin.dnd;

import org.eclipse.swt.dnd.ByteArrayTransfer;
import org.eclipse.swt.dnd.TransferData;

/**
 * Custom SWT transfer type that tags a drag as originating from the Dropzone
 * view. This lets EditorDropStartup distinguish a Dropzone drag (→ clone-aware
 * or generic-wrap path) from an ordinary editor-to-editor text drag.
 *
 * The payload is a UTF-8 encoded snippet string.
 */
public class DropzoneTransfer extends ByteArrayTransfer {

    private static final String TYPE_NAME = "refactor_plugin.dropzone_snippet";
    private static final int    TYPE_ID   = registerType(TYPE_NAME);
    private static final DropzoneTransfer INSTANCE = new DropzoneTransfer();

    private DropzoneTransfer() {}

    public static DropzoneTransfer getInstance() { return INSTANCE; }

    @Override protected String[] getTypeNames() { return new String[]{ TYPE_NAME }; }
    @Override protected int[]    getTypeIds()   { return new int[]{ TYPE_ID }; }

    @Override
    protected void javaToNative(Object object, TransferData transferData) {
        if (!(object instanceof String)) return;
        try {
            super.javaToNative(((String) object).getBytes("UTF-8"), transferData);
        } catch (java.io.UnsupportedEncodingException e) { /* UTF-8 always supported */ }
    }

    @Override
    protected Object nativeToJava(TransferData transferData) {
        byte[] bytes = (byte[]) super.nativeToJava(transferData);
        if (bytes == null) return null;
        try {
            return new String(bytes, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) { return null; }
    }

    /**
     * Public bridge so drop targets can test {@code event.currentDataType} (same pattern as
     * {@link org.eclipse.swt.dnd.TextTransfer#getInstance()}).
     */
    @Override
    public boolean isSupportedType(TransferData transferData) {
        return super.isSupportedType(transferData);
    }
}
