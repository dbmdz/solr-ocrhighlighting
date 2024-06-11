# Support for alternative forms

All OCR formats supported by this plugin have the possibility of encoding *alternative readings* for
a given word. These can either come from the OCR engine itself and consist of other high-confidence
readings for a given sequence of characters, or they could come from an manual or semi-automatic
OCR correction system.

!!! note Expressing alternatives in OCR files
    - For **hOCR**, use `<span class="alternatives"><ins class="alt">...</ins><del class="alt">...</del></span>` (see [hOCR specification](http://kba.github.io/hocr-spec/1.2/#segmentation))
    - For **ALTO**, use `<String …><ALTERNATIVE>...</ALTERNATIVE></String>` (see `AlternativeType` in the [ALTO schema](https://www.loc.gov/standards/alto/v4/alto-4-2.xsd))
    - For **MiniOCR**, delimit alternative forms with `⇿` (U+21FF) (see [MiniOCR documentation](./formats.md#miniocr))

In any case, these alternative readings can improve your user's search experience, by allowing us to
index *multiple forms for a given text position*. This enables users to find more matching passages
for a given query than if only a single form was indexed for every word. This is a form of
*index-time term expansion*, similar in concept to e.g. the [Synonym Graph Filter](https://lucene.apache.org/solr/guide/8_7/filter-descriptions.html#synonym-graph-filter)
that ships with Solr.

**To enable the indexing of alternative readings**, you have to make some modifications to your OCR field's
**index analysis chain**.

First, you need to enable alternative expansion in the `OcrCharFilterFactory` by setting the
`expandAlternatives` attribute to `true`:

```xml
<charFilter
   class="solrocr.OcrCharFilterFactory"
   expandAlternatives="true"
/>
```

Next, you need  to add a new `OcrAlternativesFilterFactory` token filter component to your analysis
chain. This component must to be placed **after the tokenizer**:

```xml
<fieldType name="text_ocr" class="solr.TextField">
  <!-- .... -->
  <tokenizer class="solr.StandardTokenizerFactory"/>
  <filter class="solrocr.OcrAlternativesFilterFactory"/>
  <!-- .... -->
</fieldType>
```

A full field definition for an OCR field with alternative expansion could look like this:

```xml hl_lines="3 4 5 6 7 9"
<fieldType name="text_ocr" class="solr.TextField">
  <analyzer type="index">
    <charFilter class="solrocr.ExternalUtf8ContentFilterFactory"/>
    <charFilter
      class="solrocr.OcrCharFilterFactory"
      expandAlternatives="true"
    />
    <tokenizer class="solr.StandardTokenizerFactory"/>
    <filter class="solrocr.OcrAlternativesFilterFactory"/<
    <filter class="solr.LowerCaseFilterFactory"/>
  </analyzer>
  <analyzer type="query">
    <tokenizer class="solr.StandardTokenizerFactory"/>
    <filter class="solr.LowerCaseFilterFactory"/>
  </analyzer>
</fieldType>
```

!!! note "Highlighting matches on alternative forms"
    During highlighting, you will only see the matching alternative form in the snippet if the match
    is on a single word, or if it is at the beginning or the end of a phrase match. This is because we cannot
    get to the offsets of matching terms inside of a phrase match through Lucene's highlighting machinery.

!!! caution "Unsupported tokenizers"
    The `OcrAlternativesFilterFactory` works with almost all tokenizers shipping with Solr, **except for
    the `ClassicTokenizer`.** This is because we use the `WORD JOINER` (U+2060) character to denote
    alternative forms in the character stream and the classic tokenizer splits tokens on this character
    (contrary to Unicode rules). This also means that if you use a custom tokenizer, you need to make
    sure that it does not split tokens on U+2060.

!!! caution "Non-alphabetic characters in alternatives"
    Some of Solr's built-in tokenizers split tokens on special characters like `-` that occur inside
    of words. When such characters occur within tokens that have alternatives, the alternatives are
    severed from the original token and the plugin will not index them. To avoid this, either use
    a tokenizer that doesn't split on these characters (like `WhitespaceTokenizerFactory`) or consider
    customizing your tokenizer of choice to not split on these characters when a token includes
    alternative readings. Note that this can lead to less precise results, e.g. when `alpha-numeric`
    is not split, only a query like `alphanumeric` or `alpha-numeric` will match (depending on the
    analysis chains), but not `alpha` or `numeric` alone or a `"alpha numeric"` phrase query.

!!! caution "Consider increasing the standard `maxTokenLength` of 255"
    When your OCR contains a large number of alternatives for tokens, or these alternatives can
    get quite long, consider increasing the maximum token length in your tokenizer's configuration.
    For most of Solr's tokenizers this can be done with the `maxTokenLength` parameter that defaults
    to 255. When the plugin encounters a case where this leads to truncated alternatives, it will
    print a warning to the Solr log. Consider increasing the value to 512 or 1024. This will come
    at the expense of an increase in memory usage during indexing, but will preserve as many of your
    alternative readings as possible.
