package refactor_plugin.handlers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;

import com.google.gson.Gson;

import refactor_plugin.model.CloneContext;
import refactor_plugin.model.CloneRecord;
import refactor_plugin.util.MultiSiteJdtExtract.ExtractMethodHandlerDemo;
import refactor_plugin.util.MultiSiteJdtExtract;
import refactor_plugin.util.MultiSiteJdtExtract.Result;

/**
 * Menu command &quot;Command Action 02 (EM)&quot;: resolves the ExportQuarkus demo clone line ranges
 * (same logic as Dropzone) and runs {@link MultiSiteJdtExtract#applyWithLineRanges}. For two sites,
 * that is the same multi-step pipeline Eclipse documents: extract each clone with a
 * <strong>different</strong> temporary method name; rewrite invocations to one unified name;
 * remove the redundant extracted methods; rename to {@link ExtractMethodHandlerDemo#UNIFIED_METHOD_NAME}.
 * Planning prefers server-provided target/ranges; fallback uses clone JSON/context from the plugin.
 * <p>
 * Dropzone on the same file uses the same {@link MultiSiteJdtExtract#resolveExportQuarkusDemoCloneRanges}
 * + {@link MultiSiteJdtExtract#applyWithLineRanges} call, then moves the unified declaration near
 * the user&apos;s drop ({@code EditorDropStartup}). Clone-record drops use JSON ranges with the
 * same JDT engine when the file matches {@code recordMap}.
 *
 * @see MultiSiteJdtExtract.ExtractMethodHandlerDemo shared path/ranges/name
 */
public class ExtractMethodHandler extends AbstractHandler {

   private static final String SERVER_PLAN_URL = "http://localhost:8000/extract-method-plan";
   private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
   private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
   private static final Gson GSON = new Gson();

   @Override
   public Object execute(ExecutionEvent event) throws ExecutionException {
      IWorkspace workspace = ResourcesPlugin.getWorkspace();

      try {
         ExtractPlan plan = resolveExtractPlan(workspace);
         ICompilationUnit cu = findCompilationUnitByJavaRelativePath(workspace, plan.targetRelativePath);
         if (cu == null) {
            showMessage(event, "Not Found",
                  "Could not find " + plan.targetRelativePath
                        + " in any open Java project.");
            return null;
         }

         List<int[]> ranges = plan.ranges != null && !plan.ranges.isEmpty()
               ? plan.ranges
               : MultiSiteJdtExtract.resolveExportQuarkusDemoCloneRanges(cu.getSource());
         String methodName = (plan.unifiedMethodName != null && !plan.unifiedMethodName.isBlank())
               ? plan.unifiedMethodName
               : ExtractMethodHandlerDemo.UNIFIED_METHOD_NAME;
         Result r = MultiSiteJdtExtract.applyWithLineRanges(cu, ranges, methodName);
         if (r.ok()) {
            MultiSiteJdtExtract.revealMethodInEditor(cu, methodName);
         }
         String detail = plan.serverMessage == null || plan.serverMessage.isBlank()
               ? r.detail()
               : plan.serverMessage + "\n\n" + r.detail();
         MessageDialog.openInformation(HandlerUtil.getActiveShell(event), r.title(), detail);

      } catch (Exception e) {
         throw new ExecutionException("Failed to apply Extract Method refactoring.", e);
      }

      return null;
   }

   private static ICompilationUnit findCompilationUnitByJavaRelativePath(IWorkspace workspace,
         String targetRelativePath) throws Exception {
      for (var project : workspace.getRoot().getProjects()) {
         if (!project.isOpen() || !project.hasNature(JavaCore.NATURE_ID)) {
            continue;
         }
         IJavaProject javaProject = JavaCore.create(project);
         for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) {
               continue;
            }
            for (IJavaElement element : root.getChildren()) {
               if (!(element instanceof IPackageFragment pkg)) {
                  continue;
               }
               for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                  if (targetRelativePath.equals(buildRelativePath(pkg, cu))) {
                     return cu;
                  }
               }
            }
         }
      }
      return null;
   }

   private static String buildRelativePath(IPackageFragment pkg, ICompilationUnit cu) {
      String packagePath = pkg.getElementName().replace('.', '/');
      if (packagePath.isEmpty()) {
         return cu.getElementName();
      }
      return packagePath + "/" + cu.getElementName();
   }

   private void showMessage(ExecutionEvent event, String title, String message) {
      MessageDialog.openInformation(HandlerUtil.getActiveShell(event), title, message);
   }

   private static ExtractPlan resolveExtractPlan(IWorkspace workspace) {
      try {
         ExtractPlan serverPlan = requestPlanFromServer(workspace);
         if (serverPlan != null && serverPlan.isUsable()) {
            return serverPlan;
         }
      } catch (Exception e) {
         System.err.println("[refactor_plugin] extract-method-plan fallback to local defaults: "
               + e.getMessage());
      }
      return defaultPlan();
   }

   private static ExtractPlan defaultPlan() {
      CloneContext ctx = CloneContext.get();
      CloneRecord selected = null;
      if (ctx.preferredClassId != null && !ctx.preferredClassId.isBlank()) {
         selected = ctx.recordMap.get(ctx.preferredClassId);
      }
      if (selected == null) {
         for (CloneRecord r : ctx.recordMap.values()) {
            if (ctx.preferredProject != null && !ctx.preferredProject.isBlank()
                  && !ctx.preferredProject.equals(r.project)) {
               continue;
            }
            selected = r;
            break;
         }
      }
      if (selected != null) {
         ExtractPlan fromRecord = planFromRecord(selected);
         if (fromRecord != null && fromRecord.isUsable()) {
            return fromRecord;
         }
      }
      ExtractPlan p = new ExtractPlan();
      p.targetRelativePath = ExtractMethodHandlerDemo.TARGET_RELATIVE_PATH;
      p.unifiedMethodName = ExtractMethodHandlerDemo.UNIFIED_METHOD_NAME;
      return p;
   }

   private static ExtractPlan planFromRecord(CloneRecord r) {
      if (r == null || r.sources == null || r.sources.isEmpty()) {
         return null;
      }
      Map<String, List<int[]>> byFile = new LinkedHashMap<>();
      for (var s : r.sources) {
         if (s == null || s.file == null || s.range == null || s.range.isBlank()) {
            continue;
         }
         int[] pr = parseRange(s.range);
         if (pr == null) {
            continue;
         }
         byFile.computeIfAbsent(s.file, k -> new ArrayList<>()).add(pr);
      }
      if (byFile.isEmpty()) {
         return null;
      }
      String bestFile = null;
      List<int[]> bestRanges = null;
      for (var e : byFile.entrySet()) {
         if (bestRanges == null || e.getValue().size() > bestRanges.size()) {
            bestFile = e.getKey();
            bestRanges = e.getValue();
         }
      }
      if (bestFile == null || bestRanges == null || bestRanges.isEmpty()) {
         return null;
      }

      ExtractPlan p = new ExtractPlan();
      p.targetRelativePath = javaRelativePathFromJsonFile(bestFile);
      p.unifiedMethodName = r.extracted_method != null
            && r.extracted_method.method_name != null
            && !r.extracted_method.method_name.isBlank()
                  ? r.extracted_method.method_name
                  : ExtractMethodHandlerDemo.UNIFIED_METHOD_NAME;
      p.ranges = bestRanges;
      p.serverMessage = "Local plan from clone JSON/context";
      return p;
   }

   private static int[] parseRange(String range) {
      try {
         String[] parts = range.split("-");
         if (parts.length < 2) {
            return null;
         }
         return new int[] {
               Integer.parseInt(parts[0].trim()),
               Integer.parseInt(parts[1].trim())
         };
      } catch (Exception e) {
         return null;
      }
   }

   private static String javaRelativePathFromJsonFile(String file) {
      if (file == null || file.isBlank()) {
         return ExtractMethodHandlerDemo.TARGET_RELATIVE_PATH;
      }
      String n = file.replace('\\', '/');
      int src = n.indexOf("/src/");
      if (src >= 0) {
         return n.substring(src + 5);
      }
      int org = n.indexOf("/org/");
      if (org >= 0) {
         return n.substring(org + 1);
      }
      java.nio.file.Path p = java.nio.file.Paths.get(n);
      return p.getFileName() != null ? p.getFileName().toString()
            : ExtractMethodHandlerDemo.TARGET_RELATIVE_PATH;
   }

   private static ExtractPlan requestPlanFromServer(IWorkspace workspace) throws Exception {
      String workspacePath = workspace.getRoot().getLocation() != null
            ? workspace.getRoot().getLocation().toOSString()
            : "";
      PlanRequest req = new PlanRequest();
      req.workspacePath = workspacePath;
      req.client = "eclipse-refactor-plugin";
      req.command = "extract-method";
      CloneContext ctx = CloneContext.get();
      req.focusProject = ctx.preferredProject;
      req.focusClassName = ctx.preferredClassName;
      req.focusClassId = ctx.preferredClassId;

      HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
      HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(SERVER_PLAN_URL))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(req)))
            .build();

      HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
         throw new IllegalStateException("Server returned HTTP " + response.statusCode());
      }

      PlanResponse raw = GSON.fromJson(response.body(), PlanResponse.class);
      if (raw == null) {
         throw new IllegalStateException("Server response was empty.");
      }
      return raw.toPlan();
   }

   private static final class PlanRequest {
      String workspacePath;
      String client;
      String command;
      String focusProject;
      String focusClassName;
      String focusClassId;
   }

   private static final class PlanResponse {
      String targetRelativePath;
      String methodName;
      String message;
      List<List<Double>> ranges;

      ExtractPlan toPlan() {
         ExtractPlan p = new ExtractPlan();
         p.targetRelativePath = targetRelativePath;
         p.unifiedMethodName = methodName;
         p.serverMessage = message;
         if (ranges != null) {
            p.ranges = new ArrayList<>();
            for (List<Double> pair : ranges) {
               if (pair == null || pair.size() < 2 || pair.get(0) == null || pair.get(1) == null) {
                  continue;
               }
               p.ranges.add(new int[] { pair.get(0).intValue(), pair.get(1).intValue() });
            }
         }
         return p;
      }
   }

   private static final class ExtractPlan {
      String targetRelativePath;
      String unifiedMethodName;
      String serverMessage;
      List<int[]> ranges;

      boolean isUsable() {
         return targetRelativePath != null && !targetRelativePath.isBlank();
      }
   }
}
