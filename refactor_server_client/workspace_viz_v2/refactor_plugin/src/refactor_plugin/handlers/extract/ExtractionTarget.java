package refactor_plugin.handlers.extract;

import java.io.Serializable;

public class ExtractionTarget implements Comparable<ExtractionTarget>, Serializable {
   private static final long serialVersionUID = 1L;

   private final int startLine;
   private final int endLine;
   private final String methodName;
   private final boolean isPrimary;

   public ExtractionTarget(int startLine, int endLine, String methodName, boolean isPrimary) {
      this.startLine = startLine;
      this.endLine = endLine;
      this.methodName = methodName;
      this.isPrimary = isPrimary;
   }

   public int getStartLine() {
      return startLine;
   }

   public int getEndLine() {
      return endLine;
   }

   public String getMethodName() {
      return methodName;
   }

   public boolean isPrimary() {
      return isPrimary;
   }

   @Override
   public int compareTo(ExtractionTarget other) {
      // Sort bottom-up to prevent line shifting during multiple extractions
      return Integer.compare(other.startLine, this.startLine);
   }
}