package com.github.dbmdz.solrocr.model;

import java.awt.Dimension;
import java.util.Objects;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;

/* Identifier and size of a given OCR page */
public class OcrPage implements Comparable<OcrPage> {
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

  @Override
  public int compareTo(OcrPage o) {
    return this.id.compareTo(o.id);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    OcrPage ocrPage = (OcrPage) o;
    return id.equals(ocrPage.id) && Objects.equals(dimensions, ocrPage.dimensions);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, dimensions);
  }
}
