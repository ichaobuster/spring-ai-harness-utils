package io.github.springai.harness.masking;

import java.util.List;

/**
 * Contract implemented by preset and custom C2 sensitive-data recognizers.
 */
public interface C2DataRecognizer {

	/**
	 * Detect sensitive spans in the supplied text.
	 */
	List<C2DataMatch> detect(String text);

	/**
	 * Maximum number of characters a single match can occupy. Streaming masking uses
	 * this value to avoid releasing incomplete matches.
	 */
	int maxMatchLength();

}
