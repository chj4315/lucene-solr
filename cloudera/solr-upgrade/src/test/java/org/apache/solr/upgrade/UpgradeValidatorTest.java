/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.upgrade;

import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.apache.solr.config.upgrade.UpgradeConfigException;
import org.hamcrest.Matcher;
import org.apache.zookeeper.ZooKeeper;
import org.junit.After;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import static org.apache.solr.util.RegexMatcher.matchesPattern;
import static org.hamcrest.CoreMatchers.allOf;
import static org.junit.matchers.JUnitMatchers.containsString;
import static org.junit.matchers.JUnitMatchers.hasItem;

public class UpgradeValidatorTest extends UpgradeTestBase {
  @After
  public void dumpSolrLogFileIfTestFailed() {
    if(solr4 != null) {
      solr4.dumpLogFileIfPossible();
    }
    if(solr != null) {
      solr.dumpLogFileIfPossible();
    }
  }


  public static final String INCOMPATIBILITY_DESCRIPTIONS = "/result/incompatibility[contains(level, '%s')]/description";

  @Test
  public void verifyInvalidItems() throws Exception {
    createSolr4Cluster();

    dockerRunner.copy4_10_3SolrXml(new File(solr4.getNodeDir()));
    solr4.start();
    createLegacyCollectionBasedOnConfig(COLLECTION_NAME, "invalid-mix");

    try {
      upgradeConfig("invalid-mix");
      fail();
    } catch (UpgradeConfigException e) {
    }

    assertMatchesAll(configIncompatibilities("error"),
        containsString("infoStream"),
        allOf(containsString("queryIndexAuthorization"), containsString("QueryIndexAuthorizationComponent")),
        allOf(containsString("secureGet"), containsString("SecureRealTimeGetComponent")),
        allOf(containsString("/analysis/field"), containsString("SecureFieldAnalysisRequestHandler")),
        allOf(containsString("/analysis/document"), containsString("SecureDocumentAnalysisRequestHandler")),
        allOf(containsString("replication"), containsString("SecureReplicationHandler")),
        allOf(containsString("/get"), containsString("SecureRealTimeGetHandler"))
    );
    assertMatchesAll(configIncompatibilities("info"),
        containsString("mergeFactor"),
        containsString("UniqFieldsUpdateProcessorFactory"),
        allOf(containsString("default response type"), containsString("JSON")),
        allOf(containsString("handleSelect"), containsString("defaults to false"))
    );
    assertMatchesAll(configIncompatibilities("warn"),
        allOf(containsString("PostingsSolrHighlighter"), containsString("UnifiedSolrHighlighter"))
    );
    try {
      upgradeSchema();
      fail();
    } catch (UpgradeConfigException e) {

    }
    assertMatchesAll(schemaIncompatibilities("info"),
      containsString("SchemaSimilarityFactory"),
      matchesPattern("BeiderMorseFilterFactory.*rebuild"),
      matchesPattern("SchemaSimilarityFactory.*BM25SimilarityFactory")
    );
    assertMatchesAll(schemaIncompatibilities("error"),
      containsString("CJKTokenizerFactory"),
      allOf(containsString("SpatialRecursivePrefixTreeFieldType"), containsString("deprecated")),
      matchesPattern("LegacyHTMLStripCharFilterFactory.*removed"),
      matchesPattern("ThaiWordFilterFactory.*removed"),
      matchesPattern("ICUCollationKeyFilterFactory.*removed"),
      matchesPattern("CollationKeyFilterFactory.*removed"),
      matchesPattern("ChineseFilterFactory.*removed"),
      matchesPattern("SmartChineseSentenceTokenizerFactory.*removed"),
      matchesPattern("ArabicLetterTokenizerFactory.*removed"),
      matchesPattern("CJKTokenizerFactory.*removed"),
      matchesPattern("RussianLetterTokenizerFactory.*removed"),
      matchesPattern("ChineseAnalyzer.*removed"),
      matchesPattern("solrQueryParser.*no.*supported."),
      matchesPattern("defaultSearchField.*no.*supported.")

    );
    stopSolr4();

  }

  private void assertMatchesAll(Set<String> findings, Matcher<String> ... matchers) {
    for (Matcher<String> m: matchers){
      assertThat(findings, hasItem(m));
    }
  }

  private Set<String> configIncompatibilities(String level) throws XPathExpressionException, FileNotFoundException {
    return asSet(String.format(INCOMPATIBILITY_DESCRIPTIONS, level), validationResult("solrconfig_validation.xml"));
  }

  private Set<String> schemaIncompatibilities(String level) throws XPathExpressionException, FileNotFoundException {
    return asSet(String.format(INCOMPATIBILITY_DESCRIPTIONS, level), validationResult("schema_validation.xml"));
  }

  private Set<String> asSet(String xpath, Path input) throws XPathExpressionException, FileNotFoundException {
    NodeList incompatibilities = (NodeList) XPathFactory.newInstance().newXPath().evaluate(xpath, new InputSource(new FileInputStream(input.toFile())), XPathConstants.NODESET);
    Set<String> incompatibilityList = new HashSet<>();
    for(int i=0;i<incompatibilities.getLength(); i++) {
      Element e = (Element) incompatibilities.item(i);
      incompatibilityList.add(e.getTextContent());
    }
    return incompatibilityList;
  }

  private Path validationResult(String fileName) {
    return upgradedDir.resolve(fileName);
  }

}