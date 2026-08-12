---
name: docx
description: "Use this skill whenever the user wants to create, read, edit, or manipulate Word documents (.docx files). Triggers include: any mention of 'Word doc', 'word document', '.docx', or requests to produce professional documents with formatting like tables of contents, headings, page numbers, or letterheads. Also use when extracting or reorganizing content from .docx files, inserting or replacing images in documents, performing find-and-replace in Word files, working with tracked changes or comments, or converting content into a polished Word document. If the user asks for a 'report', 'memo', 'letter', 'template', or similar deliverable as a Word or .docx file, use this skill. Do NOT use for PDFs, spreadsheets, Google Docs, or general coding tasks unrelated to document generation."
tool-calls: readDocxPreview readDocxContent createDocx replaceDocxText mergeDocxRuns addDocxComment acceptDocxTrackedChanges
license: Apache-2.0
---

# DOCX creation, editing, and analysis

Pure Java tools backed by Apache POI. All paths are **local filesystem paths** (absolute or relative). For remote/OSS workspace files, download first via `OssLocalFileTools#downloadOssFileToLocal`. Max file size: **50MB**.

| Task | Tool |
|---|---|
| **Quick look** (counts, headings, first blocks) | `readDocxPreview` |
| **Read** body with pagination | `readDocxContent` |
| **Create** a new document | `createDocx` |
| **Find / replace** plain text | `replaceDocxText` (run `mergeDocxRuns` first if matches fail) |
| **Merge split runs** | `mergeDocxRuns` |
| **Add comments** | `addDocxComment` |
| **Accept tracked changes** | `acceptDocxTrackedChanges` |

## Workflow

### Create
1. Build an ordered `blocks` list of `DocxBlockSpec`.
2. Call `createDocx(filePath, blocks, pageSetup?)`.
3. Verify with `readDocxPreview` / `readDocxContent`.

### Edit existing
1. `readDocxPreview` or `readDocxContent` to understand structure.
2. `mergeDocxRuns(filePath)` so search text is contiguous.
3. `replaceDocxText(filePath, find, replace, replaceAll)`.
4. Optional: `addDocxComment(...)` with `anchorText` for visible anchors.
5. If the file has redlines you want cleaned: `acceptDocxTrackedChanges(input, output)`.

## createDocx block schema

`type` is required:

- `paragraph` — set `paragraph: DocxParagraphSpec`
- `table` — set `table: DocxTableSpec`
- `pageBreak` — no payload

### DocxParagraphSpec
- `style`: `Normal`, `Heading1`, `Heading2`, `Title`, …
- `alignment`: `LEFT` \| `CENTER` \| `RIGHT` \| `BOTH`
- `runs`: list of `DocxRunSpec`

### DocxRunSpec
- `text`, `bold`, `italic`, `underline`, `fontName`, `fontSize` (pt)
- `color`: `#RRGGBB` or `r,g,b`
- `breakType`: `TEXT` (line) or `PAGE`
- `imagePath` (+ optional `imageWidthPx` / `imageHeightPx`) to embed a local image file

### DocxTableSpec
- `columnWidthsDxa`: integer list (1440 DXA = 1 inch). Prefer DXA widths on columns **and** cells.
- `rows` → `cells` → `paragraphs`, optional `widthDxa`, `shading`

### DocxPageSetupSpec (optional)
Defaults to **A4** portrait, 1-inch margins.
- `widthDxa` / `heightDxa` (A4: 11906 × 16838)
- US Letter: `12240 × 15840`
- `landscape: true` swaps width/height
- margins: `marginTopDxa`, `marginBottomDxa`, `marginLeftDxa`, `marginRightDxa`

## createDocx gotchas
- **Never pack multi-paragraph content into one `text` with `\\n`.** Use separate `paragraph` blocks.
- **Page breaks** are their own `pageBreak` blocks (or a run with `breakType: PAGE`).
- **Tables need DXA widths** for predictable layout in Word/Google Docs.
- **Table shading:** hex/rgb fill; avoid relying on exotic theme colors.
- **Headings:** use built-in styles `Heading1`… so outlines/TOC consumers can see structure.
- **Images:** `imagePath` must exist on the local filesystem; keep dimensions reasonable to avoid huge files.
- Prefer professional fonts (`Arial`, `Times New Roman`) unless the user specifies otherwise.

## replaceDocxText
- Operates on body paragraphs **and** table cell paragraphs.
- `replaceAll` defaults to `true`.
- Word often splits a visual phrase across many runs. If a find fails, call `mergeDocxRuns` then retry.
- Cross-run replacements may collapse formatting on the affected paragraph to a single run.

## mergeDocxRuns
- Coalesces adjacent runs with identical formatting in the body (and tables).
- Strips proof-error markers where safe.
- Does not merge across tracked-change boundaries when they remain as separate structures.
- `outputPath` optional; default overwrites input.

## addDocxComment
- Creates a comments part via POI (`author` default `Codex`, `initials` default `C`).
- With `anchorText`: first body match gets `commentRangeStart` / `commentRangeEnd` / `commentReference`.
- Without `anchorText` (or if text not found): comment definition exists but is not visibly anchored — tool output warns.
- POI high-level only: does **not** guarantee Word 2016+ satellite parts (`commentsExtended`, `commentsIds`, `commentsExtensible`).

## acceptDocxTrackedChanges
- Accepts `w:ins` (keeps inserted runs) and removes `w:del` (drops deleted content) in the body.
- Best-effort on paragraph-mark revisions; residual empty paragraphs can remain (same class of edge cases as LibreOffice/pandoc notes).
- Prefer writing to a new `outputPath` when you need to keep the redlined original.

## read pagination
- `readDocxPreview`: default 20 body blocks, max 100.
- `readDocxContent`: `startBlock` is **1-based**; default max 100 blocks per call, hard cap 500.

## Out of scope
- LibreOffice / `soffice` PDF render, `pdftoppm` visual QA, pandoc
- Full OOXML XSD validation
- Legacy `.doc` conversion
- Perfect parity with multi-file comment extension packs

## Requirements for deliverables
- Follow the user spec literally (title, headings, table columns).
- After edits, re-read with `readDocxPreview` or `readDocxContent` to confirm.
- Document assumptions the reader will see (short note paragraph), not only in chat.
