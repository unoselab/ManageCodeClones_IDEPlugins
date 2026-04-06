package refactor_plugin.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;

/**
 * After Command Action 02 (EM), moves {@code extractedM1Block} to a valid <em>class member</em>
 * position: right after the method that encloses the user's drop offset, or after the previous
 * method if the drop fell in whitespace between members. Refactoring edits stay identical to the
 * menu command; only the extracted method's declaration moves.
 */
public final class DemoExtractRelocation {

    /** @deprecated Legacy anchor; stripped if still present in a buffer. */
    @Deprecated
    public static final String ANCHOR_LINE = "// __refactor_plugin_drop_anchor__";

    private DemoExtractRelocation() {}

    private static String defaultLineDelimiter(IDocument doc) {
        String s = doc.get();
        int n = Math.min(s.length(), 8192);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\r') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    return "\r\n";
                }
                return "\r";
            }
            if (c == '\n') {
                return "\n";
            }
        }
        return System.lineSeparator();
    }

    /** Removes legacy anchor lines if present. */
    @Deprecated
    public static void stripAnchorIfPresent(IDocument doc) {
        try {
            String full = doc.get();
            int idx = full.indexOf(ANCHOR_LINE);
            if (idx < 0) {
                return;
            }
            int lineEnd = full.indexOf('\n', idx);
            int removeLen = lineEnd >= 0 ? lineEnd - idx + 1 : full.length() - idx;
            doc.replace(idx, removeLen, "");
        } catch (BadLocationException ignored) {
            /* keep buffer unchanged */
        }
    }

    /**
     * Cuts {@code methodName} from JDT's placement and reinserts it after the enclosing method
     * (or other valid gap) for {@code userDropOffset}.
     *
     * @return {@code false} if parsing failed or the method was not found
     */
    public static boolean relocateExtractedMethodNearUserDrop(IDocument doc, String methodName,
            int userDropOffset) {
        stripAnchorIfPresent(doc);
        try {
            String src = doc.get();
            ASTParser parser = ASTParser.newParser(AST.JLS21);
            parser.setSource(src.toCharArray());
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setResolveBindings(false);
            ASTNode root = parser.createAST(null);
            if (!(root instanceof CompilationUnit cuNode)) {
                return false;
            }
            TypeDeclaration td = primaryTypeDeclaration(cuNode);
            if (td == null) {
                return false;
            }
            List<MethodDeclaration> methods = methodsInType(td);
            MethodDeclaration moved = findMethodByName(methods, methodName);
            if (moved == null) {
                return false;
            }
            int m0 = moved.getStartPosition();
            int m1 = m0 + moved.getLength();
            int insertOrig = computeMemberInsertOffset(td, methods, moved, userDropOffset, src);
            if (insertOrig < 0) {
                return false;
            }
            if (insertOrig == m1) {
                return true;
            }

            String methodText = src.substring(m0, m1);
            int ins = mapOffsetAfterRemovingRange(insertOrig, m0, m1);
            StringBuilder stripped = stripOneRange(src, m0, m1);
            String delim = defaultLineDelimiter(doc);
            String memberIndent = leadingIndentAtResultOffset(stripped, ins);
            String body = reindentMethodBlock(methodText, memberIndent);
            stripped.insert(ins, delim + body + delim);
            doc.replace(0, doc.getLength(), stripped.toString());
            return true;
        } catch (BadLocationException e) {
            System.err.println("[refactor_plugin] relocate demo method: " + e.getMessage());
            return false;
        }
    }

    private static TypeDeclaration primaryTypeDeclaration(CompilationUnit cu) {
        for (Object t : cu.types()) {
            if (t instanceof TypeDeclaration td && !td.isInterface()) {
                return td;
            }
        }
        return null;
    }

    private static List<MethodDeclaration> methodsInType(TypeDeclaration td) {
        List<MethodDeclaration> out = new ArrayList<>();
        for (Object o : td.bodyDeclarations()) {
            if (o instanceof MethodDeclaration md) {
                out.add(md);
            }
        }
        return out;
    }

    private static MethodDeclaration findMethodByName(List<MethodDeclaration> methods,
            String methodName) {
        for (MethodDeclaration m : methods) {
            if (methodName.equals(m.getName().getIdentifier())) {
                return m;
            }
        }
        return null;
    }

    /**
     * Offset in the <em>original</em> source where the moved method should appear (start of
     * insertion): immediately after the closing brace of the method that contains the drop, or a
     * valid neighbour gap.
     */
    private static int computeMemberInsertOffset(TypeDeclaration td,
            List<MethodDeclaration> methods, MethodDeclaration moved, int userDrop, String src) {
        userDrop = Math.max(0, Math.min(userDrop, src.length()));
        int m0 = moved.getStartPosition();
        int m1 = m0 + moved.getLength();

        if (userDrop >= m0 && userDrop < m1) {
            int idx = methods.indexOf(moved);
            if (idx > 0) {
                MethodDeclaration prev = methods.get(idx - 1);
                return prev.getStartPosition() + prev.getLength();
            }
            return insertAfterTypeOpeningBrace(td, src);
        }

        for (MethodDeclaration m : methods) {
            if (m == moved) {
                continue;
            }
            int s = m.getStartPosition();
            int e = s + m.getLength();
            if (userDrop >= s && userDrop < e) {
                return e;
            }
        }

        List<MethodDeclaration> ordered = new ArrayList<>(methods);
        ordered.sort(Comparator.comparingInt(BodyDeclaration::getStartPosition));
        for (int i = 0; i < ordered.size(); i++) {
            MethodDeclaration m = ordered.get(i);
            int s = m.getStartPosition();
            if (userDrop < s) {
                if (i == 0) {
                    return insertAfterTypeOpeningBrace(td, src);
                }
                MethodDeclaration prev = ordered.get(i - 1);
                return prev.getStartPosition() + prev.getLength();
            }
        }
        if (ordered.isEmpty()) {
            return insertAfterTypeOpeningBrace(td, src);
        }
        MethodDeclaration last = ordered.get(ordered.size() - 1);
        return last.getStartPosition() + last.getLength();
    }

    private static int insertAfterTypeOpeningBrace(TypeDeclaration td, String src) {
        int from = td.getStartPosition();
        int open = src.indexOf('{', from);
        if (open < 0) {
            return from;
        }
        int p = open + 1;
        while (p < src.length() && Character.isWhitespace(src.charAt(p))) {
            p++;
        }
        return p;
    }

    private static int mapOffsetAfterRemovingRange(int insertOrig, int cut0, int cut1) {
        int len = cut1 - cut0;
        if (insertOrig <= cut0) {
            return insertOrig;
        }
        if (insertOrig >= cut1) {
            return insertOrig - len;
        }
        return cut0;
    }

    private static StringBuilder stripOneRange(String s, int r0, int r1) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            if (i >= r0 && i < r1) {
                continue;
            }
            sb.append(s.charAt(i));
        }
        return sb;
    }

    private static String leadingIndentAtResultOffset(CharSequence stripped, int insertPos) {
        int p = Math.min(insertPos, stripped.length());
        int ls = 0;
        for (int i = p - 1; i >= 0; i--) {
            if (stripped.charAt(i) == '\n') {
                ls = i + 1;
                break;
            }
        }
        int k = ls;
        while (k < stripped.length() && (stripped.charAt(k) == ' '
                || stripped.charAt(k) == '\t')) {
            k++;
        }
        if (k > ls) {
            return stripped.subSequence(ls, k).toString();
        }
        return "    ";
    }

    private static String reindentMethodBlock(String method, String indentPrefix) {
        String n = method.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = n.split("\n", -1);
        int minIndent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                continue;
            }
            int k = 0;
            while (k < line.length() && (line.charAt(k) == ' ' || line.charAt(k) == '\t')) {
                k++;
            }
            minIndent = Math.min(minIndent, k);
        }
        if (minIndent == Integer.MAX_VALUE) {
            minIndent = 0;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            String line = lines[i];
            if (line.trim().isEmpty()) {
                continue;
            }
            sb.append(indentPrefix);
            int cut = Math.min(minIndent, line.length());
            sb.append(line.substring(cut));
        }
        return sb.toString();
    }
}
