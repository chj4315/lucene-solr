package org.apache.solr.update;

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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.http.client.HttpClient;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.impl.conn.PoolingClientConnectionManager;
import org.apache.http.impl.conn.SchemeRegistryFactory;
import org.apache.solr.client.solrj.impl.HttpClientUtil;
import org.apache.solr.cloud.RecoveryStrategy;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.common.util.SolrjNamedThreadFactory;
import org.apache.solr.core.ConfigSolr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpdateShardHandler {
  
  private static Logger log = LoggerFactory.getLogger(UpdateShardHandler.class);
  
  private ExecutorService updateExecutor = Executors.newCachedThreadPool(
      new SolrjNamedThreadFactory("updateExecutor"));
  
  private ExecutorService recoveryExecutor = Executors.newCachedThreadPool(
      new SolrjNamedThreadFactory("recoveryExecutor"));
  
  private PoolingClientConnectionManager clientConnectionManager;
  
  private final HttpClient client;

  public UpdateShardHandler(ConfigSolr cfg) {
    
    clientConnectionManager = new PoolingClientConnectionManager(SchemeRegistryFactory.createSystemDefault());
    if (cfg != null ) {
      clientConnectionManager.setMaxTotal(cfg.getMaxUpdateConnections());
      clientConnectionManager.setDefaultMaxPerRoute(cfg.getMaxUpdateConnectionsPerHost());
    }
    
    
    ModifiableSolrParams params = new ModifiableSolrParams();
    if (cfg != null) {
      params.set(HttpClientUtil.PROP_SO_TIMEOUT,
          cfg.getDistributedSocketTimeout());
      params.set(HttpClientUtil.PROP_CONNECTION_TIMEOUT,
          cfg.getDistributedConnectionTimeout());
    }
    // in the update case, we want to do retries, and to use
    // the default Solr retry handler that createClient will 
    // give us
    params.set(HttpClientUtil.PROP_USE_RETRY, true);
    client = HttpClientUtil.createClient(params, clientConnectionManager);
  }
  
  
  public HttpClient getHttpClient() {
    return client;
  }

  public ClientConnectionManager getConnectionManager() {
    return clientConnectionManager;
  }
  
  /**
   * This method returns an executor that is not meant for disk IO and that will
   * be interrupted on shutdown.
   * 
   * @return an executor for update related activities that do not do disk IO.
   */
  public ExecutorService getUpdateExecutor() {
    return updateExecutor;
  }
  
  /**
   * In general, RecoveryStrategy threads do not do disk IO, but they open and close SolrCores
   * in async threads, amoung other things, and can trigger disk IO, so we use this alternate 
   * executor rather than the 'updateExecutor', which is interrupted on shutdown.
   * 
   * @return executor for {@link RecoveryStrategy} thread which will not be interrupted on close.
   */
  public ExecutorService getRecoveryExecutor() {
    return recoveryExecutor;
  }

  public void close() {
    try {
      // we interrupt on purpose here, but this exectuor should not run threads that do disk IO!
      ExecutorUtil.shutdownWithInterruptAndAwaitTermination(updateExecutor);
      ExecutorUtil.shutdownAndAwaitTermination(recoveryExecutor);
    } catch (Exception e) {
      SolrException.log(log, e);
    } finally {
      clientConnectionManager.shutdown();
    }
  }

}
