package refactor_plugin.model;

import java.util.HashMap;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;

/**
 * Process-wide singleton holding state shared between the CloneTreeView, DropzoneView, and EditorDropStartup — mirrors the two shared Maps in the VS Code extension's activate() function.
 */
public class CloneContext {

   private static final CloneContext INSTANCE = new CloneContext();
   private static final String PRJ_NAME = "camel-java";

   /** classid → CloneRecord; populated when the JSON file is loaded. */
   public final Map<String, CloneRecord> recordMap = new HashMap<>();

   /**
    * Absolute file path → classid of the clone group the file was opened from. Set by CloneTreeView when the user double-clicks a source node.
    */
   public final Map<String, String> lastOpenedByFile = new HashMap<>();

   /** UI focus from CloneGraphView to steer Dropzone/plan routing. */
   public volatile String preferredProject = null;
   public volatile String preferredClassName = null;
   public volatile String preferredClassId = null;
   
   /**
    * Absolute path to the workspace root (runtime-refactor_plugin/). Used to resolve relative file paths stored in all_refactor_results.json. Set by CloneTreeView when it loads the JSON.
    */
   public String workspaceRoot = "";

   private CloneContext() {
   }

   public static CloneContext get() {
      return INSTANCE;
   }

   /**
    * {@link #workspaceRoot} for a JSON file under {@code systems/}, legacy dirs, or flat layout.
    */
   public static String workspaceRootForCloneJson(String jsonPath) {
      java.io.File jf = new java.io.File(jsonPath);
      java.io.File dir = jf.getParentFile();
      if (dir == null) {
         return jf.getAbsolutePath();
      }
      String leaf = dir.getName();
      if ("systems".equals(leaf) || "clone_data".equals(leaf) || "project_target".equals(leaf) || "systems_bck".equals(leaf)) {
         java.io.File parent = dir.getParentFile();
         return parent != null ? parent.getAbsolutePath() : dir.getAbsolutePath();
      }
      return dir.getAbsolutePath();
   }

   public void setGraphFocus(String project, String className, String classId) {
      preferredProject = project;
      preferredClassName = className;
      preferredClassId = classId;
   }

   /**
    * Resolves a file path from the JSON. If the path is already absolute and exists, returns it as-is. Otherwise resolves it relative to workspaceRoot.
    */
   public String resolvePath(String filePath) {
      if (filePath == null || filePath.isBlank()) {
         return null;
      }

      String normalized = filePath.replace('\\', '/');

      String repoPrefix = "systems/" + PRJ_NAME + "/";
      if (normalized.startsWith(repoPrefix)) {
         normalized = normalized.substring(repoPrefix.length());
      }

      IWorkspace workspace = ResourcesPlugin.getWorkspace();
      String[] candidates = { "src/" + normalized };

      for (IProject project : workspace.getRoot().getProjects()) {
         if (project == null || !project.exists() || !project.isOpen()) {
            continue;
         }

         for (String candidate : candidates) {
            IFile file = project.getFile(new Path(candidate));
            if (file.exists() && file.getLocation() != null) {
               return file.getLocation().toOSString();
            }
         }
      }

      return null;
   }

   // public String resolvePath(String filePath) {
   // String PRJ_NAME = "systems/camel-java/";
   // String normalized = filePath.replace("systems/camel-java/", filePath);
   // // 1. Find the path of the current workspace.
   // // 2. Find the path of the current project based on Step 1.
   // // 3. Resolve all: the project path + filePath.
   // if (filePath == null || filePath.isBlank()) {
   // return filePath;
   // }
   // java.io.File f = new java.io.File(filePath);
   // if (f.isAbsolute() && f.exists()) {
   // return filePath;
   // }
   // return workspaceRoot.isEmpty() ? filePath : new java.io.File(workspaceRoot, filePath).getAbsolutePath();
   // }
}
