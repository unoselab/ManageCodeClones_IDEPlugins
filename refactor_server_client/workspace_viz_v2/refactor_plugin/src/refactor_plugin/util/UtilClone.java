package refactor_plugin.util;

import java.util.ArrayList;
import java.util.List;

import refactor_plugin.handlers.extract.ExtractionTarget;
import refactor_plugin.model.CloneRecord;
import refactor_plugin.model.CloneRecord.CloneSource;

public class UtilClone {
   private static final String PRJ_ROOT_PATH = "systems/camel-java/";

   public static String toProjectRelativeJavaPath(CloneSource src) {
      if (src == null || src.file == null || src.file.isBlank()) {
         return null;
      }

      String normalized = src.file.replace('\\', '/');

      if (normalized.startsWith(PRJ_ROOT_PATH)) {
         normalized = normalized.substring(PRJ_ROOT_PATH.length());
      }

      if (normalized.startsWith("/")) {
         normalized = normalized.substring(1);
      }

      int srcIdx = normalized.indexOf("/src/");
      if (srcIdx >= 0) {
         return normalized.substring(srcIdx + "/src/".length());
      }

      return normalized;
   }

   public static List<ExtractionTarget> buildExtractionTargets(CloneRecord record) {
      List<ExtractionTarget> targets = new ArrayList<>();

      if (record == null || record.sources == null || record.sources.isEmpty()) {
         return targets;
      }

      class TargetSeed {
         final int startLine;
         final int endLine;

         TargetSeed(int startLine, int endLine) {
            this.startLine = startLine;
            this.endLine = endLine;
         }
      }

      List<TargetSeed> seeds = new ArrayList<>();

      for (CloneSource src : record.sources) {
         if (src == null || src.range == null || src.range.isBlank()) {
            continue;
         }

         int[] lineRange = parseLineRange(src.range);
         if (lineRange == null) {
            continue;
         }

         seeds.add(new TargetSeed(lineRange[0], lineRange[1]));
      }

      seeds.sort((a, b) -> {
         int cmp = Integer.compare(a.startLine, b.startLine);
         if (cmp != 0) {
            return cmp;
         }
         return Integer.compare(a.endLine, b.endLine);
      });

      int counter = 1;
      for (int i = 0; i < seeds.size(); i++) {
         TargetSeed seed = seeds.get(i);
         boolean primary = (i == 0);
         String methodName = "extractedM1Block" + counter++;

         targets.add(new ExtractionTarget(seed.startLine, seed.endLine, methodName, primary));
      }

      return targets;
   }

   static int[] parseLineRange(String range) {
      String[] parts = range.split("-");
      if (parts.length < 2) {
         return null;
      }

      try {
         int start = Integer.parseInt(parts[0].trim());
         int end = Integer.parseInt(parts[1].trim());
         if (start <= 0 || end < start) {
            return null;
         }
         return new int[] { start, end };
      } catch (NumberFormatException ex) {
         return null;
      }
   }
}
