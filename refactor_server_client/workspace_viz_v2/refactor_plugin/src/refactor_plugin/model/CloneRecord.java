package refactor_plugin.model;

import java.io.Serializable;
import java.util.List;

/** Mirrors the CloneRecord interface from the VS Code extension's extension.ts. */
public class CloneRecord implements Serializable {
   private static final long serialVersionUID = 1L;

   public String classid;
   public String project;
   public String inspection_case;
   public String refactoring_type;
   public int nclones;
   public int same_file;
   public int Refactorable;
   public List<CloneSource> sources;
   public List<UpdatedFile> updated_files;
   public ExtractedMethod extracted_method;

   public static class EnclosingFunction implements Serializable {
      private static final long serialVersionUID = 1L;

      public String qualified_name;
      public String fun_range;
      public int fun_nlines;
      public String func_code;
   }

   public static class CloneSource implements Serializable {
      private static final long serialVersionUID = 1L;

      public String func_id;
      public String file;
      public String range;
      public int nlines;
      public String code;
      public String replacement_code;
      public EnclosingFunction enclosing_function;
   }

   public static class UpdatedFile implements Serializable {
      private static final long serialVersionUID = 1L;

      public String file;
      public boolean inserted_extracted_method;
      public String rewritten_file_path;
   }

   public static class ExtractedMethod implements Serializable {
      private static final long serialVersionUID = 1L;

      public String method_name;
      public String code;
   }
}