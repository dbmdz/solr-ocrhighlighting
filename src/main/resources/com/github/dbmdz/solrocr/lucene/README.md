`FieldHighlighterAdapter.class` is a hand-crafted Java class that dynamically selects a
`FieldHighlighter` constructor based on the Solr/Lucene version.

This is neccessary because the `FieldHighlighter` class has changed its constructor signature
between Solr 9.6 and 9.7, introduction an 8th parameter.

Since dynamically selecting a superclass constructor to call isn't posssible at the Java source
level, we have to drop down to the bytecode level to achieve this.

The classs file is defined in the `FieldHighlighterAdapter.jasm` file, which is a [jasm](1) assembly
file.

To compile the class file, [download `jasm`](2) and run it on the `.jasm` file from the project root:

```bash
$ ./jasm-0.7.0/bin/jasm src/main/resources/com/github/dbmdz/solrocr/lucene/FieldHighlighterAdapter.jasm
```

I tried for multiplpe hours to get this to build automatically as part of the Maven build, but had
to give up, Maven just is too painful for this sort of thing.

As to why we don't use the same bytecode patching technique as we did for the Solr 7 -> 8 API breakage,
the reason is that this is a breakage within a single major version, if we created a separate JAR for
each and everyone of those, we'd end up with a huge number of JARs over the years, which is not ideal.

[1]: https://github.com/roscopeco/jasm
[2]: https://github.com/roscopeco/jasm/releases