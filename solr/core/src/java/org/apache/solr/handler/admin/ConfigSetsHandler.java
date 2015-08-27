package org.apache.solr.handler.admin;

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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.solr.client.solrj.SolrResponse;
import org.apache.solr.cloud.DistributedQueue.QueueEvent;
import org.apache.solr.cloud.Overseer;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.ConfigSetParams;
import org.apache.solr.common.params.ConfigSetParams.ConfigSetAction;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.zookeeper.KeeperException;
import static org.apache.solr.cloud.OverseerConfigSetMessageHandler.BASE_CONFIGSET;
import static org.apache.solr.cloud.OverseerConfigSetMessageHandler.CONFIGSETS_ACTION_PREFIX;
import static org.apache.solr.cloud.OverseerConfigSetMessageHandler.PROPERTY_PREFIX;
import static org.apache.solr.common.params.CommonParams.NAME;
import static org.apache.solr.common.params.ConfigSetParams.ConfigSetAction.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import static org.apache.solr.cloud.Overseer.QUEUE_OPERATION;

/**
 * A {@link org.apache.solr.request.SolrRequestHandler} for ConfigSets API requests.
 */
public class ConfigSetsHandler extends RequestHandlerBase {
  protected static Logger log = LoggerFactory.getLogger(ConfigSetsHandler.class);
  protected final CoreContainer coreContainer;
  public static long DEFAULT_ZK_TIMEOUT = 180*1000;

  /**
   * Overloaded ctor to inject CoreContainer into the handler.
   *
   * @param coreContainer Core Container of the solr webapp installed.
   */
  public ConfigSetsHandler(final CoreContainer coreContainer) {
    this.coreContainer = coreContainer;
  }

  @Override
  final public void init(NamedList args) {

  }

  @Override
  public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
    if (coreContainer == null) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
              "Core container instance missing");
    }

    // Make sure that the core is ZKAware
    if(!coreContainer.isZooKeeperAware()) {
      throw new SolrException(ErrorCode.BAD_REQUEST,
          "Solr instance is not running in SolrCloud mode.");
    }

    // Pick the action
    SolrParams params = req.getParams();
    String a = params.get(ConfigSetParams.ACTION);
    if (a != null) {
      ConfigSetAction action = ConfigSetAction.get(a);
      if (action == null)
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Unknown action: " + a);
      ConfigSetOperation operation = ConfigSetOperation.get(action);
      log.info("Invoked ConfigSet Action :{} with params {} ", action.toLower(), req.getParamString());
      Map<String, Object> result = operation.call(req, rsp, this);
      if (result != null) {
        // We need to differentiate between collection and configsets actions since they currently
        // use the same underlying queue.
        result.put(QUEUE_OPERATION, CONFIGSETS_ACTION_PREFIX + operation.action.toLower());
        ZkNodeProps props = new ZkNodeProps(result);
        handleResponse(operation.action.toLower(), props, rsp, DEFAULT_ZK_TIMEOUT);
      }
    } else {
      throw new SolrException(ErrorCode.BAD_REQUEST, "action is a required param");
    }

    rsp.setHttpCaching(false);
  }

  private void handleResponse(String operation, ZkNodeProps m,
      SolrQueryResponse rsp, long timeout) throws KeeperException, InterruptedException {
    long time = System.nanoTime();

    QueueEvent event = coreContainer.getZkController()
        .getOverseerConfigSetQueue()
        .offer(ZkStateReader.toJSON(m), timeout);
    if (event.getBytes() != null) {
      SolrResponse response = SolrResponse.deserialize(event.getBytes());
      rsp.getValues().addAll(response.getResponse());
      SimpleOrderedMap exp = (SimpleOrderedMap) response.getResponse().get("exception");
      if (exp != null) {
        Integer code = (Integer) exp.get("rspCode");
        rsp.setException(new SolrException(code != null && code != -1 ? ErrorCode.getErrorCode(code) : ErrorCode.SERVER_ERROR, (String)exp.get("msg")));
      }
    } else {
      if (System.nanoTime() - time >= TimeUnit.NANOSECONDS.convert(timeout, TimeUnit.MILLISECONDS)) {
        throw new SolrException(ErrorCode.SERVER_ERROR, operation
            + " the configset time out:" + timeout / 1000 + "s");
      } else if (event.getWatchedEvent() != null) {
        throw new SolrException(ErrorCode.SERVER_ERROR, operation
            + " the configset error [Watcher fired on path: "
            + event.getWatchedEvent().getPath() + " state: "
            + event.getWatchedEvent().getState() + " type "
            + event.getWatchedEvent().getType() + "]");
      } else {
        throw new SolrException(ErrorCode.SERVER_ERROR, operation
            + " the configset unknown case");
      }
    }
  }

  private static Map<String, Object> copyPropertiesWithPrefix(SolrParams params, Map<String, Object> props, String prefix) {
    Iterator<String> iter =  params.getParameterNamesIterator();
    while (iter.hasNext()) {
      String param = iter.next();
      if (param.startsWith(prefix)) {
        props.put(param, params.get(param));
      }
    }
    return props;
  }

  private static void copyIfNotNull(SolrParams params, Map<String, Object> props, String... keys) {
    ArrayList<String> prefixes = new ArrayList<>(1);
    if(keys !=null){
      for (String key : keys) {
        if(key.endsWith(".")) {
          prefixes.add(key);
          continue;
        }
        String v = params.get(key);
        if(v != null) props.put(key,v);
      }
    }
    if(prefixes.isEmpty()) return;
    Iterator<String> it = params.getParameterNamesIterator();
    String prefix = null;
    for(;it.hasNext();){
      String name = it.next();
      for (int i = 0; i < prefixes.size(); i++) {
        if(name.startsWith(prefixes.get(i))){
          String val = params.get(name);
          if(val !=null) props.put(name,val);
        }
      }
    }

  }

  @Override
  public String getDescription() {
    return "Manage SolrCloud ConfigSets";
  }

  @Override
  public String getSource() {
    return null;
  }

  enum ConfigSetOperation {
    CREATE_OP(CREATE) {
      @Override
      Map<String, Object> call(SolrQueryRequest req, SolrQueryResponse rsp, ConfigSetsHandler h) throws Exception {
        req.getParams().required().check(NAME, BASE_CONFIGSET);
        Map<String, Object> props = new HashMap<>();
        copyIfNotNull(req.getParams(), props, NAME, BASE_CONFIGSET);
        return copyPropertiesWithPrefix(req.getParams(), props, PROPERTY_PREFIX + ".");
      }
    },
    DELETE_OP(DELETE) {
      @Override
      Map<String, Object> call(SolrQueryRequest req, SolrQueryResponse rsp, ConfigSetsHandler h) throws Exception {
        req.getParams().required().check(NAME);
        Map<String, Object> props = new HashMap<>();
        copyIfNotNull(req.getParams(), props, NAME);
        return props;
      }
    };

    ConfigSetAction action;

    ConfigSetOperation(ConfigSetAction action) {
      this.action = action;
    }

    abstract Map<String, Object> call(SolrQueryRequest req, SolrQueryResponse rsp, ConfigSetsHandler h) throws Exception;

    public static ConfigSetOperation get(ConfigSetAction action) {
      for (ConfigSetOperation op : values()) {
        if (op.action == action) return op;
      }
      throw new SolrException(ErrorCode.SERVER_ERROR, "No such action" + action);
    }
  }
}
