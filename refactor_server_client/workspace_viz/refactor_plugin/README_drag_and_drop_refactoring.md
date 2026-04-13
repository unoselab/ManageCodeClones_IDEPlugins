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