---
name: xlsx
description: "Use this skill any time a spreadsheet file is the primary input or output. This means any task where the user wants to: open, read, edit, or fix an existing .xlsx, .xlsm, .xltx, .csv, or .tsv file (e.g., adding columns, computing formulas, formatting, charting, cleaning messy data); create a new spreadsheet from scratch or from other data sources; or convert between tabular file formats. Trigger especially when the user references a spreadsheet file by name or path — even casually (like \"the xlsx in my downloads\") — and wants something done to it or produced from it. Also trigger for cleaning or restructuring messy tabular data files (malformed rows, misplaced headers, junk data) into proper spreadsheets. The deliverable must be a spreadsheet file. Do NOT trigger when the primary deliverable is a Word document, HTML report, standalone Python script, database pipeline, or Google Sheets API integration, even if tabular data is involved."
tool-calls: readXlsxPreview readXlsxSheet createXlsx editXlsxCells evaluateXlsxFormulas convertCsvToXlsx
license: Proprietary. LICENSE.txt has complete terms
---

# XLSX creation, editing, and analysis

| Task | Tool / Approach |
|---|---|
| **Quick look** at a sheet (summary & top rows) | `readXlsxPreview` — view sheet dimensions and markdown table preview |
| **Read** sheet contents / formulas | `readXlsxSheet` — pagination support for value or formula inspection |
| **Create** a new workbook with formulas/formatting | `createXlsx` — construct workbook from JSON specification |
| **Edit** existing workbook cells/formulas | `editXlsxCells` — update specific cells without destroying existing content |
| **Recalculate & check errors** | `evaluateXlsxFormulas` — evaluate formulas via POI and report error summary |
| **Bulk CSV/TSV to XLSX** | `convertCsvToXlsx` — streaming CSV to XLSX conversion |

## Requirements for every output

- **Professional font** (Arial, Times New Roman) throughout, unless the user says otherwise.
- **Zero formula errors.** Never ship while `evaluateXlsxFormulas` reports `errors_found`. If you think an error predates you, inspect the cell's original cached value first.
- **Use formulas, never hardcoded results.** Write `formula: "=SUM(B2:B9)"` instead of pre-computing the total. The sheet must recalculate when its inputs change.
- **Follow the user's spec literally.** Exact tab names, exact column headers, and the formula they spelled out. A redesign that computes something else fails, however elegant.
- **Document every assumption and hardcoded number** where the reader will see it — a cell comment, or an adjacent cell at a table's end. Cite a real source when one exists (`Source: Company 10-K, FY2024, Page 45, Revenue Note, [SEC EDGAR URL]`); when the number came from the user, say so plainly.
- **A workbook *you create* for someone to fill in** needs a short legend naming which cells to edit, and one example row of realistic values showing the expected format. Never add such a row to a file you were asked to edit.
- **Editing an existing file: match its conventions exactly.** They override every guideline here. Find its designated input cells first — a distinct font color, fill, or shading marks them — write only there, and leave every existing formula untouched.

## Recalculate (mandatory whenever the file contains formulas)

After writing formulas to a workbook, cached values may not be populated until formulas are evaluated.

Call `evaluateXlsxFormulas(filePath, writeBack=true)` to recalculate all formulas (updating cached values in place while keeping formula expressions) and check for formula errors (`#VALUE!`, `#DIV/0!`, `#REF!`, `#NAME?`, `#NULL!`, `#NUM!`, `#N/A`).

**JSON Output Format (camelCase):**
- `status`: `"success"` or `"errors_found"`
- `totalFormulas`: count of formula cells
- `totalErrors`: count of formula errors
- `errorSummary`: details per error type with cell locations (`count`, `locations`, optional `locationsTruncated`)
- `error`: present only when evaluation failed fatally (no `status` in that case)

When `writeBack=true` (default), cached formula results are persisted **without replacing formulas** with literals. The workbook must still recalculate when inputs change.

## Choosing formulas that survive verification

- **Prefer Excel-2007-era functions** — `SUMIFS`, `INDEX`, `MATCH`, `IFERROR`, `SUMPRODUCT` — which need no prefix.
- **Never use `XLOOKUP`, `XMATCH`, `SORT`, `FILTER`, `UNIQUE`, or `SEQUENCE`.** These Excel 365 array spilling functions are not supported by offline evaluators. Use `INDEX`/`MATCH` for lookups, and sort, filter, and de-duplicate before writing the cells.

## Financial models

Unless the user says otherwise, or the existing file already does something else.

**Color:** blue text (`fontColorRgb: "0,0,255"`) for hardcoded inputs and scenario levers · black for formulas ·
green (`fontColorRgb: "0,128,0"`) for links to another sheet · red (`fontColorRgb: "255,0,0"`) for links to another file ·
yellow fill (`fillColorRgb: "255,255,0"`) for key assumptions and cells the user should fill in.

**Numbers:** currency `$#,##0`, with the unit named in the header (`Revenue ($mm)`) · zeros
render as `-`, including in percentages (`$#,##0;($#,##0);-`) · negatives in parentheses ·
percentages `0.0%`, **stored as fractions** (`0.15` renders `15.0%`; storing `15` renders
`1500.0%`) · valuation multiples `0.0x` · years as text (`"2024"`, never `2,024`).

**Structure:** every assumption in its own labeled cell, referenced by the formulas that use it
(`=B5*(1+$B$6)`, never `=B5*1.05`) · formulas consistent across every projection period, since a
lone edited cell mid-row is the commonest silent error · guard denominators that can be zero.
