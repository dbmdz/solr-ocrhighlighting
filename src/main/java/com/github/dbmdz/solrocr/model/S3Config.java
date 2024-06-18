package com.github.dbmdz.solrocr.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import java.io.File;
import java.io.IOException;

@JacksonXmlRootElement(localName = "s3Config")
public class S3Config {
  @JacksonXmlProperty @JsonProperty public String endpoint;
  @JacksonXmlProperty @JsonProperty public String accessKey;
  @JacksonXmlProperty @JsonProperty public String secretKey;

  public S3Config() {}

  public static S3Config parse(String configFile) throws IOException {
    S3Config s3Config;
    if (configFile.endsWith(".json")) {
      s3Config = new ObjectMapper().readValue(new File(configFile), S3Config.class);
    } else if (configFile.endsWith(".xml")) {
      s3Config = new XmlMapper().readValue(new File(configFile), S3Config.class);
    } else {
      throw new UnsupportedOperationException("Unsupported file format: " + configFile);
    }
    return s3Config;
  }
}
