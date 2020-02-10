package de.digitalcollections.solrocr.util;

import java.awt.Dimension;
import java.util.Objects;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;

/* Identifier and size of a given OCR page */
public class OcrPage {
  public final String id;
  public final Dimension dimensions;

  public OcrPage(String id, Dimension dimensions) {
    Objects.requireNonNull(id, "Pages need to have an identifier, check your source files!");
    this.id = id;
    this.dimensions = dimensions;
  }

  @SuppressWarnings("rawtypes")
  public NamedList toNamedList() {
    NamedList nl = new SimpleOrderedMap();
    nl.add("id", id);
    if (dimensions != null) {
      nl.add("width", dimensions.width);
      nl.add("height", dimensions.height);
    }
    return nl;
  }
}
