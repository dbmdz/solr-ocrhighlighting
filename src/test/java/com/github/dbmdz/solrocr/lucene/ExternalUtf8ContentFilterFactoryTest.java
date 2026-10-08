package com.github.dbmdz.solrocr.lucene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dbmdz.solrocr.model.SourcePointer;
import com.github.dbmdz.solrocr.reader.SourceReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import solrocr.ExternalUtf8ContentFilterFactory;

class ExternalUtf8ContentFilterFactoryTest {
  @TempDir Path tempDir;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void constructionFailureClosesTheSourceAndPreservesTheOriginalException(boolean closeFails)
      throws IOException {
    Path path = tempDir.resolve("invalid-region.txt");
    Files.write(path, new byte[] {65, 66, 67, 68, 69});
    String pointerText = path + "[3:2]";
    SourcePointer pointer = spy(SourcePointer.parse(pointerText));
    SourceReader source = mock(SourceReader.class);
    when(source.getByteChannel()).thenReturn(mock(SeekableByteChannel.class));
    IOException closeFailure = new IOException("close failed");
    if (closeFails) {
      doThrow(closeFailure).when(source).close();
    }
    doReturn(source).when(pointer).getReader(512 * 1024, 0);

    try (MockedStatic<SourcePointer> pointers =
        mockStatic(SourcePointer.class, CALLS_REAL_METHODS)) {
      pointers.when(() -> SourcePointer.parse(pointerText)).thenReturn(pointer);
      ExternalUtf8ContentFilterFactory factory =
          new ExternalUtf8ContentFilterFactory(new HashMap<>());
      assertThatThrownBy(() -> factory.create(new StringReader(pointerText)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("ends before it starts")
          .satisfies(
              failure -> {
                if (closeFails) {
                  assertThat(failure.getSuppressed()).containsExactly(closeFailure);
                } else {
                  assertThat(failure.getSuppressed()).isEmpty();
                }
              });
      verify(source).close();
    }
  }

  @Test
  void channelAcquisitionFailureClosesTheSource() throws IOException {
    Path path = tempDir.resolve("channel-failure.txt");
    Files.write(path, new byte[] {65});
    String pointerText = path.toString();
    SourcePointer pointer = spy(SourcePointer.parse(pointerText));
    SourceReader source = mock(SourceReader.class);
    IOException channelFailure = new IOException("channel failed");
    when(source.getByteChannel()).thenThrow(channelFailure);
    doReturn(source).when(pointer).getReader(512 * 1024, 0);

    try (MockedStatic<SourcePointer> pointers =
        mockStatic(SourcePointer.class, CALLS_REAL_METHODS)) {
      pointers.when(() -> SourcePointer.parse(pointerText)).thenReturn(pointer);
      ExternalUtf8ContentFilterFactory factory =
          new ExternalUtf8ContentFilterFactory(new HashMap<>());
      assertThatThrownBy(() -> factory.create(new StringReader(pointerText)))
          .isInstanceOf(RuntimeException.class)
          .hasCause(channelFailure);
      verify(source).close();
    }
  }

  @Test
  void successfulConstructionKeepsTheSourceOpenUntilTheFilterCloses() throws IOException {
    Path path = tempDir.resolve("valid-region.txt");
    Files.write(path, new byte[] {65});
    String pointerText = path.toString();
    SourcePointer pointer = spy(SourcePointer.parse(pointerText));
    SourceReader source = mock(SourceReader.class);
    SeekableByteChannel channel = Files.newByteChannel(path);
    when(source.getByteChannel()).thenReturn(channel);
    doReturn(source).when(pointer).getReader(512 * 1024, 0);

    try (SeekableByteChannel ignored = channel;
        MockedStatic<SourcePointer> pointers =
            mockStatic(SourcePointer.class, CALLS_REAL_METHODS)) {
      pointers.when(() -> SourcePointer.parse(pointerText)).thenReturn(pointer);
      ExternalUtf8ContentFilterFactory factory =
          new ExternalUtf8ContentFilterFactory(new HashMap<>());
      try (Reader filter = factory.create(new StringReader(pointerText))) {
        verify(source, never()).close();
        assertThat(channel.isOpen()).isTrue();
        assertThat(filter.read()).isEqualTo('A');
      }
      assertThat(channel.isOpen()).isFalse();
    }
  }
}
