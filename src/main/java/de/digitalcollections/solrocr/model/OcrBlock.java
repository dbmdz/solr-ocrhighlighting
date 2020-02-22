package de.digitalcollections.solrocr.model;

public enum OcrBlock {
  /* Order matters: From top of the page layout hierarchy to bottom */
  PAGE,
  BLOCK,
  SECTION,
  PARAGRAPH,
  LINE,
  WORD;
}
