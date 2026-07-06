package io.github.springai.harness.tool;

import io.github.springai.harness.storage.StorageProvider;
import io.github.springai.harness.storage.StorageProviderFactory;
import io.modelcontextprotocol.common.McpTransportContext;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * StorageProviderTools 作为 FileSystemTools 使用 StorageProvider 的 Harness 替代方案
 *
 * @author buyc
 */
@Component
@Slf4j
public class FileSystemTools {

	private static final Integer GREP_MAX_OUTPUT_LENGTH = 50_000;

	@Autowired
	private StorageProviderFactory storageProviderFactory;

	@Autowired
	private io.github.springai.harness.skill.SkillProvider skillProvider;

	protected StorageProvider getStorageProvider(McpTransportContext context) {
		return storageProviderFactory.getStorageProvider(context);
	}

	// @formatter:off
	@McpTool(name = "Read", description = """
		Reads a file from the filesystem. You can access any file directly by using this tool.
		Assume this tool is able to read all files on the machine. If the User provides a path to a file assume that path is valid. It is okay to read a file that does not exist; an error will be returned.

		Usage:
		- The filePath parameter must be a path relative to the workspace directory
		- By default, it reads up to 2000 lines starting from the beginning of the file
		- You can optionally specify a line offset and limit (especially handy for long files), but it's recommended to read the whole file by not providing these parameters
		- Any lines longer than 2000 characters will be truncated
		- Results are returned using cat -n format, with line numbers starting at 1
		- This tool can only read files, not directories.
		- You can call multiple tools in a single response. It is always better to speculatively read multiple potentially useful files in parallel.
		- If you read a file that exists but has empty contents you will receive a system reminder warning in place of file contents.
		""")
	public String read(
			McpTransportContext context,
			@McpToolParam(description = "The relative path to the file to read, relative to the workspace") String filePath,
			@McpToolParam(description = "The line number to start reading from. Only provide if the file is too large to read at once", required = false) Integer offset,
			@McpToolParam(description = "The number of lines to read. Only provide if the file is too large to read at once.", required = false) Integer limit) { // @formatter:on

		StorageProvider storageProvider = getStorageProvider(context);
		try {
			if (!storageProvider.exists(filePath)) {
				return "Error: File does not exist: " + filePath;
			}

			if (storageProvider.isDirectory(filePath)) {
				return "Error: Path is a directory, not a file: " + filePath;
			}

			// Default values
			int maxLines = limit != null ? limit : StorageProvider.MAX_LINES;
			int realOffset = (offset == null || offset < 1) ? 1 : offset;

			List<String> rawLines = storageProvider.readAllLines(filePath);
			if (rawLines.isEmpty()) {
				return "File is empty: " + filePath;
			}
			if (realOffset > rawLines.size()) {
				return String.format("No lines to read. File has %d lines, but offset was %d", rawLines.size(),
						offset);
			}

			AtomicInteger currentLine = new AtomicInteger(realOffset);
			List<String> lines = rawLines.stream()
					.skip(realOffset - 1)
					.limit(maxLines)
					.map(line -> String.format("%6d\t%s", currentLine.getAndIncrement(), line.length() > StorageProvider.MAX_LINE_LENGTH ? line.substring(0, StorageProvider.MAX_LINE_LENGTH) + "... (line truncated)" : line))
					.collect(Collectors.toList());

			StringBuilder result = new StringBuilder();
			result.append(String.format("File: %s\n", filePath));
			result.append(
					String.format("Showing lines %d-%d of %d\n\n", realOffset, realOffset + lines.size() - 1, rawLines.size()));

			for (String line : lines) {
				result.append(line).append("\n");
			}

			return result.toString();

		} catch (IOException e) {
			return "Error reading file: " + e.getMessage();
		}
	}

	// @formatter:off
	@McpTool(name = "Write", description = """
		Writes a file to the filesystem.

		Usage:
		- This tool will overwrite the existing file if there is one at the provided path.
		- If this is an existing file, you MUST use the Read tool first to read the file's contents. This tool will fail if you did not read the file first.
		- ALWAYS prefer editing existing files in the codebase. NEVER write new files unless explicitly required.
		- NEVER proactively create documentation files (*.md) or README files. Only create documentation files if explicitly requested by the User.
		- Only use emojis if the user explicitly requests it. Avoid writing emojis to files unless asked.
		""")
	public String write(
			McpTransportContext context,
			@McpToolParam(description = "The relative path to the file to write, relative to the workspace") String filePath,
			@McpToolParam(description = "The content to write to the file") String content) { // @formatter:on

		StorageProvider storageProvider = getStorageProvider(context);
		try {
			content = content != null ? content : "";

			// Check if file already exists
			boolean fileExists = storageProvider.exists(filePath);

			// Write content to file
			storageProvider.writeString(filePath, content);

			if (fileExists) {
				return String.format("Successfully overwrote file: %s (%d bytes)", filePath, content.length());
			} else {
				return String.format("Successfully created file: %s (%d bytes)", filePath, content.length());
			}

		} catch (IOException e) {
			return "Error writing file: " + e.getMessage();
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	// @formatter:off
	@McpTool(name = "Edit", description = """
		Performs exact string replacements in files.

		Usage:
		- You must use your `Read` tool at least once in the conversation before editing. This tool will error if you attempt an edit without reading the file.
		- When editing text from Read tool output, ensure you preserve the exact indentation (tabs/spaces) as it appears AFTER the line number prefix. The line number prefix format is: spaces + line number + tab. Everything after that tab is the actual file content to match. Never include any part of the line number prefix in the oldString or newString.
		- ALWAYS prefer editing existing files in the codebase. NEVER write new files unless explicitly required.
		- Only use emojis if the user explicitly requests it. Avoid adding emojis to files unless asked.
		- The edit will FAIL if `oldString` is not unique in the file. Either provide a larger string with more surrounding context to make it unique or use `replaceAll` to change every instance of `oldString`.
		- Use `replaceAll` for replacing and renaming strings across the file. This parameter is useful if you want to rename a variable for instance.
		""")
	public String edit(
			McpTransportContext context,
			@McpToolParam(description = "The relative path to the file to modify, relative to the workspace") String filePath,
			@McpToolParam(description = "The text to replace") String oldString,
			@McpToolParam(description = "The text to replace it with (must be different from oldString)") String newString,
			@McpToolParam(description = "Replace all occurences of oldString (default false)", required = false) Boolean replaceAll) { // @formatter:on

		StorageProvider storageProvider = getStorageProvider(context);
		try {
			if (!storageProvider.exists(filePath)) {
				return "Error: File does not exist: " + filePath;
			}

			if (storageProvider.isDirectory(filePath)) {
				return "Error: Path is a directory, not a file: " + filePath;
			}

			// Validate that oldString and newString are different
			if (oldString.equals(newString)) {
				return "Error: oldString and newString must be different";
			}

			// Read the entire file content preserving exact line endings
			String originalContent = storageProvider.readString(filePath);

			// Count occurrences
			int occurrences = countOccurrences(originalContent, oldString);

			if (occurrences == 0) {
				return "Error: oldString not found in file: " + filePath;
			}

			if (!Boolean.TRUE.equals(replaceAll) && occurrences > 1) {
				return String.format(
						"Error: oldString appears %d times in the file. Either provide a larger string with more surrounding context to make it unique or use replaceAll=true to change all instances.",
						occurrences);
			}

			// Perform replacement
			String newContent;
			if (Boolean.TRUE.equals(replaceAll)) {
				// Replace all occurrences using literal string replacement
				newContent = replaceAll(originalContent, oldString, newString);
			} else {
				// Replace first occurrence only
				newContent = replaceFirst(originalContent, oldString, newString);
			}

			// Write the modified content back to the file
			storageProvider.writeString(filePath, newContent);

			// Generate a snippet showing the context around the edit
			String snippet = generateEditSnippet(newContent, newString);

			// Return formatted response matching Claude Code's Edit tool format
			return String.format(
					"The file %s has been updated. Here's the result of running `cat -n` on a snippet of the edited file:\n%s",
					filePath, snippet);

		} catch (IOException e) {
			return "Error editing file: " + e.getMessage();
		}
	}

	// @formatter:off
	@McpTool(name = "Glob", description = """
        - Fast file pattern matching tool that works with any codebase size
        - Supports glob patterns like "**/*.js" or "src/**/*.ts"
        - Returns matching file paths sorted by modification time, limited to 100 files
        - Use this tool when you need to find files by name patterns
        - When you are doing an open ended search that may require multiple rounds of globbing and grepping, use the Agent tool instead
        - You can call multiple tools in a single response. It is always better to speculatively perform multiple searches in parallel if they are potentially useful.
        """)

	public String glob(
			McpTransportContext context,
			@McpToolParam(description = "The glob pattern to match files against") String pattern,
			@McpToolParam(description = "The directory to search in. If not specified, the current workspace directory will be used. IMPORTANT: Omit this field to use the default directory. DO NOT enter \\\"undefined\\\" or \\\"null\\\" - simply omit it for the default behavior. Must be a valid directory path if provided.", required = false) String path) { // @formatter:on

		StorageProvider storageProvider = getStorageProvider(context);
		Assert.hasText(pattern, "	The glob pattern must not be empty");

		try {
			if (!storageProvider.exists(path)) {
				return "Error: Path does not exist: " + path;
			}

			if (!storageProvider.isDirectory(path)) {
				return "Error: Path is not a directory: " + path;
			}

			List<String> result = storageProvider.glob(pattern, path);

			return String.join("\n", result);

		} catch (Exception e) {
			return "Error executing glob: " + e.getMessage();
		}
	}

	// @formatter:off
	@McpTool(name = "Grep", description = """
		A powerful search tool built with pure Java (no external dependencies required)

		Usage:
		- ALWAYS use Grep for search tasks. NEVER invoke `grep` or `rg` as a Bash command. The Grep tool has been optimized for correct permissions and access.
		- Supports full regex syntax (e.g., "log.*Error", "function\\s+\\w+")
		- Filter files with glob parameter (e.g., "*.js", "**/*.tsx") or type parameter (e.g., "js", "py", "rust")
		- Output modes: "content" shows matching lines, "files_with_matches" shows only file paths (default), "count" shows match counts
		- Use Task tool for open-ended searches requiring multiple rounds
		- Pattern syntax: Java regex - use standard Java regex escaping
		- Multiline matching: By default patterns match within single lines only. For cross-line patterns, use `multiline: true`

		Note: This is a pure Java implementation that doesn't require ripgrep installation. But it provides similar functionality.
		""")
	public String grep(
			McpTransportContext mcpSyncRequestContext,
			@McpToolParam(description = "The regular expression pattern to search for in file contents") String pattern,
			@McpToolParam(description = "File or directory to search in. Defaults to workspace directory.", required = false) String path,
			@McpToolParam(description = "Glob pattern to filter files (e.g. \"*.js\", \"**/*.tsx\")", required = false) String glob,
			@McpToolParam(description = "Output mode: \"content\" shows matching lines (supports -A/-B/-C context, -n line numbers, head_limit), \"files_with_matches\" shows file paths (supports head_limit), \"count\" shows match counts (supports head_limit). Defaults to \"files_with_matches\".", required = false) StorageProvider.GrepOutputMode outputMode,
			@McpToolParam(description = "Number of lines to show before each match. Requires output_mode: \"content\", ignored otherwise.", required = false) Integer contextBefore,
			@McpToolParam(description = "Number of lines to show after each match. Requires output_mode: \"content\", ignored otherwise.", required = false) Integer contextAfter,
			@McpToolParam(description = "Number of lines to show before and after each match. Requires output_mode: \"content\", ignored otherwise.", required = false) Integer context,
			@McpToolParam(description = "Show line numbers in output. Requires output_mode: \"content\", ignored otherwise. Defaults to true.", required = false) Boolean showLineNumbers,
			@McpToolParam(description = "Case insensitive search", required = false) Boolean caseInsensitive,
			@McpToolParam(description = "Limit output to first N lines/entries, equivalent to \"| head -N\". Works across all output modes: content (limits output lines), files_with_matches (limits file paths), count (limits count entries). Defaults to 250 when unspecified. Pass 0 for unlimited (use sparingly — large result sets waste context).", required = false) Integer headLimit,
			@McpToolParam(description = "Skip first N lines/entries before applying head_limit. Works across all output modes. Defaults to 0.", required = false) Integer offset,
			@McpToolParam(description = "Enable multiline mode where . matches newlines and patterns can span lines. Default: false.", required = false) Boolean multiline) { // @formatter:on

		StorageProvider storageProvider = getStorageProvider(mcpSyncRequestContext);
		try {
			List<String> result = storageProvider.grep(pattern, path, glob, outputMode, contextBefore, contextAfter, context, showLineNumbers, caseInsensitive, headLimit, offset, multiline);
			return formatGrepResults(result, pattern);
		} catch (IOException e) {
			log.error(e.getMessage(), e);
			return "Failed to grep for pattern \"%s\": %s".formatted(pattern, e.getMessage());
		}

	}

	private String formatGrepResults(List<String> result, String pattern) {
		if (CollectionUtils.isEmpty(result)) {
			return "No matches found for pattern: " + pattern;
		}
		String formattedResult = String.join("\n", result);
		// Truncate if too long
		if (formattedResult.length() > GREP_MAX_OUTPUT_LENGTH) {
			formattedResult = formattedResult.substring(0, GREP_MAX_OUTPUT_LENGTH) + "\n... (output truncated, "
					+ (formattedResult.length() - GREP_MAX_OUTPUT_LENGTH) + " characters omitted)";
		}

		return formattedResult;
	}

	// @formatter:off
	@McpTool(name = "ListDirectory", description = """
		Lists the contents of a directory in the workspace.

		Usage:
		- The path parameter must be a path relative to the workspace directory.
		- If path is omitted or empty, lists the workspace root directory.
		- Returns entries with details including name, type (file or directory), size, and last modified timestamp.
		""")
	public String listDirectory(
			McpTransportContext context,
			@McpToolParam(description = "The relative path to the directory to list. Omit to list the workspace root directory.", required = false) String path) { // @formatter:on

		StorageProvider storageProvider = getStorageProvider(context);
		try {
			String targetPath = (path == null || path.isBlank()) ? "" : path;

			if (!storageProvider.exists(targetPath)) {
				return "Error: Path does not exist: " + (targetPath.isEmpty() ? "." : targetPath);
			}

			if (!storageProvider.isDirectory(targetPath)) {
				return "Error: Path is a file, not a directory: " + targetPath;
			}

			List<StorageProvider.Info> items = storageProvider.listDirectory(targetPath);
			items = items.stream()
					.filter(item -> !storageProvider.isIgnoredPath("/" + item.path() + "/"))
					.collect(Collectors.toList());

			String displayPath = targetPath.isEmpty() ? "." : targetPath;
			if (items.isEmpty()) {
				return "Directory is empty: " + displayPath;
			}

			StringBuilder result = new StringBuilder();
			result.append(String.format("Directory listing for: %s\n\n", displayPath));
			result.append(String.format("%-10s %-12s %-24s %s\n", "TYPE", "SIZE", "MODIFIED", "NAME"));
			result.append("-".repeat(60)).append("\n");

			for (StorageProvider.Info item : items) {
				String type = item.isDirectory() ? "<DIR>" : "<FILE>";
				String sizeStr = item.isDirectory() ? "-" : String.valueOf(item.size()) + " B";
				String modifiedStr = item.lastModified() > 0 ? new java.util.Date(item.lastModified()).toString() : "-";
				result.append(String.format("%-10s %-12s %-24s %s\n", type, sizeStr, modifiedStr, item.path()));
			}

			return result.toString();

		} catch (IOException e) {
			return "Error listing directory: " + e.getMessage();
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	// @formatter:off
	@McpTool(name = "Trash", description = """
		Moves a file or directory to the workspace trash (.trash/).
		Prefer using Trash over permanent deletion to allow safety and potential recovery.

		Usage:
		- The filePath parameter must be a path relative to the workspace directory.
		- If the file or directory exists, it will be safely moved to .trash/ inside the workspace.
		""")
	public String trash(
			McpTransportContext context,
			@McpToolParam(description = "The relative path to the file or directory to move to trash") String filePath) { // @formatter:on

		StorageProvider storageProvider = getStorageProvider(context);
		try {
			if (!storageProvider.exists(filePath)) {
				return "Error: File or directory does not exist: " + filePath;
			}

			storageProvider.trash(filePath);
			return String.format("Successfully moved to trash: %s", filePath);

		} catch (IOException e) {
			return "Error moving file or directory to trash: " + e.getMessage();
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	// @formatter:off
	@McpTool(name = "ListSkills", description = """
		Lists all available skills in the workspace (including user skills and global shared skills).
		Returns skill names, descriptions, and their sources (user or global).
		Use ReadSkill to view the full instructions for a specific skill.
		""")
	public String listSkills(McpTransportContext context) { // @formatter:on
		try {
			List<io.github.springai.harness.skill.SkillInfo> skills = skillProvider.listSkills(context);
			if (skills.isEmpty()) {
				return "No skills found.";
			}

			StringBuilder result = new StringBuilder();
			result.append("Available Skills:\n\n");
			result.append(String.format("%-24s %-10s %s\n", "NAME", "SOURCE", "DESCRIPTION"));
			result.append("-".repeat(70)).append("\n");

			for (io.github.springai.harness.skill.SkillInfo skill : skills) {
				result.append(String.format("%-24s %-10s %s\n", skill.name(), skill.source(), skill.description()));
			}

			return result.toString();
		} catch (Exception e) {
			return "Error listing skills: " + e.getMessage();
		}
	}

	// @formatter:off
	@McpTool(name = "ReadSkill", description = """
		Reads the full content of a specified skill (SKILL.md instructions).

		Usage:
		- Provide the skillName returned by ListSkills.
		- Returns the complete markdown instructions for executing the skill.
		""")
	public String readSkill(
			McpTransportContext context,
			@McpToolParam(description = "The name of the skill to read") String skillName) { // @formatter:on
		try {
			if (skillName == null || skillName.isBlank()) {
				return "Error: skillName must not be empty.";
			}
			return skillProvider.readSkill(context, skillName.trim());
		} catch (Exception e) {
			return "Error reading skill: " + e.getMessage();
		}
	}

	// Helper method to count occurrences of a substring
	private int countOccurrences(String text, String substring) {
		int count = 0;
		int index = 0;
		while ((index = text.indexOf(substring, index)) != -1) {
			count++;
			index += substring.length();
		}
		return count;
	}

	// Helper method to replace first occurrence
	private String replaceFirst(String text, String old_string, String new_string) {
		int index = text.indexOf(old_string);
		if (index == -1) {
			return text;
		}
		return text.substring(0, index) + new_string + text.substring(index + old_string.length());
	}

	// Helper method to replace all occurrences (literal, not regex)
	private String replaceAll(String text, String old_string, String new_string) {
		StringBuilder result = new StringBuilder();
		int index = 0;
		int lastIndex = 0;

		while ((index = text.indexOf(old_string, lastIndex)) != -1) {
			result.append(text, lastIndex, index);
			result.append(new_string);
			lastIndex = index + old_string.length();
		}
		result.append(text.substring(lastIndex));

		return result.toString();
	}

	/**
	 * Generates a formatted snippet of the file showing context around the edited
	 * section. Matches Claude Code's Edit tool output format with line numbers and arrow
	 * separator.
	 *
	 * @param fileContent the complete file content after editing
	 * @param newString   the new string that was inserted (used to find the edit location)
	 * @return formatted snippet with line numbers
	 */
	private String generateEditSnippet(String fileContent, String newString) {
		String[] lines = fileContent.split("\n", -1);

		// Find the line where the new content appears
		int editStartLine = -1;
		int editEndLine = -1;

		// Split new_string into lines to find where it appears in the file
		String[] newLines = newString.split("\n", -1);

		// Search for the first line of the new content
		for (int i = 0; i < lines.length; i++) {
			if (newLines.length > 0 && lines[i].contains(newLines[0])) {
				// Check if subsequent lines match (for multi-line edits)
				boolean matches = true;
				for (int j = 1; j < newLines.length && i + j < lines.length; j++) {
					if (!lines[i + j].contains(newLines[j])) {
						matches = false;
						break;
					}
				}
				if (matches) {
					editStartLine = i;
					editEndLine = i + newLines.length - 1;
					break;
				}
			}
		}

		// If we didn't find the edit location, show the beginning of the file
		if (editStartLine == -1) {
			editStartLine = 0;
			editEndLine = Math.min(10, lines.length - 1);
		}

		// Show context: ~5 lines before and ~5 lines after the edit
		int startLine = Math.max(0, editStartLine - 5);
		int endLine = Math.min(lines.length - 1, editEndLine + 5);

		// Build the snippet with line numbers (1-indexed, right-aligned with arrow)
		StringBuilder snippet = new StringBuilder();
		for (int i = startLine; i <= endLine; i++) {
			// Line numbers are 1-indexed and right-aligned to 6 characters
			snippet.append(String.format("%6d→%s", i + 1, lines[i]));
			if (i < endLine) {
				snippet.append("\n");
			}
		}

		return snippet.toString();
	}

}
