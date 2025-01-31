## Requirements
- Some familiarity with configuring Solr
- Solr ≥ 7.5
- OCR documents need to be in [hOCR](formats.md#hocr), [ALTO](formats.md#alto)
  or [MiniOCR](formats.md#miniocr) formats, with at least page-, and word-level
  segmentation

## Manually installing the plugin JAR
To use the latest release version, refer to the [GitHub Releases list](https://github.com/dbmdz/solr-ocrhighlighting/releases). From there, download the corresponding JAR file.
To make the plugin available to Solr, create a new directory `$SOLR_HOME/contrib/ocrhighlighting/lib` and place the JAR you just downloaded there.

## For Solrcloud users: Installation as a Solr Package
Since version 8.4, Solrcloud ships with a package management subsystem that can be used
to conveniently install plugins from the command-line. To install the OCR highlighting
plugin in this way, follow these steps on one of the nodes in
your Solrcloud cluster. All paths are relative to the Solr installation directory:

- **Add repository** to the local package registry:<br>
  `$ ./bin/solr package add-repo dbmdz.github.io https://dbmdz.github.io/solr`
- **Install package** in the latest version:<br>
  `$ ./bin/solr package install ocrhighlighting` if you're on Solr 9, otherwise:
  `$ ./bin/solr package install ocrhighlighting:0.9.1-solr78`

!!! caution "Be sure to use the `ocrhighlighting:` prefix when specifying classes in your configuration."
    When using the Package Manager, classes from plugins have to be prefixed (separated by a colon) by
    their plugin's  identifier, for this plugin this identifier is `ocrhighlighting`. So whenever
    you see an attribute like `class="solrocr.SomeClass"`, you have to write
    `class="ocrhighlighting:solrocr.SomeClass"` in your config instead.

# Core Configuration

To enable the use of the plugin for your Solr core, you will have to edit
both the `solrconfig.xml` and the `schema.xml` file in your core's `conf` directory.

Additionally, if you have installed the plugin via Solr's Package Management, you will
have to *deploy* the plugin to your collection/core using Solr's CLI:

```bash
$ bin/solr package deploy ocrhighlighting -collections <your-collection>
```

## SolrConfig

In your core's `solrconfig.xml`, you need to:

1. Enable the plugin for your collection/core by  instructing the collection from where to
   load the plugin classes (**Skip when using Solrcloud with Package Manager** )
2. Define a search component that will perform the OCR highlighting at query time
3. Add the search component to your request handlers that will trigger the highlighting.


```xml hl_lines="10 16 17 18 33"
<config>
  <!-- ...other configuration options... -->

  <!-- Only needed when not using the Package Management:
    Tell Solr to load all JAR files from the directory installed the plugin to. 
    This assumes a directory structure where the cores are in
    `$SOLR_HOME/server/solr/$CORE` and the plugin JAR was installed in
    `$SOLR_HOME/contrib/ocrhighlighting/lib`. Adjust the path if you setup differs.

    NOTE: When using Solr >=9.8, you need to start it with `-Dsolr.config.lib.enabled=true`
          for this to work
  -->
  <lib dir="../../../contrib/ocrhighlighting/lib" regex=".*\.jar" />

  <!-- Add a new named search component that takes care of highlighting OCR field
       values.
       NOTE: Add the `ocrhighlighting:` prefix if using Package Management.
  -->
  <searchComponent
      class="solrocr.OcrHighlightComponent"
      name="ocrHighlight" />

   <!-- ...other search components... -->

  <!--
    Instruct the request handlers you want to enable OCR highlighting for to
    include the search component you defined above. This example uses the
    standard /select handler.

    CAUTION: Make sure that the OCR highlight component is listed **before** the
    standard highlighting component, but **after** the query component.
  -->
  <requestHandler name="/select" class="solr.SearchHandler">
      <arr name="components">
          <str>query</str>
          <str>ocrHighlight</str>
          <str>highlight</str>
      </arr>
  </requestHandler>
</config>
```

If you run into problems, a look into these sections of the Solr user's guide might be helpful:

- [Resource and Plugin Loading](https://lucene.apache.org/solr/guide/8_1/resource-and-plugin-loading.html)
- [Package Manager](https://solr.apache.org/guide/8_11/package-manager.html)
- [RequestHandlers and SearchComponents in SolrConfig](https://lucene.apache.org/solr/guide/8_1/requesthandlers-and-searchcomponents-in-solrconfig.html)


## Schema

In the core's `schema.xml`, you need to:

1. Define a new field type that will hold your indexed OCR text
2. Define which fields are going to hold the indexed OCR text.

The **field type** for OCR text is very similar to your regular text field, with the
difference that there are one or two extra *character filters* at the beginning of your
*index analysis chain*:

  - `ExternalUtf8ContentFilterFactory` will (optionally) allow you to index and highlight OCR from
    external  sources on the file system. More on this in the [Indexing chapter](./indexing.md).

  - `OcrCharFilterFactory` will retrieve the raw OCR data and extract the plain text that is
    going to pass through the rest of the analysis chain. It will auto-detect the used OCR
    formats, which means that **you can use different OCR formats alongside each other**.
    After this filter, Solr will treat the field just like a regular text field for purposes
    of analysis.

Additionally, you need to enable the `storeOffsetsWithPositions` option. The plugin uses these
offsets to locate the matching terms in the OCR documents.

```xml hl_lines="6 11 12 14 15 29"
<schema>
  <types>
    <fieldtype
        name="text_ocr"
        class="solr.TextField"
        storeOffsetsWithPositions="true">
      <!-- NOTE: When not using the Package Manager, add the `ocrhighlighting:`
                 prefix to  the `class` attributes -->
      <analyzer type="index">
        <!-- For loading external files as field values during indexing -->
        <charFilter
          class="solrocr.ExternalUtf8ContentFilterFactory" />
        <!-- For converting OCR to plaintext -->
        <charFilter
          class="solrocr.OcrCharFilterFactory" />
        <!-- ...rest of your index analysis chain... -->
      </analyzer>
      <analyzer type="query">
        <!-- query analysis chain, should not include the character filters -->
      </analyzer>
    </fieldtype>
  </types>

  <fields>
    <!-- ...your other fields ... -->

    <!-- A field that uses the OCR field type. Has to be `stored`. -->
    <field name="ocr_text" type="text_ocr" multiValued="false" indexed="true"
           stored="true" />
  </fields>
</schema>
```

If you struggle with setting up your schema, a look into the [Schema Design](https://lucene.apache.org/solr/guide/8_1/documents-fields-and-schema-design.html)
chapter of the Solr user's guide might be helpful.

!!! caution "No support for multi-valued fields"
    Due to certain limitations in Lucene/Solr, it is currently **not possible
    to use multi-valued fields for OCR highlighting**. You can work around
    this by leveraging some of the advanced features of [source pointers](./indexing.md),
    though.
