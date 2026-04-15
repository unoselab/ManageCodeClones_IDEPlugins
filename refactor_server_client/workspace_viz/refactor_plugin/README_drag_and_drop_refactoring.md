## Drag-and-drop refactoring

All **reusable** implementation for drag-and-drop lives in **`ExtractMethodWorkflow.java`** .

| Topic in this README | Where it appears for DnD |
|----------------------|-------------------------|
| **§1 Bottom-up / line-shift** | `ExtractMethodWorkflow`: `ExtractionTarget` sorts **descending** by start line before LTK extract. Intra-editor drag **reverts** the user’s move first (`EditorDropStartup`) so line ranges stay valid for LTK or for JSON fallback. |
| **§2 Clean deletion (`IMethod.delete`)** | `ExtractMethodWorkflow.deleteSecondaryMethods` — Java Model API removes redundant extracted methods after multi-site extract. |
| **§3 Batched AST rebind** | `ExtractMethodWorkflow.renameSecondaryInvocations` — **one** `ASTRewrite`; the visitor only **records** `MethodInvocation` renames; one `TextEdit` apply at the end. |
| **§4 “Effectively final” in visitors** | `ExtractMethodWorkflow.moveExtractedMethod` — `final TypeDeclaration[]` / `MethodDeclaration[]` length-1 arrays hold nodes found inside `ASTVisitor` (same pattern as the README). |
| **§5 `JLS25`** | `ExtractMethodWorkflow` uses `AST.JLS25` for `ASTParser`. |
| **Relocation** | `ExtractMethodWorkflow.moveExtractedMethod` — `createMoveTarget` + `ListRewrite.insertAfter`. |

**Entry points:** `CloneRecordLiveExtract` (builds targets from `CloneRecord` / JSON ranges, then calls `ExtractMethodWorkflow`), and `EditorDropStartup` (Dropzone + intra-editor). If live LTK is not applicable (e.g. no workspace `ICompilationUnit`), **`CloneRefactoring`** applies precomputed text in **descending offset** order and **reconciles** the Java model when possible — analogous goals, text-based rebinding/move from the server.

### Failed live-LTK examples (corpus; JSON fallback when available)

Some clone ranges match **Eclipse Extract Method** `checkInitialConditions` rules that reject the selection (e.g. **return** without all paths returning, or **try / catch / finally** boundaries). For those messages, **`CloneRecordLiveExtract.tryApplyLive`** can apply **`CloneRefactoring.applyToOpenEditor`** on the **already open** Java editor when every same-file `sources[]` row has non-blank **`replacement_code`** in `all_refactor_results.json` (see `isLtkInitialConditionSupersededByJsonFallback`).

| classid | File (`systems/…`) | Ranges (1-based lines) | What goes wrong with pure LTK |
|---------|----------------------|------------------------|--------------------------------|
| `derby_33_63_vs_derby_33_64` | `derby-java/org/apache/derbyTesting/functionTests/harness/RunList.java` | 1194–1208, 1211–1222 | `if (result) return true;` after `try`/`catch` — *“Semantics may not be preserved”* / return vs. fall-through paths. |
| `maven_265_433_vs_maven_265_434` | `maven-java/org/apache/maven/cling/executor/forked/ForkedMavenExecutor.java` | 178–188, 191–201 | `Thread` lambda bodies with **try / catch / finally** (`pump` stdout/stderr pumps) — *selection must cover whole try or only try, catch, or finally parts*. |

If JSON is missing, stale, or lines drift from the snapshot, the fallback cannot run and the user still sees the LTK error dialog.