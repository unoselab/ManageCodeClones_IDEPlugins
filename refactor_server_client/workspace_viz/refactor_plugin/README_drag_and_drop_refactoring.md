## Drag-and-drop refactoring

Reusable **live** extract logic lives in **`ExtractMethodWorkflow.java`**. **JSON** fallback and **multi-group** Dropzone orchestration live in **`CloneRefactoring.java`** (single-file apply, parallel JSON preparation, Dropzone class-id payload handling, and preferring an already-open workspace editor over `openEditorOnFileStore` to avoid duplicate tabs).

| Topic in this README | Where it appears for DnD |
|----------------------|-------------------------|
| **1. Bottom-up / line-shift** | `ExtractMethodWorkflow`: `ExtractionTarget` sorts **descending** by start line before LTK extract. Intra-editor drag **reverts** the user’s move first (`EditorDropStartup`) so line ranges stay valid for LTK or for JSON fallback. |
| **2. Clean deletion (`IMethod.delete`)** | `ExtractMethodWorkflow.deleteSecondaryMethods` — Java Model API removes redundant extracted methods after multi-site extract. |
| **3. Batched AST rebind** | `ExtractMethodWorkflow.renameSecondaryInvocations` — **one** `ASTRewrite`; the visitor only **records** `MethodInvocation` renames; one `TextEdit` apply at the end. |
| **4. “Effectively final” in visitors** | `ExtractMethodWorkflow.moveExtractedMethod` — `final TypeDeclaration[]` / `MethodDeclaration[]` length-1 arrays hold nodes found inside `ASTVisitor` (same pattern as the README). |
| **5. `JLS25`** | `ExtractMethodWorkflow` uses `AST.JLS25` for `ASTParser`. |
| **Relocation** | `ExtractMethodWorkflow.moveExtractedMethod` — `createMoveTarget` + `ListRewrite.insertAfter`. |

**Entry points**

- **`CloneRecordLiveExtract`** — Builds `ExtractionTarget` list from `CloneRecord` / JSON ranges, then calls `ExtractMethodWorkflow`. Used when the clone group is **same file** as the active Java editor.
- **`EditorDropStartup`** — Dropzone transfer onto an editor, intra-editor drag detection, and routing of Dropzone payloads.
- **`CloneRefactoring`** — If live LTK is not applicable (e.g. no workspace `ICompilationUnit`, or multi-file group), applies precomputed `replacement_code` in **descending offset** order and reconciles the Java model when possible.

### Dropzone: one group vs several groups

| Flow | What the user does | What runs |
|------|----------------------|-----------|
| **Single snippet** | Add a snippet (no `[classid]` label), drop on editor | `EditorDropStartup` resolves one `CloneRecord` from the target file (`findRecordForFile`), then `CloneRefactoring.applyCloneRefactorWithLiveFirst` (live, then demo, then `CloneRefactoring.apply`). |
| **Several groups** | Add rows with **`[classid]`** (e.g. via **Add from Editor Selection** while a clone is focused in the graph). Multi-select those rows, then either **drag** onto a Java editor or use the Dropzone **toolbar / view menu**: **Apply selected clone groups to active editor**. | Payload prefix `DROPZONE_CLASSIDS:\n` + one `classid` per line. **`CloneRefactoring.applyFromDropzoneClassidsPayload`**: for each group whose JSON sources match the **drop/active editor file**, live extract runs **sequentially** on the UI thread; **other** groups are handled with **`CloneRefactoring.snapshotTextsForRecords`** (prefer open editor text, else disk) and **`CloneRefactoring.applyRecordsJsonInParallel`** (one worker task per group to compute edits on in-memory documents; merge per file; validate non-overlapping replacements; apply on the UI thread). |
| **Opening the right editor** | JSON apply must hit the buffer the user already has open | **`CloneRefactoring.openTextEditorForAbsPath`** tries, in order: an open `ITextEditor` whose path matches (including **`CloneContext.canonicalJavaSourceKey`** for `systems/…` vs `src/main/java/…`), then workspace **`IFile`** / **`JavaUI.openInEditor`**, then **`IDE.openEditorOnFileStore`** as last resort. |

### Failed live-LTK examples (corpus; JSON fallback when available)

Some clone ranges match **Eclipse Extract Method** `checkInitialConditions` rules that reject the selection (e.g. **return** without all paths returning, or **try / catch / finally** boundaries). When **`CloneRecordLiveExtract.tryApplyLive`** returns **`NOT_APPLICABLE`**, the stack falls back to precomputed JSON via **`CloneRefactoring.apply`** (single group) or the parallel path above (multi-group), provided `sources[]` include usable **`replacement_code`**.

| classid | File (`systems/…`) | Ranges (1-based lines) | What goes wrong with pure LTK |
|---------|----------------------|------------------------|--------------------------------|
| `derby_33_63_vs_derby_33_64` | `derby-java/org/apache/derbyTesting/functionTests/harness/RunList.java` | 1194–1208, 1211–1222 | `if (result) return true;` after `try`/`catch` — *“Semantics may not be preserved”* / return vs. fall-through paths. |
| `maven_265_433_vs_maven_265_434` | `maven-java/org/apache/maven/cling/executor/forked/ForkedMavenExecutor.java` | 178–188, 191–201 | `Thread` lambda bodies with **try / catch / finally** (`pump` stdout/stderr pumps) — *selection must cover whole try or only try, catch, or finally parts*. |

If JSON is missing, stale, or lines drift from the snapshot, the fallback cannot run and the user still sees the LTK error dialog.

### Transfer format (Dropzone custom type)

**`DropzoneTransfer`** carries a UTF-8 string: either a plain snippet, or **`DROPZONE_CLASSIDS:\n`** followed by one clone **`classid`** per line when every selected list row carries an id (see **`DropzoneView`** and **`CloneRefactoring.DROPZONE_CLASSIDS_PAYLOAD`**).
