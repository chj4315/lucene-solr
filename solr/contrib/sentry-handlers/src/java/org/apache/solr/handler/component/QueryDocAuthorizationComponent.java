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

package org.apache.solr.handler.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.sentry.SentryIndexAuthorizationSingleton;
import org.apache.solr.request.LocalSolrQueryRequest;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.net.URLEncoder;

public class QueryDocAuthorizationComponent extends SearchComponent
{
  private static Logger log =
    LoggerFactory.getLogger(QueryDocAuthorizationComponent.class);
  public static String AUTH_FIELD_PROP = "sentryAuthField";
  public static String DEFAULT_AUTH_FIELD = "_sentry_auth_";
  public static String ALL_GROUPS_TOKEN_PROP = "allGroupsToken";
  private SentryIndexAuthorizationSingleton sentryInstance;
  private String authField;
  private String allGroupsToken;

  public QueryDocAuthorizationComponent() {
    this(SentryIndexAuthorizationSingleton.getInstance());
  }

  @VisibleForTesting
  public QueryDocAuthorizationComponent(SentryIndexAuthorizationSingleton sentryInstance) {
    super();
    this.sentryInstance = sentryInstance;
  }

  @Override
  public void init(NamedList args) {
    Object fieldArg = args.get(AUTH_FIELD_PROP);
    this.authField = (fieldArg == null) ? DEFAULT_AUTH_FIELD : fieldArg.toString();
    log.info("QueryDocAuthorizationComponent authField: " + this.authField);
    Object groupsArg = args.get(ALL_GROUPS_TOKEN_PROP);
    this.allGroupsToken = (groupsArg == null) ? "" : groupsArg.toString();
    log.info("QueryDocAuthorizationComponent allGroupsToken: " + this.allGroupsToken);
  }

  private void addRawClause(StringBuilder builder, String authField, String value) {
    // requires a space before the first term, so the
    // default lucene query parser will be used
    builder.append(" {!raw f=").append(authField).append(" v=")
      .append(value).append("}");
  }

  @Override
  public void prepare(ResponseBuilder rb) throws IOException {
    String userName = sentryInstance.getUserName(rb.req);
    String superUser = (System.getProperty("solr.authorization.superuser", "solr"));
    if (superUser.equals(userName)) {
      return;
    }
    List<String> groups = sentryInstance.getGroups(userName);
    if (groups != null && groups.size() > 0) {
      StringBuilder builder = new StringBuilder();
      for (int i = 0; i < groups.size(); ++i) {
        addRawClause(builder, authField, groups.get(i));
      }
      if (allGroupsToken != null && !allGroupsToken.isEmpty()) {
        addRawClause(builder, authField, allGroupsToken);
      }
      ModifiableSolrParams newParams = new ModifiableSolrParams(rb.req.getParams());
      String result = builder.toString();
      newParams.add("fq", result);
      rb.req.setParams(newParams);
    } else {
      throw new SolrException(SolrException.ErrorCode.UNAUTHORIZED,
        "Request from user: " + userName +
        " rejected because user does not belong to any groups.");
    }
  }

  @Override
  public void process(ResponseBuilder rb) throws IOException {
  }

  @Override
  public String getDescription() {
    return "Handle Query Document Authorization";
  }

  @Override
  public String getSource() {
    return "$URL$";
  }
}
