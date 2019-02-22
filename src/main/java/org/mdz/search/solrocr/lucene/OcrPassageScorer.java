package org.mdz.search.solrocr.lucene;

import org.apache.lucene.search.uhighlight.PassageScorer;

public class OcrPassageScorer extends PassageScorer {
  private final boolean boostEarly;

  public OcrPassageScorer(float k1, float b, float pivot, boolean boostEarly) {
    super(k1, b, pivot);
    this.boostEarly = boostEarly;
  }

  /** If enabled with `hl.score.boostEarly`, normalize the passage start so that earlier starts are
   *  given more weight. */
  @Override
  public float norm(int passageStart) {
    if (boostEarly) {
      return super.norm(passageStart);
    } else {
      return passageStart;
    }
  }
}
