# Parts that need to be kept in sync with upstream

Since we're re-using and slightly modifying a multitude of internal Lucene APIs (all from the `lucene.search.uhighlight`)
namespace, we should go through all those APIs after every new release of Lucene/Solr and update our code to use the 
new APIs/implementations.

- [`PassageFormatter`](https://github.com/apache/lucene-solr/commits/master/lucene/highlighter/src/java/org/apache/lucene/search/uhighlight/PassageFormatter.java)
- [`UnifiedHighlighter`](https://github.com/apache/lucene-solr/commits/master/lucene/highlighter/src/java/org/apache/lucene/search/uhighlight/UnifiedHighlighter.java)
  * `highlightFieldsAsObjects`
  * `loadFieldValues`
  * `getPhraseHelper`
  * `getOffsetStrategy`
  * `copyAndSortFieldsWithMaxPassages`
  * `copyAndSortDocIds`
  * `asDocIdSetIterator`
  * `TermVectorReusingLeafReader`
- [`FieldHighlighter`](https://github.com/apache/lucene-solr/commits/master/lucene/highlighter/src/java/org/apache/lucene/search/uhighlight/FieldHighlighter.java)
- [`TermVectorFilteredLeafReader`](https://github.com/apache/lucene-solr/commits/master/lucene/highlighter/src/java/org/apache/lucene/search/uhighlight/TermVectorFilteredLeafReader.java)
- [`PhraseHelper`](https://github.com/apache/lucene-solr/commits/master/lucene/highlighter/src/java/org/apache/lucene/search/uhighlight/PhraseHelper.java)
- [`OffsetsEnum`](https://github.com/apache/lucene-solr/commits/master/lucene/highlighter/src/java/org/apache/lucene/search/uhighlight/OffsetsEnum.java)
- [`FieldOffsetStrategy`](https://github.com/apache/lucene-solr/commits/master/lucene/highlighter/src/java/org/apache/lucene/search/uhighlight/FieldOffsetStrategy.java)
  * `NoOpOffsetStrategy`
  * `TermVectorOffsetStrategy`
  * `PostingsOffsetStrategy`
  * `PostingsWithTermVectorsOffsetStrategy`