package org.mdz.search.solrocr.formats;

public enum OcrBlock {
  /* Order matters: From top of the page layout hierarchy to bottom */
  PAGE,
  BLOCK,
  SECTION,
  PARAGRAPH,
  LINE,
  WORD;
}
