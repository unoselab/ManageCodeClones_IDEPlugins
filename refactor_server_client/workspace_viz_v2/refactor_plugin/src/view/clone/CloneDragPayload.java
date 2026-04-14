package view.clone;

import java.io.Serializable;
import java.util.List;

import refactor_plugin.model.CloneRecord;
import refactor_plugin.model.CloneRecord.CloneSource;
import refactor_plugin.handlers.extract.ExtractionTarget;

public class CloneDragPayload implements Serializable {
   private static final long serialVersionUID = 1L;

   private final CloneRecord record;
   private final CloneSource selectedSource;
   private final String relativePath;
   private final List<ExtractionTarget> extractionTargets;
   private final String extractedMethodLocation;

   public CloneDragPayload(CloneRecord record, CloneSource selectedSource, String relativePath, List<ExtractionTarget> extractionTargets, String extractedMethodLocation) {
      this.record = record;
      this.selectedSource = selectedSource;
      this.relativePath = relativePath;
      this.extractionTargets = extractionTargets;
      this.extractedMethodLocation = extractedMethodLocation;
   }

   public CloneRecord getRecord() {
      return record;
   }

   public CloneSource getSelectedSource() {
      return selectedSource;
   }

   public String getRelativePath() {
      return relativePath;
   }

   public List<ExtractionTarget> getExtractionTargets() {
      return extractionTargets;
   }

   public String getExtractedMethodLocation() {
      return extractedMethodLocation;
   }
}