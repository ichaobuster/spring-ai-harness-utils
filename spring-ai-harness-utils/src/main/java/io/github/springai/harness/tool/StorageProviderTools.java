package io.github.springai.harness.tool;

import io.github.springai.harness.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * StorageProviderTools 作为 FileSystemTools 使用 StorageProvider 的 Harness 替代方案
 *
 * @author ichaobuster
 */
@Slf4j
public class StorageProviderTools implements FileEditTool {

	private static final Integer GREP_MAX_OUTPUT_LENGTH = 50_000;

	private final StorageProvider storageProvider;

	public StorageProviderTools(StorageProvider storageProvider) {
		Assert.notNull(storageProvider, "storageProvider must not be null");
		this.storageProvider = storageProvider;
	}

	// @formatter:off
	@Tool(name = "Read", description = """
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
			@ToolParam(description = "The relative path to the file to read, relative to the workspace") String filePath,
			@ToolParam(description = "The line number to start reading from. Only provide if the file is too large to read at once", required = false) Integer offset,
			@ToolParam(description = "The number of lines to read. Only provide if the file is too large to read at once.", required = false) Integer limit) { // @formatter:on

		try {
			if (!storageProvider.exists(filePath)) {
				return "Error: File does not exist: " + filePath;
			}

			if (storageProvider.isDirectory(filePath)) {
				return "Error: Path is a directory, not a file: " + filePath;
			}

			// Default values
			int maxLines = limit != null ? limit : storageProvider.MAX_LINES;
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
					.map(line -> String.format("%6d\t%s", currentLine.getAndIncrement(), line.length() > storageProvider.MAX_LINE_LENGTH ? line.substring(0, storageProvider.MAX_LINE_LENGTH) + "... (line truncated)" : line))
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
	@Tool(name = "Write", description = """
		Writes a file to the filesystem.

		Usage:
		- This tool will overwrite the existing file if there is one at the provided path.
		- If this is an existing file, you MUST use the Read tool first to read the file's contents. This tool will fail if you did not read the file first.
		- ALWAYS prefer editing existing files in the codebase. NEVER write new files unless explicitly required.
		- NEVER proactively create documentation files (*.md) or README files. Only create documentation files if explicitly requested by the User.
		- Only use emojis if the user explicitly requests it. Avoid writing emojis to files unless asked.	
		""")
	public String write(
			@ToolParam(description = "The relative path to the file to write, relative to the workspace") String filePath,
			@ToolParam(description = "The content to write to the file") String content) { // @formatter:on

		try {
			content = content != null ? content : "";

			// Check if file already exists
			boolean fileExists = this.storageProvider.exists(filePath);

			// Write content to file
			this.storageProvider.writeString(filePath, content);

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
	@Tool(name = "Edit", description = """
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
			@ToolParam(description = "The relative path to the file to modify, relative to the workspace") String filePath,
			@ToolParam(description = "The text to replace") String oldString,
			@ToolParam(description = "The text to replace it with (must be different from oldString)") String newString,
			@ToolParam(description = "Replace all occurences of oldString (default false)", required = false) Boolean replaceAll) { // @formatter:on

		try {
			if (!this.storageProvider.exists(filePath)) {
				return "Error: File does not exist: " + filePath;
			}

			if (this.storageProvider.isDirectory(filePath)) {
				return "Error: Path is a directory, not a file: " + filePath;
			}

			// Validate that oldString and newString are different
			if (oldString.equals(newString)) {
				return "Error: oldString and newString must be different";
			}

			// Read the entire file content preserving exact line endings
			String originalContent = this.storageProvider.readString(filePath);

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
			this.storageProvider.writeString(filePath, newContent);

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
	@Tool(name = "Glob", description = """
        - Fast file pattern matching tool that works with any codebase size
        - Supports glob patterns like "**/*.js" or "src/**/*.ts"
        - Returns matching file paths sorted by modification time, limited to 100 files
        - Use this tool when you need to find files by name patterns
        - When you are doing an open ended search that may require multiple rounds of globbing and grepping, use the Agent tool instead
        - You can call multiple tools in a single response. It is always better to speculatively perform multiple searches in parallel if they are potentially useful.
		""")
	public String glob(
			@ToolParam(description = "The glob pattern to match files against") String pattern,
			@ToolParam(description = "The directory to search in. If not specified, the current workspace directory will be used. IMPORTANT: Omit this field to use the default directory. DO NOT enter \\\"undefined\\\" or \\\"null\\\" - simply omit it for the default behavior. Must be a valid directory path if provided.", required = false) String path) { // @formatter:on

		Assert.hasText(pattern, "	The glob pattern must not be empty");

		try {
			if (!this.storageProvider.exists(path)) {
				return "Error: Path does not exist: " + path;
			}

			if (!this.storageProvider.isDirectory(path)) {
				return "Error: Path is not a directory: " + path;
			}

			List<String> result = this.storageProvider.glob(pattern, path);

			return result.stream().collect(Collectors.joining("\n"));

		} catch (Exception e) {
			return "Error executing glob: " + e.getMessage();
		}
	}

	// @formatter:off
	@Tool(name = "Grep", description = """
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
			@ToolParam(description = "The regular expression pattern to search for in file contents") String pattern,
			@ToolParam(description = "File or directory to search in. Defaults to workspace directory.", required = false) String path,
			@ToolParam(description = "Glob pattern to filter files (e.g. \"*.js\", \"**/*.tsx\")", required = false) String glob,
			@ToolParam(description = "Output mode: \"content\" shows matching lines (supports -A/-B/-C context, -n line numbers, head_limit), \"files_with_matches\" shows file paths (supports head_limit), \"count\" shows match counts (supports head_limit). Defaults to \"files_with_matches\".", required = false) StorageProvider.GrepOutputMode outputMode,
			@ToolParam(description = "Number of lines to show before each match. Requires output_mode: \"content\", ignored otherwise.", required = false) Integer contextBefore,
			@ToolParam(description = "Number of lines to show after each match. Requires output_mode: \"content\", ignored otherwise.", required = false) Integer contextAfter,
			@ToolParam(description = "Number of lines to show before and after each match. Requires output_mode: \"content\", ignored otherwise.", required = false) Integer context,
			@ToolParam(description = "Show line numbers in output. Requires output_mode: \"content\", ignored otherwise. Defaults to true.", required = false) Boolean showLineNumbers,
			@ToolParam(description = "Case insensitive search", required = false) Boolean caseInsensitive,
			@ToolParam(description = "Limit output to first N lines/entries, equivalent to \"| head -N\". Works across all output modes: content (limits output lines), files_with_matches (limits file paths), count (limits count entries). Defaults to 250 when unspecified. Pass 0 for unlimited (use sparingly — large result sets waste context).", required = false) Integer headLimit,
			@ToolParam(description = "Skip first N lines/entries before applying head_limit. Works across all output modes. Defaults to 0.", required = false) Integer offset,
			@ToolParam(description = "Enable multiline mode where . matches newlines and patterns can span lines. Default: false.", required = false) Boolean multiline) { // @formatter:on

		try {
			List<String> result = this.storageProvider.grep(pattern, path, glob, outputMode, contextBefore, contextAfter, context, showLineNumbers, caseInsensitive, headLimit, offset, multiline);
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
		String formattedResult = result.stream().collect(Collectors.joining("\n"));
		// Truncate if too long
		if (formattedResult.length() > GREP_MAX_OUTPUT_LENGTH) {
			formattedResult = formattedResult.substring(0, GREP_MAX_OUTPUT_LENGTH) + "\n... (output truncated, "
					+ (formattedResult.length() - GREP_MAX_OUTPUT_LENGTH) + " characters omitted)";
		}

		return formattedResult;
	}

	public static Builder builder(StorageProvider storageProvider) {
		return new Builder(storageProvider);
	}

	public static class Builder {

		private StorageProvider storageProvider;

		/**
		 * Set the storage provider.
		 *
		 * @param storageProvider the storage provider
		 * @return this builder
		 */

		public Builder(StorageProvider storageProvider) {
			this.storageProvider = storageProvider;
		}

		public StorageProviderTools build() {
			return new StorageProviderTools(this.storageProvider);
		}

	}

}
