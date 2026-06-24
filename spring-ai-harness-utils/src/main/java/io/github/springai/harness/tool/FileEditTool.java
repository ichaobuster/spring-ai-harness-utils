package io.github.springai.harness.tool;

/**
 * @author ichaobuster
 */
public interface FileEditTool {

	// Helper method to count occurrences of a substring
	default int countOccurrences(String text, String substring) {
		int count = 0;
		int index = 0;
		while ((index = text.indexOf(substring, index)) != -1) {
			count++;
			index += substring.length();
		}
		return count;
	}

	// Helper method to replace first occurrence
	default String replaceFirst(String text, String old_string, String new_string) {
		int index = text.indexOf(old_string);
		if (index == -1) {
			return text;
		}
		return text.substring(0, index) + new_string + text.substring(index + old_string.length());
	}

	// Helper method to replace all occurrences (literal, not regex)
	default String replaceAll(String text, String old_string, String new_string) {
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
	default String generateEditSnippet(String fileContent, String newString) {
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
