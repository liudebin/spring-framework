/*
 * Copyright 2002-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.util.pattern;

import org.springframework.http.server.reactive.PathContainer;
import org.springframework.http.server.reactive.PathContainer.Element;
import org.springframework.http.server.reactive.PathContainer.Segment;
import org.springframework.http.server.reactive.PathContainer.Separator;
import org.springframework.lang.Nullable;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.util.PathMatcher;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a parsed path pattern. Includes a chain of path elements
 * for fast matching and accumulates computed state for quick comparison of
 * patterns.
 *
 * <p>PathPatterns match URL paths using the following rules:<br>
 * <ul>
 * <li>{@code ?} matches one character</li>
 * <li>{@code *} matches zero or more characters within a path segment</li>
 * <li>{@code **} matches zero or more <em>path segments</em> until the end of the path</li>
 * <li>{@code {spring}} matches a <em>path segment</em> and captures it as a variable named "spring"</li>
 * <li>{@code {spring:[a-z]+}} matches the regexp {@code [a-z]+} as a path variable named "spring"</li>
 * <li>{@code {*spring}} matches zero or more <em>path segments</em> until the end of the path
 * and captures it as a variable named "spring"</li>
 * </ul>
 *
 * <h3>Examples</h3>
 * <ul>
 * <li>{@code /pages/t?st.html} &mdash; matches {@code /pages/test.html} but also
 * {@code /pages/tast.html} but not {@code /pages/toast.html}</li>
 * <li>{@code /resources/*.png} &mdash; matches all {@code .png} files in the
 * {@code resources} directory</li>
 * <li><code>/resources/&#42;&#42;</code> &mdash; matches all files
 * underneath the {@code /resources/} path, including {@code /resources/image.png}
 * and {@code /resources/css/spring.css}</li>
 * <li><code>/resources/{&#42;path}</code> &mdash; matches all files
 * underneath the {@code /resources/} path and captures their relative path in
 * a variable named "path"; {@code /resources/image.png} will match with
 * "spring" -> "/image.png", and {@code /resources/css/spring.css} will match
 * with "spring" -> "/css/spring.css"</li>
 * <li>{@code /resources/{filename:\\w+}.dat} will match {@code /resources/spring.dat}
 * and assign the value {@code "spring"} to the {@code filename} variable</li>
 * </ul>
 *
 * @author Andy Clement
 * @since 5.0
 */
public class PathPattern implements Comparable<PathPattern> {

	private final static PathContainer EMPTY_PATH = PathContainer.parse("", StandardCharsets.UTF_8);

	/** The parser used to construct this pattern */
	private final PathPatternParser parser;

	/** First path element in the parsed chain of path elements for this pattern */
	@Nullable
	private final PathElement head;

	/** The text of the parsed pattern */
	private String patternString;

	/** The separator used when parsing the pattern */
	private char separator;

	/** Will this match candidates in a case sensitive way? (case sensitivity  at parse time) */
	private boolean caseSensitive;

	/** If this pattern has no trailing slash, allow candidates to include one and still match successfully */
	private boolean allowOptionalTrailingSlash;

	/** How many variables are captured in this pattern */
	private int capturedVariableCount;

	/**
	 * The normalized length is trying to measure the 'active' part of the pattern. It is computed
	 * by assuming all captured variables have a normalized length of 1. Effectively this means changing
	 * your variable name lengths isn't going to change the length of the active part of the pattern.
	 * Useful when comparing two patterns.
	 */
	private int normalizedLength;

	/**
	 * Does the pattern end with '&lt;separator&gt;*' 
	 */
	private boolean endsWithSeparatorWildcard = false;

	/**
	 * Score is used to quickly compare patterns. Different pattern components are given different
	 * weights. A 'lower score' is more specific. Current weights:
	 * <ul>
	 * <li>Captured variables are worth 1
	 * <li>Wildcard is worth 100
	 * </ul>
	 */
	private int score;

	/** Does the pattern end with {*...} */
	private boolean catchAll = false;


	PathPattern(String patternText, PathPatternParser parser, @Nullable PathElement head,
			char separator, boolean caseSensitive, boolean allowOptionalTrailingSlash) {

		this.patternString = patternText;
		this.parser = parser;
		this.head = head;
		this.separator = separator;
		this.caseSensitive = caseSensitive;
		this.allowOptionalTrailingSlash = allowOptionalTrailingSlash;

		// Compute fields for fast comparison
		PathElement elem = head;
		while (elem != null) {
			this.capturedVariableCount += elem.getCaptureCount();
			this.normalizedLength += elem.getNormalizedLength();
			this.score += elem.getScore();
			if (elem instanceof CaptureTheRestPathElement || elem instanceof WildcardTheRestPathElement) {
				this.catchAll = true;
			}
			if (elem instanceof SeparatorPathElement && elem.next != null &&
					elem.next instanceof WildcardPathElement && elem.next.next == null) {
				this.endsWithSeparatorWildcard = true;
			}
			elem = elem.next;
		}
	}


	/**
	 * @return the original pattern string that was parsed to create this PathPattern.
	 */
	public String getPatternString() {
		return this.patternString;
	}


	// TODO: remove String-variants

	public boolean matches(String path) {
		return matches(PathContainer.parse(path, StandardCharsets.UTF_8));
	}

	public PathMatchResult matchAndExtract(String path) {
		return matchAndExtract(PathContainer.parse(path, StandardCharsets.UTF_8));
	}

	@Nullable
	public PathRemainingMatchInfo getPathRemaining(String path) {
		return getPathRemaining(PathContainer.parse(path, StandardCharsets.UTF_8));
	}


	/**
	 * @param pathContainer the candidate path container to attempt to match against this pattern
	 * @return true if the path matches this pattern
	 */
	public boolean matches(PathContainer pathContainer) {
		if (this.head == null) {
			return !hasLength(pathContainer);
		}
		else if (!hasLength(pathContainer)) {
			if (this.head instanceof WildcardTheRestPathElement || this.head instanceof CaptureTheRestPathElement) {
				pathContainer = EMPTY_PATH; // Will allow CaptureTheRest to bind the variable to empty
			}
			else {
				return false;
			}
		}
		MatchingContext matchingContext = new MatchingContext(pathContainer, false);
		return this.head.matches(0, matchingContext);
	}
	
	/**
	 * For a given path return the remaining piece that is not covered by this PathPattern.
	 * @param pathContainer a path that may or may not match this path pattern
	 * @return a {@link PathRemainingMatchInfo} describing the match result,
	 * or {@code null} if the path does not match this pattern
	 */
	@Nullable
	public PathRemainingMatchInfo getPathRemaining(PathContainer pathContainer) {
		if (this.head == null) {
			return new PathRemainingMatchInfo(pathContainer);
		}
		else if (!hasLength(pathContainer)) {
			return null;
		}

		MatchingContext matchingContext = new MatchingContext(pathContainer, true);
		matchingContext.setMatchAllowExtraPath();
		boolean matches = this.head.matches(0, matchingContext);
		if (!matches) {
			return null;
		}
		else {
			PathRemainingMatchInfo info;
			if (matchingContext.remainingPathIndex == pathContainer.elements().size()) {
				info = new PathRemainingMatchInfo(EMPTY_PATH, matchingContext.getPathMatchResult());
			}
			else {
				info = new PathRemainingMatchInfo(PathContainer.subPath(pathContainer, matchingContext.remainingPathIndex),
						 matchingContext.getPathMatchResult());
			}
			return info;
		}
	}

	/**
	 * @param pathContainer the path to check against the pattern
	 * @return true if the pattern matches as much of the path as is supplied
	 */
	public boolean matchStart(PathContainer pathContainer) {
		if (this.head == null) {
			return !hasLength(pathContainer);
		}
		else if (!hasLength(pathContainer)) {
			return true;
		}
		MatchingContext matchingContext = new MatchingContext(pathContainer, false);
		matchingContext.setMatchStartMatching(true);
		return this.head.matches(0, matchingContext);
	}

	/**
	 * @param pathContainer a path that matches this pattern from which to extract variables
	 * @return a map of extracted variables - an empty map if no variables extracted. 
	 * @throws IllegalStateException if the path does not match the pattern
	 */
	public PathMatchResult matchAndExtract(PathContainer pathContainer) {
		MatchingContext matchingContext = new MatchingContext(pathContainer, true);
		if (this.head != null && this.head.matches(0, matchingContext)) {
			return matchingContext.getPathMatchResult();
		}
		else if (!hasLength(pathContainer)) {
			return PathMatchResult.EMPTY;
		}
		else {
			throw new IllegalStateException("Pattern \"" + this + "\" is not a match for \"" + pathContainer.value() + "\"");
		}
	}

	/**
	 * Given a full path, determine the pattern-mapped part. <p>For example: <ul>
	 * <li>'{@code /docs/cvs/commit.html}' and '{@code /docs/cvs/commit.html} -> ''</li>
	 * <li>'{@code /docs/*}' and '{@code /docs/cvs/commit} -> '{@code cvs/commit}'</li>
	 * <li>'{@code /docs/cvs/*.html}' and '{@code /docs/cvs/commit.html} -> '{@code commit.html}'</li>
	 * <li>'{@code /docs/**}' and '{@code /docs/cvs/commit} -> '{@code cvs/commit}'</li>
	 * </ul>
	 * <p><b>Note:</b> Assumes that {@link #matches} returns {@code true} for '{@code pattern}' and '{@code path}', but
	 * does <strong>not</strong> enforce this. As per the contract on {@link PathMatcher}, this
	 * method will trim leading/trailing separators. It will also remove duplicate separators in
	 * the returned path.
	 * @param path a path that matches this pattern
	 * @return the subset of the path that is matched by pattern or "" if none of it is matched by pattern elements
	 */
	public String extractPathWithinPattern(String path) {
		// assert this.matches(path)
		PathElement elem = head;
		int separatorCount = 0;
		boolean matchTheRest = false;

		// Find first path element that is pattern based
		while (elem != null) {
			if (elem instanceof SeparatorPathElement || elem instanceof CaptureTheRestPathElement ||
					elem instanceof WildcardTheRestPathElement) {
				separatorCount++;
				if (elem instanceof WildcardTheRestPathElement || elem instanceof CaptureTheRestPathElement) {
					matchTheRest = true;
				}
			}
			if (elem.getWildcardCount() != 0 || elem.getCaptureCount() != 0) {
				break;
			}
			elem = elem.next;
		}

		if (elem == null) {
			return "";  // there is no pattern mapped section
		}

		// Now separatorCount indicates how many sections of the path to skip
		char[] pathChars = path.toCharArray();
		int len = pathChars.length;
		int pos = 0;
		while (separatorCount > 0 && pos < len) {
			if (path.charAt(pos++) == separator) {
				separatorCount--;
			}
		}
		int end = len;
		// Trim trailing separators
		if (!matchTheRest) {
			while (end > 0 && path.charAt(end - 1) == separator) {
				end--;
			}
		}

		// Check if multiple separators embedded in the resulting path, if so trim them out.
		// Example: aaa////bbb//ccc/d -> aaa/bbb/ccc/d
		// The stringWithDuplicateSeparatorsRemoved is only computed if necessary
		int c = pos;
		StringBuilder stringWithDuplicateSeparatorsRemoved = null;
		while (c < end) {
			char ch = path.charAt(c);
			if (ch == separator) {
				if ((c + 1) < end && path.charAt(c + 1) == separator) {
					// multiple separators
					if (stringWithDuplicateSeparatorsRemoved == null) {
						// first time seen, need to capture all data up to this point
						stringWithDuplicateSeparatorsRemoved = new StringBuilder();
						stringWithDuplicateSeparatorsRemoved.append(path.substring(pos, c));
					}
					do {
						c++;
					} while ((c + 1) < end && path.charAt(c + 1) == separator);
				}
			}
			if (stringWithDuplicateSeparatorsRemoved != null) {
				stringWithDuplicateSeparatorsRemoved.append(ch);
			}
			c++;
		}

		if (stringWithDuplicateSeparatorsRemoved != null) {
			return stringWithDuplicateSeparatorsRemoved.toString();
		}
		return (pos == len ? "" : path.substring(pos, end));
	}


	/**
	 * Compare this pattern with a supplied pattern: return -1,0,+1 if this pattern
	 * is more specific, the same or less specific than the supplied pattern.
	 * The aim is to sort more specific patterns first.
	 */
	@Override
	public int compareTo(@Nullable PathPattern otherPattern) {
		// 1) null is sorted last
		if (otherPattern == null) {
			return -1;
		}

		// 2) catchall patterns are sorted last. If both catchall then the
		// length is considered
		if (isCatchAll()) {
			if (otherPattern.isCatchAll()) {
				int lenDifference = getNormalizedLength() - otherPattern.getNormalizedLength();
				if (lenDifference != 0) {
					return (lenDifference < 0) ? +1 : -1;
				}
			}
			else {
				return +1;
			}
		}
		else if (otherPattern.isCatchAll()) {
			return -1;
		}

		// 3) This will sort such that if they differ in terms of wildcards or
		// captured variable counts, the one with the most will be sorted last
		int score = getScore() - otherPattern.getScore();
		if (score != 0) {
			return (score < 0) ? -1 : +1;
		}

		// 4) longer is better
		int lenDifference = getNormalizedLength() - otherPattern.getNormalizedLength();
		return (lenDifference < 0) ? +1 : (lenDifference == 0 ? 0 : -1);
	}

	/**
	 * Combine this pattern with another. Currently does not produce a new PathPattern, just produces a new string.
	 */
	public PathPattern combine(PathPattern pattern2string) {
		// If one of them is empty the result is the other. If both empty the result is ""
		if (!StringUtils.hasLength(this.patternString)) {
			if (!StringUtils.hasLength(pattern2string.patternString)) {
				return parser.parse("");
			}
			else {
				return pattern2string;
			}
		}
		else if (!StringUtils.hasLength(pattern2string.patternString)) {
			return this;
		}

		// /* + /hotel => /hotel
		// /*.* + /*.html => /*.html
		// However:
		// /usr + /user => /usr/user 
		// /{foo} + /bar => /{foo}/bar
		if (!this.patternString.equals(pattern2string.patternString) && this.capturedVariableCount == 0 && 
				matches(PathContainer.parse(pattern2string.patternString, StandardCharsets.UTF_8))) {
			return pattern2string;
		}

		// /hotels/* + /booking => /hotels/booking
		// /hotels/* + booking => /hotels/booking
		if (this.endsWithSeparatorWildcard) {
			return parser.parse(concat(this.patternString.substring(0, this.patternString.length() - 2), pattern2string.patternString));
		}

		// /hotels + /booking => /hotels/booking
		// /hotels + booking => /hotels/booking
		int starDotPos1 = this.patternString.indexOf("*.");  // Are there any file prefix/suffix things to consider?
		if (this.capturedVariableCount != 0 || starDotPos1 == -1 || this.separator == '.') {
			return parser.parse(concat(this.patternString, pattern2string.patternString));
		}

		// /*.html + /hotel => /hotel.html
		// /*.html + /hotel.* => /hotel.html
		String firstExtension = this.patternString.substring(starDotPos1 + 1);  // looking for the first extension
		String p2string = pattern2string.patternString;
		int dotPos2 = p2string.indexOf('.');
		String file2 = (dotPos2 == -1 ? p2string : p2string.substring(0, dotPos2));
		String secondExtension = (dotPos2 == -1 ? "" : p2string.substring(dotPos2));
		boolean firstExtensionWild = (firstExtension.equals(".*") || firstExtension.equals(""));
		boolean secondExtensionWild = (secondExtension.equals(".*") || secondExtension.equals(""));
		if (!firstExtensionWild && !secondExtensionWild) {
			throw new IllegalArgumentException(
					"Cannot combine patterns: " + this.patternString + " and " + pattern2string);
		}
		return parser.parse(file2 + (firstExtensionWild ? secondExtension : firstExtension));
	}

	public boolean equals(Object other) {
		if (!(other instanceof PathPattern)) {
			return false;
		}
		PathPattern otherPattern = (PathPattern) other;
		return (this.patternString.equals(otherPattern.getPatternString()) &&
				this.separator == otherPattern.getSeparator() &&
				this.caseSensitive == otherPattern.caseSensitive);
	}

	public int hashCode() {
		return (this.patternString.hashCode() + this.separator) * 17 + (this.caseSensitive ? 1 : 0);
	}

	public String toString() {
		return this.patternString;
	}


	/**
	 * Represents the result of a successful path match. This holds the keys that matched, the
	 * values that were found for each key and, if any, the path parameters (matrix variables)
	 * attached to that path element.
	 * For example: "/{var}" against "/foo;a=b" will return a PathMathResult with 'foo=bar'
	 * for URI variables and 'a=b' as path parameters for 'foo'.
	 */
	public static class PathMatchResult {

		private static final PathMatchResult EMPTY =
				new PathMatchResult(Collections.emptyMap(), Collections.emptyMap());


		private final Map<String, String> uriVariables;

		private final Map<String, MultiValueMap<String, String>> matrixVariables;


		PathMatchResult(Map<String, String> uriVars,
				@Nullable Map<String, MultiValueMap<String, String>> matrixVars) {

			this.uriVariables = Collections.unmodifiableMap(uriVars);
			this.matrixVariables = matrixVars != null ?
					Collections.unmodifiableMap(matrixVars) : Collections.emptyMap();
		}


		public Map<String, String> getUriVariables() {
			return this.uriVariables;
		}

		public Map<String, MultiValueMap<String, String>> getMatrixVariables() {
			return this.matrixVariables;
		}

		@Override
		public String toString() {
			return "PathMatchResult[uriVariables=" + this.uriVariables + ", " +
					"matrixVariables=" + this.matrixVariables + "]";
		}
	}

	/**
	 * A holder for the result of a {@link PathPattern#getPathRemaining(String)} call. Holds
	 * information on the path left after the first part has successfully matched a pattern
	 * and any variables bound in that first part that matched.
	 */
	public static class PathRemainingMatchInfo {

		private final PathContainer pathRemaining;

		private final PathMatchResult pathMatchResult;

		PathRemainingMatchInfo(PathContainer pathRemaining) {
			this(pathRemaining, PathMatchResult.EMPTY);
		}

		PathRemainingMatchInfo(PathContainer pathRemaining, PathMatchResult pathMatchResult) {
			this.pathRemaining = pathRemaining;
			this.pathMatchResult = pathMatchResult;
		}

		/**
		 * Return the part of a path that was not matched by a pattern.
		 */
		public String getPathRemaining() {
			return this.pathRemaining.value();
		}

		/**
		 * Return variables that were bound in the part of the path that was successfully matched.
		 * Will be an empty map if no variables were bound
		 */
		public Map<String, String> getUriVariables() {
			return this.pathMatchResult.getUriVariables();
		}

		/**
		 * Return the path parameters for each bound variable.
		 */
		public Map<String, MultiValueMap<String, String>> getMatrixVariables() {
			return this.pathMatchResult.getMatrixVariables();
		}
	}

	int getScore() {
		return this.score;
	}

	boolean isCatchAll() {
		return this.catchAll;
	}

	/**
	 * The normalized length is trying to measure the 'active' part of the pattern. It is computed
	 * by assuming all capture variables have a normalized length of 1. Effectively this means changing
	 * your variable name lengths isn't going to change the length of the active part of the pattern.
	 * Useful when comparing two patterns.
	 */
	int getNormalizedLength() {
		return this.normalizedLength;
	}

	char getSeparator() {
		return this.separator;
	}

	int getCapturedVariableCount() {
		return this.capturedVariableCount;
	}

	String toChainString() {
		StringBuilder buf = new StringBuilder();
		PathElement pe = this.head;
		while (pe != null) {
			buf.append(pe.toString()).append(" ");
			pe = pe.next;
		}
		return buf.toString().trim();
	}

	/**
	 * @return string form of the pattern built from walking the path element chain
	 */
	String computePatternString() {
		StringBuilder buf = new StringBuilder();
		PathElement pe = this.head;
		while (pe != null) {
			buf.append(pe.getChars());
			pe = pe.next;
		}
		return buf.toString();
	}
	
	@Nullable
	PathElement getHeadSection() {
		return this.head;
	}

	/**
	 * Encapsulates context when attempting a match. Includes some fixed state like the
	 * candidate currently being considered for a match but also some accumulators for
	 * extracted variables.
	 */
	class MatchingContext {

		final PathContainer candidate;

		final List<Element> pathElements;

		final int pathLength;

		boolean isMatchStartMatching = false;

		@Nullable
		private Map<String, String> extractedUriVariables;

		@Nullable
		private Map<String, MultiValueMap<String, String>> extractedMatrixVariables;

		boolean extractingVariables;

		boolean determineRemainingPath = false;

		// if determineRemaining is true, this is set to the position in
		// the candidate where the pattern finished matching - i.e. it
		// points to the remaining path that wasn't consumed
		int remainingPathIndex;

		public MatchingContext(PathContainer pathContainer, boolean extractVariables) {
			candidate = pathContainer;
			pathElements = pathContainer.elements();
			pathLength = pathElements.size();
			this.extractingVariables = extractVariables;
		}

		public void setMatchAllowExtraPath() {
			determineRemainingPath = true;
		}

		public boolean isAllowOptionalTrailingSlash() {
			return allowOptionalTrailingSlash;
		}

		public void setMatchStartMatching(boolean b) {
			isMatchStartMatching = b;
		}

		public void set(String key, String value, MultiValueMap<String,String> parameters) {
			if (this.extractedUriVariables == null) {
				this.extractedUriVariables = new HashMap<>();
			}
			this.extractedUriVariables.put(key, value);

			if (!parameters.isEmpty()) {
				if (this.extractedMatrixVariables == null) {
					this.extractedMatrixVariables = new HashMap<>();
				}
				this.extractedMatrixVariables.put(key, CollectionUtils.unmodifiableMultiValueMap(parameters));
			}
		}

		public PathMatchResult getPathMatchResult() {
			if (this.extractedUriVariables == null) {
				return PathMatchResult.EMPTY;
			}
			else {
				return new PathMatchResult(this.extractedUriVariables, this.extractedMatrixVariables);
			}
		}

		/**
		 * @param pathIndex possible index of a separator
		 * @return true if element at specified index is a separator
		 */
		boolean isSeparator(int pathIndex) {
			return pathElements.get(pathIndex) instanceof Separator;
		}

		/**
		 * @param pathIndex path element index
		 * @return decoded value of the specified element
		 */
		String pathElementValue(int pathIndex) {
			Element element = (pathIndex < pathLength) ? pathElements.get(pathIndex) : null;
			if (element instanceof Segment) {
				return ((Segment)element).valueDecoded();
			}
			return "";
		}
	}

	/**
	 * Join two paths together including a separator if necessary.
	 * Extraneous separators are removed (if the first path
	 * ends with one and the second path starts with one).
	 * @param path1 first path
	 * @param path2 second path
	 * @return joined path that may include separator if necessary
	 */
	private String concat(String path1, String path2) {
		boolean path1EndsWithSeparator = (path1.charAt(path1.length() - 1) == this.separator);
		boolean path2StartsWithSeparator = (path2.charAt(0) == this.separator);
		if (path1EndsWithSeparator && path2StartsWithSeparator) {
			return path1 + path2.substring(1);
		}
		else if (path1EndsWithSeparator || path2StartsWithSeparator) {
			return path1 + path2;
		}
		else {
			return path1 + this.separator + path2;
		}
	}

	/**
	 * @param container a path container
	 * @return true if the container is not null and has more than zero elements
	 */
	private boolean hasLength(@Nullable PathContainer container) {
		return container != null && container.elements().size() > 0;
	}

}
