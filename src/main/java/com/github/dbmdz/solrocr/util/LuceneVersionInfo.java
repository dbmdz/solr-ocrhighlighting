package com.github.dbmdz.solrocr.util;

import org.apache.lucene.util.Version;

public class LuceneVersionInfo {
  public static boolean versionIsBefore(int major, int minor) {
    return Version.LATEST.major < major
        || (Version.LATEST.major == major && Version.LATEST.minor < minor);
  }
}
