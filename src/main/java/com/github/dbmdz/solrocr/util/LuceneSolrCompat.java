package com.github.dbmdz.solrocr.util;

import com.github.dbmdz.solrocr.reader.LegacyBaseCompositeReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.BaseCompositeReader;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.QueryTimeout;
import org.apache.lucene.index.StoredFieldVisitor;
import org.apache.lucene.search.IndexSearcher;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.QueryLimits;

/** Reflection-backed compatibility helpers for Solr/Lucene APIs that moved between releases. */
public class LuceneSolrCompat {
  private static final Method INDEX_SEARCHER_DOC =
      getMethodOrNull(IndexSearcher.class, "doc", int.class, java.util.Set.class);
  private static final Method INDEX_SEARCHER_STORED_FIELDS =
      getMethodOrNull(IndexSearcher.class, "storedFields");
  private static final Method INDEX_READER_GET_TERM_VECTORS =
      getMethodOrNull(IndexReader.class, "getTermVectors", int.class);
  private static final Method INDEX_READER_TERM_VECTORS =
      getMethodOrNull(IndexReader.class, "termVectors");
  private static final Method INDEX_READER_DOCUMENT =
      getMethodOrNull(IndexReader.class, "document", int.class, StoredFieldVisitor.class);
  private static final Method INDEX_READER_STORED_FIELDS =
      getMethodOrNull(IndexReader.class, "storedFields");
  private static final Method RESPONSE_BUILDER_GET_STAGE =
      getMethodOrNull(ResponseBuilder.class, "getStage");
  private static final Field RESPONSE_BUILDER_STAGE =
      getFieldOrNull(ResponseBuilder.class, "stage");
  private static final Method SOLR_QUERY_TIMEOUT_IMPL_GET_INSTANCE =
      getMethodOrNull("org.apache.solr.search.SolrQueryTimeoutImpl", "getInstance");
  private static final Method STORED_FIELDS_DOCUMENT =
      getCompatMethod(
          INDEX_SEARCHER_STORED_FIELDS,
          INDEX_READER_STORED_FIELDS,
          "document",
          java.util.Set.class);
  private static final Method STORED_FIELDS_VISIT =
      getCompatMethod(
          INDEX_SEARCHER_STORED_FIELDS,
          INDEX_READER_STORED_FIELDS,
          "document",
          StoredFieldVisitor.class);
  private static final Method TERM_VECTORS_GET =
      getCompatMethod(INDEX_READER_TERM_VECTORS, null, "get");

  private LuceneSolrCompat() {}

  public static Document getDocument(IndexSearcher searcher, int docId, String[] fieldNames)
      throws IOException {
    if (INDEX_SEARCHER_DOC != null) {
      return (Document)
          invoke(searcher, INDEX_SEARCHER_DOC, docId, new HashSet<>(Arrays.asList(fieldNames)));
    }
    Object storedFields = invoke(searcher, INDEX_SEARCHER_STORED_FIELDS);
    return (Document)
        invoke(
            storedFields, STORED_FIELDS_DOCUMENT, docId, new HashSet<>(Arrays.asList(fieldNames)));
  }

  public static Fields getTermVectors(IndexReader reader, int docId) throws IOException {
    if (INDEX_READER_GET_TERM_VECTORS != null) {
      return (Fields) invoke(reader, INDEX_READER_GET_TERM_VECTORS, docId);
    }
    return getTermVectors(openTermVectors(reader), docId);
  }

  public static Object openTermVectors(IndexReader reader) throws IOException {
    return invoke(reader, INDEX_READER_TERM_VECTORS);
  }

  public static Fields getTermVectors(Object termVectors, int docId) throws IOException {
    return (Fields) invoke(termVectors, TERM_VECTORS_GET, docId);
  }

  public static void visitStoredFields(IndexReader reader, int docId, StoredFieldVisitor visitor)
      throws IOException {
    if (INDEX_READER_DOCUMENT != null) {
      invoke(reader, INDEX_READER_DOCUMENT, docId, visitor);
      return;
    }
    Object storedFields = invoke(reader, INDEX_READER_STORED_FIELDS);
    invoke(storedFields, STORED_FIELDS_VISIT, docId, visitor);
  }

  public static Object openStoredFields(IndexReader reader) throws IOException {
    return invoke(reader, INDEX_READER_STORED_FIELDS);
  }

  public static void visitStoredFields(Object storedFields, int docId, StoredFieldVisitor visitor)
      throws IOException {
    invoke(storedFields, STORED_FIELDS_VISIT, docId, visitor);
  }

  public static int getStage(ResponseBuilder rb) {
    if (RESPONSE_BUILDER_GET_STAGE != null) {
      try {
        return (Integer) invokeUnchecked(rb, RESPONSE_BUILDER_GET_STAGE);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    if (RESPONSE_BUILDER_STAGE == null) {
      throw new IllegalStateException("Neither ResponseBuilder.getStage() nor stage field exist");
    }
    try {
      return RESPONSE_BUILDER_STAGE.getInt(rb);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  public static QueryTimeout getQueryTimeout(SolrQueryRequest req) {
    final QueryTimeout globalTimeout;
    if (SOLR_QUERY_TIMEOUT_IMPL_GET_INSTANCE != null) {
      try {
        globalTimeout = (QueryTimeout) invokeUnchecked(null, SOLR_QUERY_TIMEOUT_IMPL_GET_INSTANCE);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    } else {
      QueryLimits limits = QueryLimits.getCurrentLimits();
      globalTimeout = limits.isLimitsEnabled() ? limits : null;
    }
    return globalTimeout;
  }

  public static IndexReader createCompositeReader(
      LeafReader[] leafReaders, IndexReader parentReader) throws IOException {
    if (VersionUtils.luceneVersionIsBefore(8, 9)) {
      return new LegacyBaseCompositeReader<IndexReader>(leafReaders) {
        @Override
        protected void doClose() throws IOException {
          parentReader.close();
        }

        @Override
        public CacheHelper getReaderCacheHelper() {
          return null;
        }
      };
    }
    return new BaseCompositeReader<IndexReader>(leafReaders, null) {
      @Override
      protected void doClose() throws IOException {
        parentReader.close();
      }

      @Override
      public CacheHelper getReaderCacheHelper() {
        return null;
      }
    };
  }

  private static Method getMethodOrNull(Class<?> cls, String name, Class<?>... parameterTypes) {
    try {
      return cls.getMethod(name, parameterTypes);
    } catch (NoSuchMethodException e) {
      return null;
    }
  }

  private static Method getMethodOrNull(String className, String name, Class<?>... parameterTypes) {
    try {
      return Class.forName(className).getDeclaredMethod(name, parameterTypes);
    } catch (ClassNotFoundException | NoSuchMethodException e) {
      return null;
    }
  }

  private static Field getFieldOrNull(Class<?> cls, String name) {
    try {
      return cls.getField(name);
    } catch (NoSuchFieldException e) {
      return null;
    }
  }

  private static Method getCompatMethod(
      Method primarySource, Method secondarySource, String name, Class<?>... trailingParams) {
    Method source = primarySource != null ? primarySource : secondarySource;
    if (source == null) {
      return null;
    }
    Class<?>[] parameterTypes = new Class<?>[trailingParams.length + 1];
    parameterTypes[0] = int.class;
    System.arraycopy(trailingParams, 0, parameterTypes, 1, trailingParams.length);
    try {
      return source.getReturnType().getMethod(name, parameterTypes);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(
          "Could not resolve compatibility method '" + name + "' on " + source.getReturnType(), e);
    }
  }

  private static Object invoke(Object target, Method method, Object... args) throws IOException {
    try {
      return invokeUnchecked(target, method, args);
    } catch (IOException e) {
      throw e;
    }
  }

  private static Object invokeUnchecked(Object target, Method method, Object... args)
      throws IOException {
    if (method == null) {
      throw new IllegalStateException("Required compatibility method is unavailable");
    }
    try {
      return method.invoke(target, args);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException) {
        throw (IOException) cause;
      }
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw new RuntimeException(cause);
    }
  }
}
