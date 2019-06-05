package de.digitalcollections.solrocr.util;

import java.util.Iterator;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Stream helpers.
 *
 * Only needed for `zip` and `stream(Iterator)`, since Lucene/Solr ships with an ancient version of Guava that does not
 * yet have `Streams.zip` and `Streams.stream`.
 * This is, however, going to be resolved with 8.0, so this class is only here temporarily.
 */
public class Streams {
  /** Implementation from https://stackoverflow.com/a/23529010/487903 */
  public static<A, B, C> Stream<C> zip(Stream<? extends A> a, Stream<? extends B> b,
                                       BiFunction<? super A, ? super B, ? extends C> zipper) {
    Objects.requireNonNull(zipper);
    Spliterator<? extends A> aSpliterator = Objects.requireNonNull(a).spliterator();
    Spliterator<? extends B> bSpliterator = Objects.requireNonNull(b).spliterator();

    // Zipping looses DISTINCT and SORTED characteristics
    int characteristics = aSpliterator.characteristics() & bSpliterator.characteristics() &
        ~(Spliterator.DISTINCT | Spliterator.SORTED);

    long zipSize = ((characteristics & Spliterator.SIZED) != 0)
        ? Math.min(aSpliterator.getExactSizeIfKnown(), bSpliterator.getExactSizeIfKnown())
        : -1;

    Iterator<A> aIterator = Spliterators.iterator(aSpliterator);
    Iterator<B> bIterator = Spliterators.iterator(bSpliterator);
    Iterator<C> cIterator = new Iterator<C>() {
      @Override
      public boolean hasNext() {
        return aIterator.hasNext() && bIterator.hasNext();
      }

      @Override
      public C next() {
        return zipper.apply(aIterator.next(), bIterator.next());
      }
    };

    Spliterator<C> split = Spliterators.spliterator(cIterator, zipSize, characteristics);
    return (a.isParallel() || b.isParallel())
        ? StreamSupport.stream(split, true)
        : StreamSupport.stream(split, false);
  }

  public static <T> Stream<T> stream(Iterator<T> it) {
    Iterable<T> iterable = () -> it;
    return StreamSupport.stream(iterable.spliterator(), false);
  }
}
