/*
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.shared.internal;

import static com.google.common.flogger.StackSize.MEDIUM;
import static java.lang.Math.min;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.escape.Escaper;
import com.google.common.flogger.GoogleLogger;
import com.google.common.net.PercentEscaper;
import com.google.common.primitives.Chars;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.template.soy.data.Dir;
import com.google.template.soy.data.LogStatement;
import com.google.template.soy.data.LoggingAdvisingAppendable;
import com.google.template.soy.data.LoggingFunctionInvocation;
import com.google.template.soy.data.SanitizedContent;
import com.google.template.soy.data.SanitizedContent.ContentKind;
import com.google.template.soy.data.SoyValue;
import com.google.template.soy.data.UnsafeSanitizedContentOrdainer;
import com.google.template.soy.data.restricted.BooleanData;
import com.google.template.soy.data.restricted.NullData;
import com.google.template.soy.data.restricted.NumberData;
import com.google.template.soy.shared.internal.TagWhitelist.OptionalSafeTag;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Java implementations of functions that escape, normalize, and filter untrusted strings to allow
 * them to be safely embedded in particular contexts. These correspond to the {@code soy.$$escape*},
 * {@code soy.$$normalize*}, and {@code soy.$$filter*} functions defined in "soyutils.js".
 */
public final class Sanitizers {

  /** Receives messages about unsafe values that were filtered out. */
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private Sanitizers() {
    // Not instantiable.
  }

  /** Converts the input to HTML by entity escaping. */
  public static String escapeHtml(SoyValue value) {
    value = normalizeNull(value);
    if (isSanitizedContentOfKind(value, SanitizedContent.ContentKind.HTML)) {
      return value.coerceToString();
    }
    return escapeHtml(value.coerceToString());
  }

  /** Converts plain text to HTML by entity escaping. */
  public static String escapeHtml(String value) {
    return EscapingConventions.EscapeHtml.INSTANCE.escape(value);
  }

  /**
   * Normalizes the input HTML while preserving "safe" tags and the known directionality.
   *
   * @return the normalized input, in the form of {@link SanitizedContent} of {@link
   *     ContentKind#HTML}
   */
  public static SanitizedContent cleanHtml(SoyValue value) {
    return cleanHtml(value, ImmutableSet.of());
  }

  /**
   * Normalizes the input HTML while preserving "safe" tags and the known directionality.
   *
   * @param optionalSafeTags to add to the basic whitelist of formatting safe tags
   * @return the normalized input, in the form of {@link SanitizedContent} of {@link
   *     ContentKind#HTML}
   */
  public static SanitizedContent cleanHtml(
      SoyValue value, Collection<? extends OptionalSafeTag> optionalSafeTags) {
    value = normalizeNull(value);
    Dir valueDir = null;
    if (value instanceof SanitizedContent) {
      SanitizedContent sanitizedContent = (SanitizedContent) value;
      if (sanitizedContent.getContentKind() == SanitizedContent.ContentKind.HTML) {
        return (SanitizedContent) value;
      }
      valueDir = sanitizedContent.getContentDirection();
    }
    return cleanHtml(value.coerceToString(), valueDir, optionalSafeTags);
  }

  /**
   * Normalizes the input HTML while preserving "safe" tags. The content directionality is unknown.
   *
   * @return the normalized input, in the form of {@link SanitizedContent} of {@link
   *     ContentKind#HTML}
   */
  public static SanitizedContent cleanHtml(String value) {
    return cleanHtml(value, ImmutableSet.of());
  }

  /**
   * Normalizes the input HTML while preserving "safe" tags. The content directionality is unknown.
   *
   * @param optionalSafeTags to add to the basic whitelist of formatting safe tags
   * @return the normalized input, in the form of {@link SanitizedContent} of {@link
   *     ContentKind#HTML}
   */
  public static SanitizedContent cleanHtml(
      String value, Collection<? extends OptionalSafeTag> optionalSafeTags) {
    return cleanHtml(value, null, optionalSafeTags);
  }

  /**
   * Normalizes the input HTML of a given directionality while preserving "safe" tags.
   *
   * @param optionalSafeTags to add to the basic whitelist of formatting safe tags
   * @return the normalized input, in the form of {@link SanitizedContent} of {@link
   *     ContentKind#HTML}
   */
  public static SanitizedContent cleanHtml(
      String value, Dir contentDir, Collection<? extends OptionalSafeTag> optionalSafeTags) {
    return UnsafeSanitizedContentOrdainer.ordainAsSafe(
        stripHtmlTags(value, TagWhitelist.FORMATTING.withOptionalSafeTags(optionalSafeTags), true),
        ContentKind.HTML,
        contentDir);
  }

  /** Streaming version of {@code |cleanHtml}. */
  public static LoggingAdvisingAppendable cleanHtmlStreaming(
      LoggingAdvisingAppendable delegate, Collection<? extends OptionalSafeTag> optionalSafeTags) {
    return new CleanHtmlAppendable(delegate, optionalSafeTags);
  }

  private static final class CleanHtmlAppendable extends AbstractStreamingHtmlEscaper {
    private final Collection<? extends OptionalSafeTag> optionalSafeTags;

    CleanHtmlAppendable(
        LoggingAdvisingAppendable delegate,
        Collection<? extends OptionalSafeTag> optionalSafeTags) {
      super(delegate, new StringBuilder());
      this.optionalSafeTags = optionalSafeTags;
    }

    @Override
    protected void notifyKindAndDirectionality(ContentKind kind, @Nullable Dir contentDir)
        throws IOException {
      if (isInHtml()) {
        activeAppendable = delegate;
        delegate.setKindAndDirectionality(kind, contentDir);
      }
    }

    @CanIgnoreReturnValue
    @Override
    public LoggingAdvisingAppendable enterLoggableElement(LogStatement statement) {
      if (isInHtml()) {
        delegate.enterLoggableElement(statement);
      } else {
        throw new AssertionError(
            "Logging statements should've already been removed as they're only allowed in HTML");
      }
      return this;
    }

    @CanIgnoreReturnValue
    @Override
    public LoggingAdvisingAppendable exitLoggableElement() {
      if (isInHtml()) {
        delegate.exitLoggableElement();
      }
      return this;
    }

    @CanIgnoreReturnValue
    @Override
    public LoggingAdvisingAppendable appendLoggingFunctionInvocation(
        LoggingFunctionInvocation funCall, ImmutableList<Function<String, String>> escapers)
        throws IOException {
      if (isInHtml()) {
        delegate.appendLoggingFunctionInvocation(funCall, escapers);
      } else {
        throw new AssertionError(
            "Logging statements should've already been removed as they're only allowed in HTML");
      }
      return this;
    }

    @Override
    public void flushBuffers(int depth) throws IOException {
      if (!isInHtml()) {
        StringBuilder buffer = (StringBuilder) activeAppendable;
        if (buffer.length() > 0) {
          cleanHtml(buffer.toString(), getSanitizedContentDirectionality(), optionalSafeTags)
              .render(delegate);
          buffer.setLength(0);
        }
      }
      super.flushBuffers(depth);
    }
  }

  /** Converts the input to HTML suitable for use inside {@code <textarea>} by entity escaping. */
  public static String escapeHtmlRcdata(SoyValue value) {
    value = normalizeNull(value);

    if (isSanitizedContentOfKind(value, SanitizedContent.ContentKind.HTML)) {
      // We can't allow tags in the output, because that would allow safe HTML containing
      // "<textarea>" to prematurely close the textarea.
      // Instead, we normalize which is semantics preserving in RCDATA.
      return normalizeHtml(value.coerceToString());
    }

    return escapeHtmlRcdata(value.coerceToString());
  }

  public static String escapeHtmlRcdata(String value) {
    return escapeHtml(value);
  }

  /** Streaming version of {@code |escapeHtmlRcData}. */
  public static LoggingAdvisingAppendable escapeHtmlRcdataStreaming(
      LoggingAdvisingAppendable delegate) {
    return new StreamingHtmlRcDataEscaper(delegate);
  }

  private static final class StreamingHtmlRcDataEscaper extends AbstractStreamingHtmlEscaper {
    private StreamingHtmlRcDataEscaper(LoggingAdvisingAppendable delegate) {
      super(delegate, EscapingConventions.EscapeHtml.INSTANCE.escape(delegate));
    }

    @Override
    protected void notifyKindAndDirectionality(ContentKind kind, @Nullable Dir directionality)
        throws IOException {
      if (isInHtml()) {
        activeAppendable = EscapingConventions.NormalizeHtml.INSTANCE.escape(delegate);
      }
      delegate.setKindAndDirectionality(kind, directionality);
    }

    @CanIgnoreReturnValue
    @Override
    public LoggingAdvisingAppendable appendLoggingFunctionInvocation(
        LoggingFunctionInvocation funCall, ImmutableList<Function<String, String>> escapers)
        throws IOException {
      activeAppendable.append(escapePlaceholder(funCall.placeholderValue(), escapers));
      return this;
    }

    @Override
    public LoggingAdvisingAppendable enterLoggableElement(LogStatement statement) {
      return this;
    }

    @Override
    public LoggingAdvisingAppendable exitLoggableElement() {
      return this;
    }
  }

  /** Normalizes HTML to HTML making sure quotes and other specials are entity encoded. */
  public static String normalizeHtml(SoyValue value) {
    value = normalizeNull(value);
    return normalizeHtml(value.coerceToString());
  }

  /** Normalizes HTML to HTML making sure quotes and other specials are entity encoded. */
  public static String normalizeHtml(String value) {
    return EscapingConventions.NormalizeHtml.INSTANCE.escape(value);
  }

  public static LoggingAdvisingAppendable normalizeHtmlStreaming(
      LoggingAdvisingAppendable appendable) {
    return StreamingEscaper.create(appendable, EscapingConventions.NormalizeHtml.INSTANCE);
  }

  /**
   * Normalizes HTML to HTML making sure quotes, spaces and other specials are entity encoded so
   * that the result can be safely embedded in a valueless attribute.
   */
  public static String normalizeHtmlNospace(SoyValue value) {
    value = normalizeNull(value);
    return normalizeHtmlNospace(value.coerceToString());
  }

  /**
   * Normalizes HTML to HTML making sure quotes, spaces and other specials are entity encoded so
   * that the result can be safely embedded in a valueless attribute.
   */
  public static String normalizeHtmlNospace(String value) {
    return EscapingConventions.NormalizeHtmlNospace.INSTANCE.escape(value);
  }

  /**
   * Converts the input to HTML by entity escaping, stripping tags in sanitized content so the
   * result can safely be embedded in an HTML attribute value.
   */
  public static String escapeHtmlAttribute(SoyValue value) {
    value = normalizeNull(value);
    if (isSanitizedContentOfKind(value, SanitizedContent.ContentKind.HTML)) {
      // |escapeHtmlAttribute should only be used on attribute values that cannot have tags.
      return stripHtmlTags(value.coerceToString(), null, true);
    }
    return escapeHtmlAttribute(value.coerceToString());
  }

  /**
   * Converts plain text to HTML by entity escaping so the result can safely be embedded in an HTML
   * attribute value.
   */
  public static String escapeHtmlAttribute(String value) {
    return EscapingConventions.EscapeHtml.INSTANCE.escape(value);
  }

  public static LoggingAdvisingAppendable escapeHtmlAttributeStreaming(
      LoggingAdvisingAppendable appendable) {
    return StreamingAttributeEscaper.create(appendable, EscapingConventions.EscapeHtml.INSTANCE);
  }

  /**
   * Escapes HTML special characters in an HTML attribute value containing HTML code, such as {@code
   * <iframe srcdoc>}.
   */
  public static String escapeHtmlHtmlAttribute(SoyValue value) {
    return escapeHtml(value);
  }

  /**
   * Escapes HTML special characters in an HTML attribute value containing HTML code, such as {@code
   * <iframe srcdoc>}.
   */
  public static String escapeHtmlHtmlAttribute(String value) {
    return escapeHtml(value);
  }

  /**
   * Converts plain text to HTML by entity escaping, stripping tags in sanitized content so the
   * result can safely be embedded in an unquoted HTML attribute value.
   */
  public static String escapeHtmlAttributeNospace(SoyValue value) {
    value = normalizeNull(value);
    if (isSanitizedContentOfKind(value, SanitizedContent.ContentKind.HTML)) {
      // |escapeHtmlAttributeNospace should only be used on attribute values that cannot have tags.
      return stripHtmlTags(value.coerceToString(), null, false);
    }
    return escapeHtmlAttributeNospace(value.coerceToString());
  }

  /**
   * Converts plain text to HTML by entity escaping so the result can safely be embedded in an
   * unquoted HTML attribute value.
   */
  public static String escapeHtmlAttributeNospace(String value) {
    return EscapingConventions.EscapeHtmlNospace.INSTANCE.escape(value);
  }

  public static LoggingAdvisingAppendable escapeHtmlAttributeNospaceStreaming(
      LoggingAdvisingAppendable appendable) {
    return StreamingAttributeEscaper.create(
        appendable, EscapingConventions.EscapeHtmlNospace.INSTANCE);
  }

  /** Filters decimal and floating-point numbers. */
  public static String filterNumber(SoyValue value) {
    return filterNumber(value.coerceToString());
  }

  /** Filters decimal and floating-point numbers. */
  public static String filterNumber(String value) {
    if (!value.matches("\\d*\\.?\\d+")) {
      return EscapingConventions.INNOCUOUS_OUTPUT;
    }
    return value;
  }

  /** Converts the input to the body of a JavaScript string by using {@code \n} style escapes. */
  public static String escapeJsString(SoyValue value) {
    value = normalizeNull(value);
    return escapeJsString(value.coerceToString());
  }

  /** Converts plain text to the body of a JavaScript string by using {@code \n} style escapes. */
  public static String escapeJsString(String value) {
    return EscapingConventions.EscapeJsString.INSTANCE.escape(value);
  }

  public static LoggingAdvisingAppendable escapeJsStringStreaming(
      LoggingAdvisingAppendable appendable) {
    return StreamingEscaper.create(appendable, EscapingConventions.EscapeJsString.INSTANCE);
  }

  /**
   * Converts the input to a JavaScript expression. The resulting expression can be a boolean,
   * number, string literal, or {@code null}.
   */
  public static String escapeJsValue(SoyValue value) {
    // We surround values with spaces so that they can't be interpolated into identifiers
    // by accident.  We could use parentheses but those might be interpreted as a function call.
    if (NullData.INSTANCE == value || value == null) {
      // The JS counterpart of this code in soyutils.js emits " null " for both null and the special
      // JS value undefined.
      return " null ";
    } else if (value instanceof NumberData) {
      // This will emit references to NaN and Infinity.  Client code should not redefine those
      // to store sensitive data.
      return " " + value.numberValue() + " ";
    } else if (value instanceof BooleanData) {
      return " " + value.booleanValue() + " ";
    } else if (isSanitizedContentOfKind(value, SanitizedContent.ContentKind.JS)) {
      // This value may not be embeddable if it contains the substring "</script".
      // TODO(msamuel): Fixup.  We need to be careful because mucking with '<' can
      // break code like
      //    while (i</foo/.exec(str).length)
      // and mucking with / can break
      //    return untrustedHTML.replace(/</g, '&lt;');
      return value.coerceToString();
    } else {
      return escapeJsValue(value.coerceToString());
    }
  }

  /** Converts plain text to a quoted javaScript string value. */
  public static String escapeJsValue(double value) {
    return " " + value + " ";
  }

  /** Converts plain text to a quoted javaScript string value. */
  public static String escapeJsValue(boolean value) {
    return " " + value + " ";
  }

  /** Converts plain text to a quoted javaScript string value. */
  public static String escapeJsValue(String value) {
    return "'" + escapeJsString(value) + "'";
  }

  /** Converts the input to the body of a JavaScript regular expression literal. */
  public static String escapeJsRegex(SoyValue value) {
    value = normalizeNull(value);
    return escapeJsRegex(value.coerceToString());
  }

  /** Converts plain text to the body of a JavaScript regular expression literal. */
  public static String escapeJsRegex(String value) {
    return EscapingConventions.EscapeJsRegex.INSTANCE.escape(value);
  }

  /** Converts the input to the body of a JavaScript regular expression literal. */
  public static LoggingAdvisingAppendable escapeJsRegexStreaming(
      LoggingAdvisingAppendable delegate) {
    return StreamingEscaper.create(delegate, EscapingConventions.EscapeJsRegex.INSTANCE);
  }

  /** Converts the input to the body of a CSS string literal. */
  public static String escapeCssString(SoyValue value) {
    value = normalizeNull(value);
    return escapeCssString(value.coerceToString());
  }

  /** Converts plain text to the body of a CSS string literal. */
  public static String escapeCssString(String value) {
    return EscapingConventions.EscapeCssString.INSTANCE.escape(value);
  }

  /** Converts the input to the body of a CSS string literal. */
  public static LoggingAdvisingAppendable escapeCssStringStreaming(
      LoggingAdvisingAppendable delegate) {
    return StreamingEscaper.create(delegate, EscapingConventions.EscapeCssString.INSTANCE);
  }

  /**
   * Makes sure that the input is a valid CSS identifier part, CLASS or ID part, quantity, or CSS
   * keyword part.
   */
  public static String filterCssValue(SoyValue value) {
    value = normalizeNull(value);
    if (isSanitizedContentOfKind(value, SanitizedContent.ContentKind.CSS)) {
      // We don't need to do this when the CSS is embedded in a
      // style attribute since then the HTML escaper kicks in.
      // TODO(msamuel): Maybe change the autoescaper to generate
      //   |filterCssValue:attrib
      // for style attributes and thread the parameter here so that
      // we can skip this check when its unnecessary.
      return embedCssIntoHtml(value.coerceToString());
    }
    return NullData.INSTANCE == value ? "" : filterCssValue(value.coerceToString());
  }

  /**
   * Makes sure that the input is a valid CSS identifier part, CLASS or ID part, quantity, or CSS
   * keyword part.
   */
  public static String filterCssValue(String value) {
    if (EscapingConventions.FilterCssValue.INSTANCE.getValueFilter().matcher(value).find()) {
      return value;
    }
    logger.atWarning().withStackTrace(MEDIUM).log("|filterCssValue received bad value '%s'", value);
    return EscapingConventions.FilterCssValue.INSTANCE.getInnocuousOutput();
  }

  /** Converts the input to a piece of a URI by percent encoding the value as UTF-8 bytes. */
  public static String escapeUri(SoyValue value) {
    value = normalizeNull(value);
    return escapeUri(value.coerceToString());
  }

  /** Converts plain text to a piece of a URI by percent encoding the string as UTF-8 bytes. */
  public static String escapeUri(String value) {
    return uriEscaper().escape(value);
  }

  /**
   * Converts a piece of URI content to a piece of URI content that can be safely embedded in an
   * HTML attribute by percent encoding.
   */
  public static String normalizeUri(SoyValue value) {
    value = normalizeNull(value);
    return normalizeUri(value.coerceToString());
  }

  /**
   * Converts a piece of URI content to a piece of URI content that can be safely embedded in an
   * HTML attribute by percent encoding.
   */
  public static String normalizeUri(String value) {
    return EscapingConventions.NormalizeUri.INSTANCE.escape(value);
  }

  /**
   * Converts a piece of URI content to a piece of URI content that can be safely embedded in an
   * HTML attribute by percent encoding.
   */
  public static LoggingAdvisingAppendable normalizeUriStreaming(LoggingAdvisingAppendable value) {
    return StreamingEscaper.create(value, EscapingConventions.NormalizeUri.INSTANCE);
  }

  /**
   * Makes sure that the given input doesn't specify a dangerous protocol and also {@link
   * #normalizeUri normalizes} it.
   */
  public static String filterNormalizeUri(SoyValue value) {
    value = normalizeNull(value);
    if (isSanitizedContentOfKind(value, SanitizedContent.ContentKind.URI)
        || isSanitizedContentOfKind(value, SanitizedContent.ContentKind.TRUSTED_RESOURCE_URI)) {
      return normalizeUri(value);
    }
    return filterNormalizeUri(value.coerceToString());
  }

  /**
   * Makes sure that the given input doesn't specify a dangerous protocol and also {@link
   * #normalizeUri normalizes} it.
   */
  public static String filterNormalizeUri(String value) {
    if (EscapingConventions.FilterNormalizeUri.INSTANCE.getValueFilter().matcher(value).find()) {
      return EscapingConventions.FilterNormalizeUri.INSTANCE.escape(value);
    }
    logger.atWarning().withStackTrace(MEDIUM).log(
        "|filterNormalizeUri received bad value '%s'", value);
    return EscapingConventions.FilterNormalizeUri.INSTANCE.getInnocuousOutput();
  }

  /**
   * Checks that a URI is safe to be an image source.
   *
   * <p>Does not return SanitizedContent as there isn't an appropriate type for this.
   */
  public static String filterNormalizeMediaUri(SoyValue value) {
    value = normalizeNull(value);
    if (isSanitizedContentOfKind(value, SanitizedContent.ContentKind.URI)
        || isSanitizedContentOfKind(value, SanitizedContent.ContentKind.TRUSTED_RESOURCE_URI)) {
      return normalizeUri(value);
    }
    return filterNormalizeMediaUri(value.coerceToString());
  }

  /**
   * Checks that a URI is safe to be an image source.
   *
   * <p>Does not return SanitizedContent as there isn't an appropriate type for this.
   */
  public static String filterNormalizeMediaUri(String value) {
    if (EscapingConventions.FilterNormalizeMediaUri.INSTANCE
        .getValueFilter()
        .matcher(value)
        .find()) {
      return EscapingConventions.FilterNormalizeMediaUri.INSTANCE.escape(value);
    }
    logger.atWarning().withStackTrace(MEDIUM).log(
        "|filterNormalizeMediaUri received bad value '%s'", value);
    return EscapingConventions.FilterNormalizeMediaUri.INSTANCE.getInnocuousOutput();
  }

  /**
   * Like {@link #filterNormalizeUri} but also escapes ';'. It is a special character in content of
   * {@code <meta http-equiv="Refresh">}.
   */
  public static String filterNormalizeRefreshUri(SoyValue value) {
    return filterNormalizeUri(value).replace(";", "%3B");
  }

  /** Like {@link #filterNormalizeUri} but also escapes ';'. */
  public static String filterNormalizeRefreshUri(String value) {
    return filterNormalizeUri(value).replace(";", "%3B");
  }

  /** Makes sure the given input is an instance of either trustedResourceUrl or trustedString. */
  public static String filterTrustedResourceUri(SoyValue value) {
    value = normalizeNull(value);
    if (isSanitizedContentOfKind(value, SanitizedContent.ContentKind.TRUSTED_RESOURCE_URI)) {
      return value.coerceToString();
    }
    return filterTrustedResourceUri(value.coerceToString());
  }

  /** For string inputs this function just returns the input string itself. */
  public static String filterTrustedResourceUri(String value) {
    logger.atWarning().withStackTrace(MEDIUM).log(
        "|filterTrustedResourceUri received bad value '%s'", value);
    return "about:invalid#" + EscapingConventions.INNOCUOUS_OUTPUT;
  }

  /** Filters out strings that cannot be a substring of a valid <script> tag. */
  public static String filterHtmlScriptPhrasingData(SoyValue value) {
    value = normalizeNull(value);
    // no content types are safe for this context
    return filterHtmlScriptPhrasingData(value.coerceToString());
  }

  /**
   * Filters out strings that cannot be a substring of a valid <script> tag.
   *
   * <p>In particular, {@code <!--} and {@code </script} or prefixes of those strings that occur at
   * the end of the value.
   */
  public static String filterHtmlScriptPhrasingData(String value) {
    // we need to ban sequences that look like
    // <!--
    // </script
    int start = 0;
    int indexOfLt;
    while ((indexOfLt = value.indexOf('<', start)) != -1) {
      start = indexOfLt;
      if (matchPrefixIgnoreCasePastEnd("</script", value, start)
          || matchPrefixIgnoreCasePastEnd("<!--", value, start)) {
        logger.atWarning().withStackTrace(MEDIUM).log(
            "|filterHtmlScriptPhrasingData received bad value '%s'. Cannot contain a script"
                + " tag, and html comment, or end with a prefix of either",
            value);
        return EscapingConventions.INNOCUOUS_OUTPUT;
      }
      start++;
    }
    return value;
  }

  private static boolean matchPrefixIgnoreCasePastEnd(String needle, String haystack, int offset) {
    int charsLeft = haystack.length() - offset;
    int charsToScan = min(needle.length(), charsLeft);
    for (int i = 0; i < charsToScan; i++) {
      char c1 = needle.charAt(i);
      char c2 = haystack.charAt(i + offset);
      if (c1 != c2 && Ascii.toLowerCase(c1) != Ascii.toLowerCase(c2)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Makes sure that the given input is a data URI corresponding to an image.
   *
   * <p>SanitizedContent kind does not apply -- the directive is also used to ensure no foreign
   * resources are loaded.
   */
  public static SanitizedContent filterImageDataUri(SoyValue value) {
    value = normalizeNull(value);
    return filterImageDataUri(value.coerceToString());
  }

  /** Makes sure that the given input is a data URI corresponding to an image. */
  public static SanitizedContent filterImageDataUri(String value) {
    if (EscapingConventions.FilterImageDataUri.INSTANCE.getValueFilter().matcher(value).find()) {
      // NOTE: No need to escape.
      return UnsafeSanitizedContentOrdainer.ordainAsSafe(value, ContentKind.URI);
    }
    logger.atWarning().withStackTrace(MEDIUM).log(
        "|filterImageDataUri received bad value '%s'", value);
    return UnsafeSanitizedContentOrdainer.ordainAsSafe(
        EscapingConventions.FilterImageDataUri.INSTANCE.getInnocuousOutput(),
        SanitizedContent.ContentKind.URI);
  }

  /** Makes sure that the given input is a sip URI. */
  public static SanitizedContent filterSipUri(SoyValue value) {
    value = normalizeNull(value);
    return filterSipUri(value.coerceToString());
  }

  /** Makes sure that the given input is a sip URI. */
  public static SanitizedContent filterSipUri(String value) {
    if (EscapingConventions.FilterSipUri.INSTANCE.getValueFilter().matcher(value).find()) {
      // NOTE: No need to escape. Escaping for other contexts (e.g. HTML) happen after this.
      return UnsafeSanitizedContentOrdainer.ordainAsSafe(value, ContentKind.URI);
    }
    logger.atWarning().withStackTrace(MEDIUM).log("|filterSipUri received bad value '%s'", value);
    return UnsafeSanitizedContentOrdainer.ordainAsSafe(
        EscapingConventions.FilterSipUri.INSTANCE.getInnocuousOutput(),
        SanitizedContent.ContentKind.URI);
  }

  /** Makes sure that the given input is a sms URI. */
  public static SanitizedContent filterSmsUri(SoyValue value) {
    value = normalizeNull(value);
    return filterSmsUri(value.coerceToString());
  }

  /** Makes sure that the given input is a sms URI. */
  public static SanitizedContent filterSmsUri(String value) {
    if (EscapingConventions.FilterSmsUri.INSTANCE.getValueFilter().matcher(value).find()) {
      // NOTE: No need to escape. Escaping for other contexts (e.g. HTML) happen after this.
      return UnsafeSanitizedContentOrdainer.ordainAsSafe(value, ContentKind.URI);
    }
    logger.atWarning().withStackTrace(MEDIUM).log("|filterSmsUri received bad value '%s'", value);
    return UnsafeSanitizedContentOrdainer.ordainAsSafe(
        EscapingConventions.FilterSmsUri.INSTANCE.getInnocuousOutput(),
        SanitizedContent.ContentKind.URI);
  }

  /** Makes sure that the given input is a tel URI. */
  public static SanitizedContent filterTelUri(SoyValue value) {
    value = normalizeNull(value);
    return filterTelUri(value.coerceToString());
  }

  /** Makes sure that the given input is a tel URI. */
  public static SanitizedContent filterTelUri(String value) {
    if (EscapingConventions.FilterTelUri.INSTANCE.getValueFilter().matcher(value).find()) {
      // NOTE: No need to escape. Escaping for other contexts (e.g. HTML) happen after this.
      return UnsafeSanitizedContentOrdainer.ordainAsSafe(value, ContentKind.URI);
    }
    logger.atWarning().withStackTrace(MEDIUM).log("|filterTelUri received bad value '%s'", value);
    return UnsafeSanitizedContentOrdainer.ordainAsSafe(
        EscapingConventions.FilterTelUri.INSTANCE.getInnocuousOutput(),
        SanitizedContent.ContentKind.URI);
  }

  /** Legacy URI filtering from Soy. */
  public static SanitizedContent filterLegacyUriBehavior(SoyValue value) {
    value = normalizeNull(value);
    return filterLegacyUriBehavior(value.coerceToString());
  }

  /** Makes sure that the given input is a tel URI. */
  public static SanitizedContent filterLegacyUriBehavior(String value) {
    if (EscapingConventions.FilterLegacyUriBehavior.INSTANCE
        .getValueFilter()
        .matcher(value)
        .find()) {
      // NOTE: No need to escape. Escaping for other contexts (e.g. HTML) happen after this.
      return UnsafeSanitizedContentOrdainer.ordainAsSafe(value, ContentKind.URI);
    }
    logger.atWarning().withStackTrace(MEDIUM).log(
        "|filterLegacyUriBehavior received bad value '%s'", value);
    return UnsafeSanitizedContentOrdainer.ordainAsSafe(
        EscapingConventions.FilterLegacyUriBehavior.INSTANCE.getInnocuousOutput(),
        SanitizedContent.ContentKind.URI);
  }

  /**
   * Checks that the input is a valid HTML attribute name with normal keyword or textual content or
   * known safe attribute content.
   */
  public static String filterHtmlAttributes(SoyValue value) {
    String str = normalizeNull(value).coerceToString();
    if (isSanitizedContentOfKind(value, SanitizedContent.ContentKind.ATTRIBUTES)) {
      return str;
    }
    return filterHtmlAttributes(str);
  }

  /**
   * Checks that the input is a valid HTML attribute name with normal keyword or textual content.
   */
  public static String filterHtmlAttributes(String value) {
    if (EscapingConventions.FilterHtmlAttributes.INSTANCE.getValueFilter().matcher(value).find()) {
      return value;
    }
    logger.atWarning().withStackTrace(MEDIUM).log(
        "|filterHtmlAttributes received bad value '%s'", value);
    return EscapingConventions.FilterHtmlAttributes.INSTANCE.getInnocuousOutput();
  }

  public static LoggingAdvisingAppendable filterHtmlAttributesStreaming(
      LoggingAdvisingAppendable appendable) {
    return new FilterHtmlAttributesAppendable(appendable);
  }

  private static final class FilterHtmlAttributesAppendable extends LoggingAdvisingAppendable {
    private final LoggingAdvisingAppendable delegate;
    private Appendable activeAppendable;

    FilterHtmlAttributesAppendable(LoggingAdvisingAppendable delegate) {
      this.delegate = delegate;
    }

    private Appendable getActiveAppendable() {
      if (activeAppendable == null) {
        activeAppendable = new StringBuilder();
      }
      return activeAppendable;
    }

    @Override
    protected void notifyKindAndDirectionality(ContentKind kind, @Nullable Dir dir)
        throws IOException {
      if (kind == ContentKind.ATTRIBUTES) {
        activeAppendable = delegate;
      }
      delegate.setKindAndDirectionality(kind, dir);
    }

    @CanIgnoreReturnValue
    @Override
    public LoggingAdvisingAppendable append(CharSequence csq) throws IOException {
      getActiveAppendable().append(csq);
      return this;
    }

    @CanIgnoreReturnValue
    @Override
    public LoggingAdvisingAppendable append(CharSequence csq, int start, int end)
        throws IOException {
      getActiveAppendable().append(csq, start, end);
      return this;
    }

    @CanIgnoreReturnValue
    @Override
    public LoggingAdvisingAppendable append(char c) throws IOException {
      getActiveAppendable().append(c);
      return this;
    }

    @CanIgnoreReturnValue
    @Override
    public LoggingAdvisingAppendable enterLoggableElement(LogStatement statement) {
      logger.atWarning().withStackTrace(MEDIUM).log(
          "Visual element logging behavior is undefined when used with the |filterHtmlAttributes "
              + "directive. This logging call has been dropped: %s",
          statement);
      return this;
    }

    @Override
    public LoggingAdvisingAppendable exitLoggableElement() {
      return this;
    }

    @CanIgnoreReturnValue
    @Override
    public LoggingAdvisingAppendable appendLoggingFunctionInvocation(
        LoggingFunctionInvocation funCall, ImmutableList<Function<String, String>> escapers)
        throws IOException {
      if (getSanitizedContentKind() == ContentKind.ATTRIBUTES) {
        delegate.appendLoggingFunctionInvocation(funCall, escapers);
      } else {
        String placeholder = escapePlaceholder(funCall.placeholderValue(), escapers);
        getActiveAppendable().append(placeholder);
      }
      return this;
    }

    @Override
    public boolean softLimitReached() {
      return delegate.softLimitReached();
    }

    @Override
    public void flushBuffers(int depth) throws IOException {
      if (getSanitizedContentKind() != ContentKind.ATTRIBUTES) {
        delegate.append(filterHtmlAttributes(getActiveAppendable().toString()));
      }
      if (depth > 0) {
        delegate.flushBuffers(depth - 1);
      }
    }
  }

  /** Checks that the input is part of the name of an innocuous element. */
  public static String filterHtmlElementName(SoyValue value) {
    value = normalizeNull(value);
    return filterHtmlElementName(value.coerceToString());
  }

  /** Checks that the input is part of the name of an innocuous element. */
  public static String filterHtmlElementName(String value) {
    if (EscapingConventions.FilterHtmlElementName.INSTANCE.getValueFilter().matcher(value).find()) {
      return value;
    }
    logger.atWarning().withStackTrace(MEDIUM).log(
        "|filterHtmlElementName received bad value '%s'", value);
    return EscapingConventions.FilterHtmlElementName.INSTANCE.getInnocuousOutput();
  }

  /** Filters bad csp values. */
  public static String filterCspNonceValue(SoyValue value) {
    value = normalizeNull(value);
    return filterCspNonceValue(value.coerceToString());
  }

  public static String filterCspNonceValue(String value) {
    if (EscapingConventions.FilterCspNonceValue.INSTANCE.getValueFilter().matcher(value).find()) {
      return value;
    }
    logger.atWarning().withStackTrace(MEDIUM).log(
        "|filterCspNonceValue received bad value '%s'", value);
    return EscapingConventions.FilterCspNonceValue.INSTANCE.getInnocuousOutput();
  }

  public static String whitespaceHtmlAttributes(SoyValue value) {
    return whitespaceHtmlAttributes(normalizeNull(value).coerceToString());
  }

  public static String whitespaceHtmlAttributes(String value) {
    return ((!value.isEmpty() && !value.startsWith(" ")) ? " " : "") + value;
  }

  public static LoggingAdvisingAppendable whitespaceHtmlAttributesStreaming(
      LoggingAdvisingAppendable appendable) {
    return new WhitespaceHtmlAttributesAppendable(appendable);
  }

  private static final class WhitespaceHtmlAttributesAppendable extends LoggingAdvisingAppendable {
    private final LoggingAdvisingAppendable delegate;
    private Appendable activeAppendable;
    private boolean first;

    WhitespaceHtmlAttributesAppendable(LoggingAdvisingAppendable delegate) {
      this.delegate = delegate;
      this.first = true;
    }

    private Appendable getActiveAppendable() {
      if (activeAppendable == null) {
        Preconditions.checkState(first);
        activeAppendable = new StringBuilder();
      }
      return activeAppendable;
    }

    @Override
    protected void notifyKindAndDirectionality(ContentKind kind, @Nullable Dir dir)
        throws IOException {
      if (kind == ContentKind.ATTRIBUTES) {
        activeAppendable = delegate;
      }
      delegate.setKindAndDirectionality(kind, dir);
    }

    @CanIgnoreReturnValue
    @Override
    public LoggingAdvisingAppendable append(CharSequence csq) throws IOException {
      if (first && csq.length() > 0 && getSanitizedContentKind() == ContentKind.ATTRIBUTES) {
        if (csq.charAt(0) != ' ') {
          getActiveAppendable().append(" ");
        }
        first = false;
      }
      getActiveAppendable().append(csq);
      return this;
    }

    @CanIgnoreReturnValue
    @Override
    public LoggingAdvisingAppendable append(CharSequence csq, int start, int end)
        throws IOException {
      if (first && csq.length() > 0 && getSanitizedContentKind() == ContentKind.ATTRIBUTES) {
        if (csq.charAt(0) != ' ') {
          getActiveAppendable().append(" ");
        }
        first = false;
      }
      getActiveAppendable().append(csq, start, end);
      return this;
    }

    @CanIgnoreReturnValue
    @Override
    public LoggingAdvisingAppendable append(char c) throws IOException {
      if (first && c != ' ' && getSanitizedContentKind() == ContentKind.ATTRIBUTES) {
        getActiveAppendable().append(" ");
        first = false;
      }
      getActiveAppendable().append(c);
      return this;
    }

    @CanIgnoreReturnValue
    @Override
    public LoggingAdvisingAppendable enterLoggableElement(LogStatement statement) {
      logger.atWarning().withStackTrace(MEDIUM).log(
          "Visual element logging behavior is undefined when used with the"
              + " |whitespaceHtmlAttributes directive. This logging call has been dropped: %s",
          statement);
      return this;
    }

    @Override
    public LoggingAdvisingAppendable exitLoggableElement() {
      return this;
    }

    @CanIgnoreReturnValue
    @Override
    public LoggingAdvisingAppendable appendLoggingFunctionInvocation(
        LoggingFunctionInvocation funCall, ImmutableList<Function<String, String>> escapers)
        throws IOException {
      if (getSanitizedContentKind() == ContentKind.ATTRIBUTES) {
        delegate.appendLoggingFunctionInvocation(funCall, escapers);
      } else {
        String placeholder = escapePlaceholder(funCall.placeholderValue(), escapers);
        getActiveAppendable().append(placeholder);
      }
      return this;
    }

    @Override
    public boolean softLimitReached() {
      return delegate.softLimitReached();
    }

    @Override
    public void flushBuffers(int depth) throws IOException {
      if (getSanitizedContentKind() != ContentKind.ATTRIBUTES) {
        delegate.append(whitespaceHtmlAttributes(getActiveAppendable().toString()));
      }
      if (depth > 0) {
        delegate.flushBuffers(depth - 1);
      }
    }
  }

  /** True iff the given value is sanitized content of the given kind. */
  private static boolean isSanitizedContentOfKind(
      SoyValue value, SanitizedContent.ContentKind kind) {
    return value instanceof SanitizedContent && kind == ((SanitizedContent) value).getContentKind();
  }

  /**
   * Given a snippet of HTML, returns a snippet that has the same text content but only whitelisted
   * tags.
   *
   * @param safeTags the tags that are allowed in the output. A {@code null} white-list is the same
   *     as the empty white-list. If {@code null} or empty, then the output can be embedded in an
   *     attribute value. If the output is to be embedded in an attribute, {@code safeTags} should
   *     be {@code null}.
   * @param rawSpacesAllowed true if spaces are allowed in the output unescaped as is the case when
   *     the output is embedded in a regular text node, or in a quoted attribute.
   */
  public static String stripHtmlTags(
      String value, TagWhitelist safeTags, boolean rawSpacesAllowed) {
    EscapingConventions.CrossLanguageStringXform normalizer =
        rawSpacesAllowed
            ? EscapingConventions.NormalizeHtml.INSTANCE
            : EscapingConventions.NormalizeHtmlNospace.INSTANCE;

    Matcher matcher = EscapingConventions.HTML_TAG_CONTENT.matcher(value);
    if (!matcher.find()) {
      // Normalize so that the output can be embedded in an HTML attribute.
      return normalizer.escape(value);
    }

    StringBuilder out = new StringBuilder(value.length() - matcher.end() + matcher.start());
    Appendable normalizedOut = normalizer.escape(out);
    // We do some very simple tag balancing by dropping any close tags for unopened tags and at the
    // end emitting close tags for any still open tags.
    // This is sufficient (in HTML) to prevent embedded content with safe tags from breaking layout
    // when, for example, stripHtmlTags("</table>") is embedded in a page that uses tables for
    // formatting.
    List<String> openTags = null;
    int openListTagCount = 0;
    try {
      int pos = 0; // Such that value[:pos] has been sanitized onto out.
      do {
        int start = matcher.start();

        if (pos < start) {
          normalizedOut.append(value, pos, start);

          // More aggressively normalize ampersands at the end of a chunk so that
          //   "&<b>amp;</b>" -> "&amp;amp;" instead of "&amp;".
          if (value.charAt(start - 1) == '&') {
            out.append("amp;");
          }
        }

        if (safeTags != null) {
          String tagName = matcher.group(1);
          if (tagName != null) {
            // Use locale so that <I> works when the default locale is Turkish
            tagName = Ascii.toLowerCase(tagName);
            if (safeTags.isSafeTag(tagName)) {
              boolean isClose = value.charAt(start + 1) == '/';
              if (isClose) {
                if (openTags != null) {
                  int lastIdx = openTags.lastIndexOf(tagName);
                  if (lastIdx >= 0) {
                    // Close contained tags as well.
                    // If we didn't, then we would convert "<ul><li></ul>" to "<ul><li></ul></li>"
                    // which could lead to broken layout for embedding HTML that uses lists for
                    // formatting.
                    // This leads to observably different behavior for adoption-agency dependent
                    // tag combinations like "<b><i>Foo</b> Bar</b>" but fails safe.
                    // http://www.whatwg.org/specs/web-apps/current-work/multipage/the-end.html#misnested-tags:-b-i-/b-/i
                    List<String> tagsToClose = openTags.subList(lastIdx, openTags.size());
                    for (String tagToClose : tagsToClose) {
                      if (isListTag(tagToClose)) {
                        openListTagCount--;
                      }
                    }
                    closeTags(tagsToClose, out);
                  }
                }
              } else {
                // Only allow whitelisted <li> through if it is nested in a parent <ol> or <ul>.
                if (openListTagCount > 0 || !"li".equals(tagName)) {
                  if (isListTag(tagName)) {
                    openListTagCount++;
                  }

                  // Emit beginning of the opening tag and tag name on the un-normalized channel.
                  out.append('<').append(tagName);

                  // Most attributes are dropped, but the dir attribute is preserved if it exists.
                  // The attribute matching could be made more generic if more attributes need to be
                  // whitelisted in the future. There are also probably other utilities in common to
                  // do such parsing of HTML, but this seemed simple enough and keeps with the
                  // current spirit of this function of doing custom parsing.
                  Matcher attributeMatcher = HTML_ATTRIBUTE_PATTERN.matcher(matcher.group());
                  while (attributeMatcher.find()) {
                    String attributeName = attributeMatcher.group(1);
                    if (!Strings.isNullOrEmpty(attributeName)
                        && Ascii.equalsIgnoreCase(attributeName, "dir")) {
                      String dir = attributeMatcher.group(2);
                      if (!Strings.isNullOrEmpty(dir)) {
                        // Strip quotes if the attribute value was quoted.
                        if (dir.charAt(0) == '\'' || dir.charAt(0) == '"') {
                          dir = dir.substring(1, dir.length() - 1);
                        }
                        dir = Ascii.toLowerCase(dir);
                        if ("ltr".equals(dir) || "rtl".equals(dir) || "auto".equals(dir)) {
                          out.append(" dir=\"").append(dir).append("\"");
                        }
                      }
                      break;
                    }
                  }

                  // Emit the end of the opening tag
                  out.append('>');

                  // Keep track of tags that need closing.
                  if (!HTML5_VOID_ELEMENTS.contains(tagName)) {
                    if (openTags == null) {
                      openTags = Lists.newArrayList();
                    }
                    openTags.add(tagName);
                  }
                }
              }
            }
          }
        }
        pos = matcher.end();
      } while (matcher.find());
      normalizedOut.append(value, pos, value.length());
      // Emit close tags, so that safeTags("<table>") can't break the layout of embedding HTML that
      // uses tables for layout.
      if (openTags != null) {
        closeTags(openTags, out);
      }
    } catch (IOException ex) {
      // Writing to a StringBuilder should not throw.
      throw new AssertionError(ex);
    }
    return out.toString();
  }

  private static void closeTags(List<String> openTags, StringBuilder out) {
    for (int i = openTags.size(); --i >= 0; ) {
      out.append("</").append(openTags.get(i)).append('>');
    }
    openTags.clear();
  }

  private static boolean isListTag(String tagName) {
    return "ol".equals(tagName) || "ul".equals(tagName);
  }

  /** From http://www.w3.org/TR/html-markup/syntax.html#syntax-elements */
  public static final ImmutableSet<String> HTML5_VOID_ELEMENTS =
      ImmutableSet.of(
          "area", "base", "br", "col", "command", "embed", "hr", "img", "input", "keygen", "link",
          "meta", "param", "source", "track", "wbr");

  /**
   * Pattern for matching attribute name and value, where value is single-quoted or double-quoted.
   */
  public static final Pattern HTML_ATTRIBUTE_PATTERN;

  static {
    String attributeName = "[a-zA-Z][a-zA-Z0-9:\\-]*";
    String space = "[\t\n\r ]";

    String doubleQuotedValue = "\"[^\"]*\"";
    String singleQuotedValue = "'[^']*'";
    String attributeValue = Joiner.on('|').join(doubleQuotedValue, singleQuotedValue);

    HTML_ATTRIBUTE_PATTERN =
        Pattern.compile(
            String.format(
                "(%s)%s*=%s*(%s)",
                attributeName, // Group 1: Attribute name.
                space,
                space,
                attributeValue // Group 2: Optionally-quoted attributed value.
                ));
  }

  /**
   * Returns a {@link Escaper} instance that escapes Java characters so they can be safely included
   * in URIs. For details on escaping URIs, see section 2.4 of <a
   * href="http://www.ietf.org/rfc/rfc2396.txt">RFC 2396</a>.
   *
   * <p>When encoding a String, the following rules apply:
   *
   * <ul>
   *   <li>The alphanumeric characters "a" through "z", "A" through "Z" and "0" through "9" remain
   *       the same.
   *   <li>The special characters ".", "-", "*", and "_" remain the same.
   *   <li>If {@code plusForSpace} was specified, the space character " " is converted into a plus
   *       sign "+". Otherwise it is converted into "%20".
   *   <li>All other characters are converted into one or more bytes using UTF-8 encoding and each
   *       byte is then represented by the 3-character string "%XY", where "XY" is the two-digit,
   *       uppercase, hexadecimal representation of the byte value.
   * </ul>
   *
   * <p><b>Note</b>: Unlike other escapers, URI escapers produce uppercase hexadecimal sequences.
   * From <a href="http://www.ietf.org/rfc/rfc3986.txt">RFC 3986</a>:<br>
   * <i>"URI producers and normalizers should use uppercase hexadecimal digits for all
   * percent-encodings."</i>
   *
   * @see #uriEscaper()
   */
  private static Escaper uriEscaper() {
    return URI_ESCAPER_NO_PLUS;
  }

  /**
   * A string of safe characters that mimics the behavior of {@link java.net.URLEncoder}.
   *
   * <p>TODO: Fix escapers to be compliant with RFC 3986
   */
  private static final String SAFECHARS_URLENCODER = "-_.*";

  private static final Escaper URI_ESCAPER_NO_PLUS =
      new PercentEscaper(SAFECHARS_URLENCODER, false);

  /**
   * Make sure that tag boundaries are not broken by Safe CSS when embedded in a {@code <style>}
   * element.
   */
  @VisibleForTesting
  static String embedCssIntoHtml(String css) {
    // `</style` can close a containing style element in HTML.
    // `]]>` can similarly close a CDATA element in XHTML.

    // Scan for "</" and "]]>" and escape enough to remove the token seen by
    // the HTML parser.

    // For well-formed CSS, these string might validly appear in a few contexts:
    // 1. comments
    // 2. string bodies
    // 3. url(...) bodies.

    // Appending \ should be semantics preserving in comments and string bodies.
    // This may not be semantics preserving in url content.
    // The substring "]>" can validly appear in a selector
    //   a[href]>b
    // but the substring "]]>" cannot.

    // This should not affect how a CSS parser recovers from syntax errors.
    int indexOfEndTag = css.indexOf("</");
    int indexOfEndCData = css.indexOf("]]>");
    if (indexOfEndTag != -1) {
      if (indexOfEndCData != -1) {
        return embedCssIntoHtmlSlow(
            css,
            min(indexOfEndTag, indexOfEndCData),
            /* searchForEndCData= */ true,
            /* searchForEndTag= */ true);
      }
      return embedCssIntoHtmlSlow(
          css, indexOfEndTag, /* searchForEndCData= */ false, /* searchForEndTag= */ true);
    } else if (indexOfEndCData != -1) {
      return embedCssIntoHtmlSlow(
          css, indexOfEndCData, /* searchForEndCData= */ true, /* searchForEndTag= */ false);
    }
    return css;
  }

  /**
   * Called when we know we need to make a replacement.
   *
   * <p>At least one of {@code searchForEndCData} or {@code searchForEndTag} will be {@code true}.
   *
   * @param css The css string to modify
   * @param nextReplacement The location of the first replacement
   * @param searchForEndCData Whether there are any sequences of {@code ]]>}
   * @param searchForEndTag Whether there are any sequences of {@code </}
   * @return The modified string.
   */
  private static String embedCssIntoHtmlSlow(
      String css, int nextReplacement, boolean searchForEndCData, boolean searchForEndTag) {
    // use an array instead of a stringbuilder so we can take advantage of the bulk copying
    // routine (String.getChars).  For some reason StringBuilder doesn't do this.
    char[] buf = new char[css.length() + 16];
    int endOfPreviousReplacement = 0;
    int bufIndex = 0;
    do {
      int charsToCopy = nextReplacement - endOfPreviousReplacement;
      buf = Chars.ensureCapacity(buf, bufIndex + charsToCopy + 4, 16);
      css.getChars(endOfPreviousReplacement, nextReplacement, buf, bufIndex);
      bufIndex += charsToCopy;
      char c = css.charAt(nextReplacement);
      if (c == ']') {
        buf[bufIndex++] = ']';
        buf[bufIndex++] = ']';
        buf[bufIndex++] = '\\';
        buf[bufIndex++] = '>';
        endOfPreviousReplacement = nextReplacement + 3;
      } else if (c == '<') {
        buf[bufIndex++] = '<';
        buf[bufIndex++] = '\\';
        buf[bufIndex++] = '/';
        endOfPreviousReplacement = nextReplacement + 2;
      } else {
        throw new AssertionError();
      }
      nextReplacement = -1;
      if (searchForEndTag) {
        int indexOfEndTag = css.indexOf("</", endOfPreviousReplacement);
        if (indexOfEndTag == -1) {
          searchForEndTag = false;
        } else {
          nextReplacement = indexOfEndTag;
        }
      }
      if (searchForEndCData) {
        int indexOfEndCData = css.indexOf("]]>", endOfPreviousReplacement);
        if (indexOfEndCData == -1) {
          searchForEndCData = false;
        } else {
          nextReplacement =
              nextReplacement == -1 ? indexOfEndCData : min(nextReplacement, indexOfEndCData);
        }
      }
    } while (nextReplacement != -1);
    // copy tail
    int charsToCopy = css.length() - endOfPreviousReplacement;
    buf = Chars.ensureCapacity(buf, bufIndex + charsToCopy, 16);
    css.getChars(endOfPreviousReplacement, css.length(), buf, bufIndex);
    bufIndex += charsToCopy;
    return new String(buf, 0, bufIndex);
  }

  /** A helper to normalize null->NullData. This allows tofu and jbcsrc compatibility. */
  private static SoyValue normalizeNull(@Nullable SoyValue v) {
    return v == null ? NullData.INSTANCE : v;
  }
}
