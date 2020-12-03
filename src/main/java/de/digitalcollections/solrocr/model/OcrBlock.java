package de.digitalcollections.solrocr.model;

import com.google.common.collect.ImmutableList;
import java.util.List;

public enum OcrBlock {
  /* Order matters: From top of the page layout hierarchy to bottom */
  PAGE,
  BLOCK,
  SECTION,
  PARAGRAPH,
  LINE,
  WORD;

  public static List<OcrBlock> blockHierarchy = ImmutableList.of(
      WORD, LINE, PARAGRAPH, BLOCK, SECTION, PAGE);

  public static List<OcrBlock> getHierarchyFrom(OcrBlock block) {
    return blockHierarchy.subList(
        blockHierarchy.indexOf(block), blockHierarchy.size()
    );
  }
}
