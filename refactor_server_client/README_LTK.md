# Eclipse Plug-In Workflow via JDT and LTK

## 1. Overview

This document summarizes the development of an Eclipse plug-in workflow for programmatic **Extract Method** refactoring using **JDT** and **LTK**.

The workflow targets the following Java source file:

- `org/apache/camel/dsl/jbang/core/commands/ExportQuarkus.java`

## 2. Eclipse Command and Handler

A command was added to the Eclipse main menu to trigger programmatic Extract Method refactoring.

### Menu Action

A top-level menu named **Django API** was introduced with the following action:

- `Command Action 02 (EM)`

### Handler Responsibility

This action is handled by `ExtractMethodHandler`, which is responsible for:

- locating the target Java source file
- computing the extraction range
- applying Extract Method refactoring
- moving the editor cursor to the extracted method after refactoring

## 3. Why JDT CompilationUnit Iteration Was Chosen

A key design decision was to use **JDT compilation unit iteration** instead of raw workspace file traversal.

This choice was made because the long-term goal is to support source-level refactoring through:

- **Eclipse JDT**
- **LTK (Language Toolkit)**

Using `ICompilationUnit` provides a much better foundation for AST analysis and refactoring than working with raw `IFile` objects.

### Intended Workflow

```text
IJavaProject -> IPackageFragment -> ICompilationUnit -> ASTParser -> AST -> refactoring
````

instead of:

```text
IFile -> raw text processing
```

## 4. ExtractMethodHandler Structure

`ExtractMethodHandler` was modularized into helper methods. Its workflow is:

1. iterate through all open Java projects
2. iterate through source roots
3. iterate through package fragments
4. iterate through compilation units
5. match the target file
6. compute the source range from configured line numbers
7. apply Extract Method refactoring
8. reveal the extracted method in the editor

### Key Assumptions

The implementation assumes that the file location and extraction range are already known from a previous workflow step, such as JSON loading.

Example member fields:

```java
private final String targetRelativePath = "org/apache/camel/dsl/jbang/core/commands/ExportQuarkus.java";
private final int extractStartLine = 316;
private final int extractEndLine = 335;
```

## 5. Relative Path Matching

The matching logic was designed around the relative file path because `cu.getElementName()` returns only the file name, not the package-relative path.

### Approach

The code reconstructs the relative path from:

* the package name
* the compilation unit name

For the current workflow, the expected target path is:

* `org/apache/camel/dsl/jbang/core/commands/ExportQuarkus.java`

This allows the implementation to compare the reconstructed path against the expected file location.

## 6. Reading Source via JDT

Source is accessed directly from the JDT model using:

* `compilationUnit.getSource()`

This is cleaner than converting back to `IFile` and reading bytes manually, and it aligns better with later JDT-based refactoring steps.

## 7. Successful Target File Discovery

The workflow successfully located the target file in the runtime workspace project.

### Located File

* project: `project_target01`
* file: `ExportQuarkus.java`

This confirmed that JDT-based file discovery worked correctly on the intended real-world source file.

## 8. Motivation for Extract Method Refactoring

Once the file lookup worked, the next step was to support programmatic **Extract Method** refactoring.

### Current Assumptions

* the extraction target is inside `ExportQuarkus.java`
* the selected extraction range is lines `316` to `335`

This established that file discovery is part of a larger automated refactoring workflow.

## 9. Refactoring Workflow

`ExtractMethodHandler` stores the following workflow assumptions as fields:

* target file path: `org/apache/camel/dsl/jbang/core/commands/ExportQuarkus.java`
* extraction start line: `316`
* extraction end line: `335`
* new extracted method name: `extractedM1Block`

### Refactoring Steps

The handler performs the following steps:

1. locate the target `ICompilationUnit`
2. compute a source range from the known line numbers
3. create an `ExtractMethodRefactoring`
4. set the new method name
5. check initial conditions
6. check final conditions
7. create an LTK `Change`
8. perform the change

## 10. Restriction Issue with ExtractMethodRefactoring

During implementation, Eclipse reported access restriction problems.

### Cause

The class used for programmatic Extract Method refactoring is:

* `org.eclipse.jdt.internal.corext.refactoring.code.ExtractMethodRefactoring`

This is an internal JDT class, so PDE marks it as restricted API.

### Resolution

The setup was updated so that:

* required dependencies were added
* restricted API references were tolerated as warnings instead of blocking errors

At that point, the project no longer had errors, only warnings, which is the expected prototype state.

## 11. Plug-in Dependencies Added

The plug-in dependency set was updated to include the relevant refactoring bundles:

* `org.eclipse.jdt.core`
* `org.eclipse.jdt.ui`
* `org.eclipse.jdt.core.manipulation`
* `org.eclipse.ltk.core.refactoring`

This enabled use of the JDT manipulation and LTK refactoring stack needed by `ExtractMethodHandler`.
