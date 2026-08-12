# spring-ai-skills — AGENTS.md

> [!IMPORTANT]
> **This module is under active development.** This document is a **Living Document** and must stay in sync with code, `SKILL.md` prompts, root `AGENTS.md`, and README changes.

---

## Project Overview

`spring-ai-skills` packages **executable agent skills** as Spring AI tools. Unlike MCP server workspace skills (which are primarily instructional `SKILL.md` files discovered at runtime), this module ships:

1. **Runnable `@Tool` implementations** agents can call directly through Spring AI tool calling.
2. **Bundled classpath `SKILL.md`** documents that teach the model *when* and *how* to use those tools.
3. **Storage-bound I/O** via `StorageProvider` from `spring-ai-harness-utils`, so the same tools work with local disks or remote workspaces.

Current skill coverage:

| Skill | Package | Tools |
| :--- | :--- | :--- |
| `xlsx` | `io.github.springai.harness.skills.xlsx` | `readXlsxPreview`, `readXlsxSheet`, `createXlsx`, `editXlsxCells`, `evaluateXlsxFormulas`, `convertCsvToXlsx` |

---

## Architecture

```
ChatClient / Agent runtime
        │
        ├─ SkillsTool (from spring-ai-harness-utils)
        │     └─ loads SKILL.md via SkillUtil.loadClassPath(...)
        │
        └─ XlsxTools (@Tool methods)
              └─ StorageProvider
                    ├── LocalFileStorage
                    └── AliyunOssStorage / other impls
```

Design constraints:
- Tools are **stateless** aside from the injected `StorageProvider`.
- Paths accepted by tools are **storage-relative**, never trusted absolute host paths.
- Large-file safety is mandatory: size caps, row caps, and SXSSF streaming where applicable.
- Skill prompts and tool annotations must agree on limits and JSON field names.

---

## Module Structure

```
spring-ai-skills/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/io/github/springai/harness/skills/xlsx/
    │   │   ├── XlsxTools.java
    │   │   ├── CellSpec.java
    │   │   ├── CellStyleSpec.java
    │   │   ├── SheetSpec.java
    │   │   └── FormulaEvaluationResult.java
    │   └── resources/
    │       └── skills/xlsx/SKILL.md
    └── test/
        └── java/io/github/springai/harness/skills/xlsx/
            └── XlsxToolsTest.java
```

---

## XLSX Tool Details

### Construction
```java
XlsxTools tools = XlsxTools.builder()
    .storageProvider(storageProvider)
    .build();
// or new XlsxTools(storageProvider)
```

If `storageProvider` is null, the implementation falls back to `LocalFileStorage` rooted at the process working directory (`.`). Prefer explicit injection in production.

### Tool Matrix

| Tool | Input highlights | Output | Safety limits |
| :--- | :--- | :--- | :--- |
| `readXlsxPreview` | `filePath`, optional `maxRowsPerSheet` | Markdown preview | default 10 / max 50 rows per sheet; file ≤ 50MB |
| `readXlsxSheet` | `filePath`, `sheetName`, optional row range, `showFormulas` | Text cell dump | start/end 1-indexed; max 2000 rows/call; file ≤ 50MB |
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

## Classpath Skill Loading

Bundled prompt path:
```
classpath:skills/xlsx/SKILL.md
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
| `poi-ooxml` | Excel read/write/formula evaluation |
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
- Cover preview/read/create/edit/evaluate/convert paths and validation failures.
- When fixing formula write-back or style behavior, assert **post-conditions on workbook contents**, not only success strings.

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
