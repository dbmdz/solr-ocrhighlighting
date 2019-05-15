package org.mdz.search.solrocr.lucene.byteoffset;

import com.google.common.math.IntMath;
import java.io.IOException;
import java.math.RoundingMode;
import org.apache.lucene.analysis.payloads.AbstractEncoder;
import org.apache.lucene.store.ByteArrayDataInput;
import org.apache.lucene.store.ByteArrayDataOutput;
import org.apache.lucene.util.BytesRef;

public class ByteOffsetEncoder extends AbstractEncoder {
  public static byte[] encode(int i) {
    int size = IntMath.log2(i, RoundingMode.FLOOR) / 7 + 1;
    byte[] buf = new byte[size];
    try {
      new ByteArrayDataOutput(buf).writeVInt(i);
    } catch (IOException e) {
      // NOTE: Should not ever happen, since we're operating on the heap
      throw new RuntimeException(e);
    }
    return buf;
  }

  public static int decode(BytesRef payload) {
    if (payload == null) {
      return -1;
    }
    return new ByteArrayDataInput(payload.bytes, payload.offset, payload.length).readVInt();
  }

  @Override
  public BytesRef encode(char[] buffer, int offset, int length) {
    if (length == 0) {
      return null;
    }
    int byteOffset = Integer.parseInt(new String(buffer, offset, length));
    return new BytesRef(encode(byteOffset));
  }
}
