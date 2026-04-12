# Extract Method Refactoring: Multiple Locations & AST Manipulation

This README contains an Eclipse plugin handler (`ExtractMethodHandler.java`) that demonstrates advanced Java Development Tools (JDT) and Language Toolkit (LTK) usage. 

This project illustrates how to manually orchestrate complex refactoring workflows. This is particularly useful for research use cases, fuzzy-matching algorithms, or custom refactoring tools where built-in matching falls short.

## The Core Workflow

This handler executes a complete 4-step manual refactoring pipeline:

1. **Bottom-Up Extraction:** Extracts multiple identical blocks of code into separate methods.
2. **Clean Deletion:** Removes the secondary (redundant) method declarations.
3. **AST Rebinding:** Rewrites the Abstract Syntax Tree (AST) so all function calls point to the *first* (primary) extracted method.
4. **Relocation:** Physically moves the primary extracted method to a highly specific location in the source file.

---

## 🧠 Key Concepts & Solutions for Students

When manipulating Java source code programmatically, you will encounter several quirks. Here is how this plugin solves them.

### 1. The Line-Shift Problem (Why we process Bottom-Up)
When you extract lines `10-20` into a method, those 11 lines are replaced by a single line (the method invocation). If you try to extract lines `40-50` immediately after, your hardcoded offsets are now completely wrong because the text has shifted upward.

**The Solution:** We implement the `Comparable` interface on our `ExtractionTarget` class to sort the targets in **descending order**. By processing the file bottom-up, modifying the bottom of the file leaves the line numbers at the top of the file completely untouched.

### 2. Java Model API vs. AST (Abstract Syntax Tree)
Eclipse provides two different ways to look at Java code, and this plugin uses both strategically:
* **The Java Model API (`IMethod`, `IType`, `ICompilationUnit`):** This is a lightweight, structural view of the code. We use this for **Step 2 (Deletion)**. Calling `IMethod.delete()` is much safer and handles buffer text manipulation for us under the hood.
* **The AST (`MethodDeclaration`, `MethodInvocation`, `ASTRewrite`):** This is a deep, complex semantic tree of the code. We use this for **Step 3 and Step 4** because we need to inspect the exact syntax inside method bodies (the function calls) and physically reorder nodes.

### 3. Batching AST Rewrites
A common beginner mistake is applying an AST rewrite immediately after finding a node. This breaks the AST for the rest of your traversal.

**The Solution:** We create **one** `ASTRewrite` object for the entire file. As the `ASTVisitor` traverses the code, we merely *record* our intended changes. Once the traversal is finished, we compile all those changes into a `TextEdit` and apply them to the document all at once.

### 4. Bypassing "Effectively Final" inside ASTVisitors
When using an `ASTVisitor` (which is an anonymous inner class), you cannot reassign local variables declared outside of it. 

**The Solution:** You will notice code like `final MethodDeclaration[] extractedNode = new MethodDeclaration;`. By using an explicitly sized array, the *reference* to the array remains final, but we can freely mutate its *contents* (index `0`) from inside the visitor. This is a standard Java pattern for AST traversal.

### 5. Using `JLS25` vs. `JLS_LATEST`
When setting up the `ASTParser`, it is tempting to use `AST.JLS_LATEST`. However, if your plugin is installed on an older version of Eclipse, `JLS_LATEST` might refer to an unsupported version, causing the parser to crash. Hardcoding the exact JLS level your plugin supports (e.g., `AST.JLS25`) ensures strict compatibility and stability.

---

## Code Architecture

The handler is broken down into modular helper methods to keep the `execute()` block clean:

* `applyExtractMethodRefactoring()`: Uses Eclipse's internal `ExtractMethodRefactoring` class.
* `deleteSecondaryMethods()`: Iterates through targets and uses `IMethod.delete()`.
* `renameSecondaryInvocations()`: Parses the AST, finds invocations of deleted methods, and rebinds the `NAME_PROPERTY` to the primary method.
* `moveExtractedMethod()`: Uses `ListRewrite` to yank the primary method node and `insertAfter` to place it safely behind the target destination method.

---

## Applying this guide to drag-and-drop (`workspace_viz_v2`)

The handler above stays the **reference** for the course and should remain **unchanged** as the professor’s example. All **reusable** implementation for drag-and-drop lives in **`ExtractMethodWorkflow.java`** (same concepts, without replacing the README’s teaching narrative).

| Topic in this README | Where it appears for DnD |
|----------------------|-------------------------|
| **§1 Bottom-up / line-shift** | `ExtractMethodWorkflow`: `ExtractionTarget` sorts **descending** by start line before LTK extract. Intra-editor drag **reverts** the user’s move first (`EditorDropStartup`) so line ranges stay valid for LTK or for JSON fallback. |
| **§2 Clean deletion (`IMethod.delete`)** | `ExtractMethodWorkflow.deleteSecondaryMethods` — Java Model API removes redundant extracted methods after multi-site extract. |
| **§3 Batched AST rebind** | `ExtractMethodWorkflow.renameSecondaryInvocations` — **one** `ASTRewrite`; the visitor only **records** `MethodInvocation` renames; one `TextEdit` apply at the end. |
| **§4 “Effectively final” in visitors** | `ExtractMethodWorkflow.moveExtractedMethod` — `final TypeDeclaration[]` / `MethodDeclaration[]` length-1 arrays hold nodes found inside `ASTVisitor` (same pattern as the README). |
| **§5 `JLS25`** | `ExtractMethodWorkflow` uses `AST.JLS25` for `ASTParser`. |
| **Relocation** | `ExtractMethodWorkflow.moveExtractedMethod` — `createMoveTarget` + `ListRewrite.insertAfter`. |

**Entry points:** `CloneRecordLiveExtract` (builds targets from `CloneRecord` / JSON ranges, then calls `ExtractMethodWorkflow`), and `EditorDropStartup` (Dropzone + intra-editor). If live LTK is not applicable (e.g. no workspace `ICompilationUnit`), **`CloneRefactoring`** applies precomputed text in **descending offset** order and **reconciles** the Java model when possible — analogous goals, text-based rebinding/move from the server.