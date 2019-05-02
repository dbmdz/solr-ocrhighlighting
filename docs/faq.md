## Can I have documents that point to a part of an OCR document on external storage?

**Yes, with an ugly hack**. This use case appears e.g. when indexing digital newspapers,
where you have a single large volume on disk (e.g. the OCR text for the bound volume containing all issues from the
year 1878) but you want your Solr documents to be more fine-grained (e.g. on the issue or even article level).
A problem in Solr is that the source of the offsets that are used for highlighting are always relative to the actual
document that was used for indexing and cannot be easily customized. To work around this:<br/>
**Replace all of the content preceding your sub-section with a single XML comment tag that is exactly as long as the
old content and discard all content that follows after the sub-section** (We told you the solution was hacky, didn't
we?). This will lead the analyzer chain to discard all of the undesired content, while still storing the correct offsets
for the sub-section content in the index.

Minimal example before masking:

```
<l>Some content that you don't want in your Solr document</l>
<l>Here's the content you want in the index for this document</l>
<l>And here's some extra content following it that you don't want</l>
```

Minimal example after masking:
```
<!---------------------------------------------------------->
<l>Here's the content you want in the index for this document</l>
```
