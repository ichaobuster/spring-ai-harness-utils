# spring-ai-skills — AGENTS.md

> [!IMPORTANT]
> **This module is under active development.** This document is a **Living Document** and must stay in sync with code, `SKILL.md` prompts, root `AGENTS.md`, and README changes.

---

## Project Overview

`spring-ai-skills` packages **executable agent skills** as Spring AI tools. Unlike MCP server workspace skills (which are primarily instructional `SKILL.md` files discovered at runtime), this module ships:

1. **Runnable `@Tool` implementations** agents can call directly through Spring AI tool calling.
2. **Bundled classpath `SKILL.md`** documents that teach the model *when* and *how* to use those tools.
3. **Local filesystem I/O** for domain skills (e.g. `XlsxTools`), plus optional **`OssLocalFileTools`** that uses the Aliyun OSS SDK to download objects into a local `downloadPath` (default `/tmp`) and upload results back.

Current skill coverage:

| Skill | Package | Tools |
| :--- | :--- | :--- |
| `xlsx` | `io.github.springai.harness.skills.xlsx` | `readXlsxPreview`, `readXlsxSheet`, `createXlsx`, `editXlsxCells`, `evaluateXlsxFormulas`, `convertCsvToXlsx` (local FS paths) |
| `docx` | `io.github.springai.harness.skills.docx` | `readDocxPreview`, `readDocxContent`, `createDocx`, `replaceDocxText`, `mergeDocxRuns`, `addDocxComment`, `acceptDocxTrackedChanges` |
| *(bridge)* | `io.github.springai.harness.skills.storage` | `downloadOssFileToLocal`, `uploadLocalFileToOss` |

---

## Architecture

```
ChatClient / Agent runtime
        │
        ├─ SkillsTool (from spring-ai-harness-utils)
        │     └─ loads SKILL.md via SkillUtil.loadClassPath(...)
        │
        ├─ OssLocalFileTools  ──► Aliyun OSS SDK (bucket + optional prefix)
        │        downloadOssFileToLocal / uploadLocalFileToOss
        │
        ├─ XlsxTools (@Tool methods)  ──► local filesystem paths
        └─ DocxTools (@Tool methods) ──► local filesystem (Path/Files)
```

Design constraints:
- `XlsxTools` is **stateless** and reads/writes **local filesystem paths** only.
- `OssLocalFileTools` requires `OSS` + `bucketName` (optional `prefix`, `downloadPath=/tmp`) and keeps object paths **prefix-relative** (no leading `/`).
- Large-file safety is mandatory: size caps, row caps, and SXSSF streaming where applicable.
- Skill prompts and tool annotations must agree on limits and JSON field names.

---

## Module Structure

```
spring-ai-skills/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/io/github/springai/harness/skills/
    │   │   ├── storage/
    │   │   │   └── OssLocalFileTools.java
    │   │   ├── xlsx/
    │   │   │   ├── XlsxTools.java
    │   │   │   ├── CellSpec.java
    │   │   │   ├── CellStyleSpec.java
    │   │   │   ├── SheetSpec.java
    │   │   │   └── FormulaEvaluationResult.java
    │   │   └── docx/
    │   │       ├── DocxTools.java
    │   │       ├── DocxBlockSpec.java / DocxParagraphSpec.java / DocxRunSpec.java
    │   │       ├── DocxTableSpec.java / DocxTableRowSpec.java / DocxTableCellSpec.java
    │   │       └── DocxPageSetupSpec.java
    │   └── resources/skills/
    │       ├── xlsx/SKILL.md
    │       └── docx/SKILL.md
    └── test/java/io/github/springai/harness/skills/
        ├── storage/OssLocalFileToolsTest.java
        ├── xlsx/XlsxToolsTest.java
        └── docx/DocxToolsTest.java
```

---

## XLSX Tool Details

### Construction
```java
XlsxTools xlsxTools = new XlsxTools(); // local filesystem paths

OssLocalFileTools bridge = OssLocalFileTools.builder()
    .ossClient(ossClient)
    .bucketName("your-bucket")
    .prefix("mcp/workspaces/sys-agent-user/") // optional
    .downloadPath(Path.of("/tmp"))            // optional, default /tmp
    .build();
```

Typical remote workflow: `downloadOssFileToLocal` → `XlsxTools.*` → `uploadLocalFileToOss`.

### Tool Matrix

| Tool | Input highlights | Output | Safety limits |
| :--- | :--- | :--- | :--- |
| `readXlsxPreview` | `filePath`, optional `maxRowsPerSheet` | Markdown preview | default 10 / max 50 rows per sheet; file ≤ 50MB (local FS) |
| `readXlsxSheet` | `filePath`, `sheetName`, optional row range, `showFormulas` | Text cell dump | start/end 1-indexed; max 2000 rows/call; file ≤ 50MB (local FS) |
| `createXlsx` | `filePath`, `List<SheetSpec>` | Success/error string | empty sheets rejected; SXSSF when cell count > 10000 |
| `editXlsxCells` | existing `filePath`, `List<SheetSpec>` | Success/error string | creates missing sheets/cells; preserves others |
| `evaluateXlsxFormulas` | `filePath`, optional `writeBack` | JSON `FormulaEvaluationResult` (camelCase) | updates cached results without replacing formulas; caps locations at 100 per error type |
| `convertCsvToXlsx` | `csvFilePath`, `xlsxFilePath`, optional delimiter/sheet | Success/error string | delimiter supports `,` and `\t`; SXSSF window 100 |

### Spec Models
- **`SheetSpec`**: sheet name, cells, column widths, freeze panes.
- **`CellSpec`**: `cellRef` (e.g. `B12`), optional `value` / `formula` / `style` / `comment`.
- **`CellStyleSpec`**: font, colors (`R,G,B` or `#RRGGBB`), data format, alignment.
- **`FormulaEvaluationResult`**: status, totals, per-error location groups.

Formulas may be supplied with or without a leading `=`. Avoid Excel 365 dynamic-array functions (`XLOOKUP`, `FILTER`, `SORT`, etc.) because offline POI evaluation does not support them reliably.

---

## DOCX Tool Details

### Construction
```java
DocxTools tools = new DocxTools();
// Paths are local filesystem absolute/relative paths (same as XlsxTools).
// For remote/OSS files, download first via OssLocalFileTools#downloadOssFileToLocal.
```

### Tool Matrix

| Tool | Input highlights | Output | Safety limits |
| :--- | :--- | :--- | :--- |
| `readDocxPreview` | `filePath`, optional `maxBlocks` | Markdown overview | default 20 / max 100 body blocks; file ≤ 50MB (local FS) |
| `readDocxContent` | `filePath`, optional `startBlock`, `maxBlocks` | Paginated body dump | 1-indexed start; max 500 blocks/call |
| `createDocx` | `filePath`, `List<DocxBlockSpec>`, optional page setup | Success/error string | empty blocks rejected; default A4 page setup |
| `replaceDocxText` | `filePath`, `find`, `replace`, optional `replaceAll` | Success/error string | body + table cells; prefer `mergeDocxRuns` first |
| `mergeDocxRuns` | `filePath`, optional `outputPath` | Merge count message | adjacent same-format runs only |
| `addDocxComment` | `filePath`, `text`, optional author/initials/`anchorText`/`outputPath` | Comment id + anchor status | POI high-level comments; extended parts not guaranteed |
| `acceptDocxTrackedChanges` | `filePath`, optional `outputPath` | Revision count message | accepts `w:ins`, drops `w:del` via CT APIs |

### Spec Models
- **`DocxBlockSpec`**: `type` = `paragraph` \| `table` \| `pageBreak` + nested payload.
- **`DocxParagraphSpec`**: style, alignment, runs.
- **`DocxRunSpec`**: text/formatting, optional `breakType`, optional `imagePath`.
- **`DocxTableSpec` / row / cell**: DXA column widths, cell shading, nested paragraphs.
- **`DocxPageSetupSpec`**: page size/margins/landscape (A4 defaults).

Out of scope: LibreOffice PDF render, pandoc, full OOXML XSD validation, legacy `.doc`.

---

## Classpath Skill Loading

Bundled prompt paths:
```
classpath:skills/xlsx/SKILL.md
classpath:skills/docx/SKILL.md
```

Recommended discovery pattern:
```java
List<SkillsTool.Skill> skills = SkillUtil.loadClassPath("classpath*:skills/**/SKILL.md");
```

`SKILL.md` front matter must include at least:
- `name` — skill command key (e.g. `xlsx`)
- `description` — trigger guidance for the model
- `tool-calls` — space-separated tool names implemented by this module

Whenever tool behavior, limits, or JSON keys change, update **both**:
1. `@Tool` / `@ToolParam` English descriptions in Java
2. `src/main/resources/skills/<skill>/SKILL.md`

---

## Dependencies

| Dependency | Purpose |
| :--- | :--- |
| `spring-ai-harness-utils` | `StorageProvider`, `SkillUtil`, shared harness types |
| `spring-ai-client-chat` | `@Tool` / `@ToolParam` annotations |
| `poi-ooxml` | Excel + Word (XWPF) read/write |
| `opencsv` | CSV/TSV parsing for conversion |
| `jackson-databind` | JSON serialization of evaluation results |

Managed versions come from the parent POM / BOM where possible. Keep local version pins minimal.

---

## Coding Standards

1. **No hardcoded FQCNs** in method bodies or signatures; use imports (project-wide rule).
2. **English** for tool annotations and `SKILL.md`; **Chinese** allowed for internal implementation comments.
3. Prefer Java 17 `record` for DTOs (`CellSpec`, `SheetSpec`, results).
4. Log via SLF4J (`@Slf4j`); never `System.out.println`.
5. Catch tool-level I/O/parse failures and return actionable error strings/JSON when possible.
6. Do not bypass `StorageProvider` with direct `java.nio.file` writes in tool code.
7. New skills require matching unit tests with **≥ 80%** line/branch coverage on core logic.

---

## Testing

```bash
./mvnw test -pl spring-ai-skills
```

Testing rules:
- Use JUnit 5 + AssertJ + Mockito.
- Drive file I/O through `@TempDir` + `LocalFileStorage`.
- Mock `StorageProvider` only for size/existence edge cases.
- Cover XLSX preview/read/create/edit/evaluate/convert paths and validation failures.
- Cover DOCX preview/content/create/replace/merge/comment/accept-tracked-changes paths and validation failures.
- When fixing formula write-back, style, or document XML behavior, assert **post-conditions on file contents**, not only success strings.

---

## Adding a New Skill (Checklist)

1. Create package `io.github.springai.harness.skills.<skill>`.
2. Implement tool class(es) with `@Tool` methods and storage-relative paths.
3. Add `src/main/resources/skills/<skill>/SKILL.md` with front matter + usage guidance.
4. Add unit tests under `src/test/java/...`.
5. Update this file, root `AGENTS.md`, `README.md`, and `README_zh.md`.
6. Ensure artifact remains listed in `spring-ai-harness-utils-bom`.

---

## Build Commands

```bash
./mvnw compile -pl spring-ai-skills
./mvnw test -pl spring-ai-skills
```
