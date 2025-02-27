package com.github.dbmdz.solrocr.util;

import org.apache.lucene.util.Version;
import org.apache.solr.client.api.util.SolrVersion;

public class SolrVersionInfo {
  private static final int PRE_SOLR94_LUCENE_MINOR_VERSION_DIFF = 2;

  public static boolean luceneVersionIsBefore(int major, int minor) {
    return Version.LATEST.major < major
        || (Version.LATEST.major == major && Version.LATEST.minor < minor);
  }

  public static boolean solrVersionIsBefore(int major, int minor) {
    // Solr versions up until 9.1 track the Lucene version, i.e. they are identical
    if (luceneVersionIsBefore(9, 1)) {
      return luceneVersionIsBefore(major, minor);
    }

    // Up until Solr 9.4 (with Lucene 9.8), the SolrVersion class was in a
    // different namespace, so we can't use it here.
    // However, the difference in minor versions was constant between 9.1 and 9.3, so
    // we can use that to calculate the Solr version.
    if (luceneVersionIsBefore(9, 8)) {
      int solrMinorVersion = Version.LATEST.minor - PRE_SOLR94_LUCENE_MINOR_VERSION_DIFF;
      return 9 < major || solrMinorVersion < minor;
    }

    // For Solr 9.4 and later, we can use the SolrVersion class directly
    return SolrVersion.LATEST.lessThan(SolrVersion.forIntegers(major, minor, 0));
  }
}
