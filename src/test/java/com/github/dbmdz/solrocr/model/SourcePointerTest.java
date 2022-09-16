package com.github.dbmdz.solrocr.model;

import com.github.dbmdz.solrocr.model.SourcePointer.FileSource;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.request.SolrQueryRequest;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Specification for {@link SourcePointer}
 */
public class SourcePointerTest extends SolrTestCaseJ4 {

  @Test
  public void testEmptySourcePointerToString() {

    List<FileSource> sources = new ArrayList<>();
    SourcePointer pointer = new SourcePointer(sources);

    assertEquals("", pointer.toString());
  }

  @Test
  public void testFinalWhat() {


      List<MyClazz> mc1 = new ArrayList<>();
      MyWrapper w1 = new MyWrapper(mc1);
      
      assertEquals(0, w1.myClazzes.size());
      mc1.add(new MyClazz("Foo"));

      assertEquals(0, w1.myClazzes.size());
  }

}

class MyClazz {
  String name;

  public MyClazz(String name) {
    this.name = name;
  }
}

class MyWrapper {
  final List<MyClazz> myClazzes;

  public MyWrapper(List<MyClazz> myC) {
    this.myClazzes = myC;
  }
}