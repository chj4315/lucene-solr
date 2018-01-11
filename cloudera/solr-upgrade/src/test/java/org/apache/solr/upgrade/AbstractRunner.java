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

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.common.collect.ImmutableMap;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.messages.ContainerConfig;
import com.spotify.docker.client.messages.ContainerCreation;
import com.spotify.docker.client.messages.HostConfig;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.solr.common.SolrException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.solr.upgrade.DockerRunner.CORES_SUB_DIR;
import static org.apache.solr.upgrade.DockerRunner.SOLR_FROM;
import static org.apache.solr.upgrade.DockerRunner.SOLR_PORT;

public abstract class AbstractRunner {
  static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  public static final String WORK_DIR = "/work";
  public static final String SOLR_URI_INDICATING_AVAILABILITY = "http://localhost:8983/solr/#/~logging";
  public static final String SOLR_STARTUP_LOG_MESSAGE = "Started Solr server";
  public static final int REQUIRED_SUCCESSFUL_REQUESTS = 8;
  public static final int MAX_SOLR_STARTUP_TIME_SEC = 30;
  public static final int SOLR_POLL_DELAY_MILLIS = 500;
  final String namePrefix;
  DockerClient docker;
  static int containerCounter;

  public AbstractRunner(DockerRunner.Context context) {
    this.namePrefix = context.namePrefix;
    docker = context.getDocker();
  }

  public static Path createTempDirQuick(String prefix) {
    try {
      return createTempDir(prefix);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

  }

  ContainerConfig getSolrContainerConfig(HostConfig.Builder hostConfig) {
    return baseContainerConfig(hostConfig.build())
        .exposedPorts(SOLR_PORT)
        .labels(ImmutableMap.of(DockerRunner.DOCKER_LABEL, ""))
        .env("SOLR_LOGS_DIR", "/solr-logs")
        .cmd(DockerRunner.NEVER_ENDING_COMMAND)
        .build();
  }

  ContainerConfig.Builder baseContainerConfig(HostConfig hostConfig) {
    return ContainerConfig.builder()
        .hostConfig(hostConfig)
        .image(DockerRunner.IMAGE_SOLR_ALL);
  }

  ContainerCreation newContainer(ContainerConfig container) {
    try {
      return docker.createContainer(container, namePrefix + containerCounter++);
    } catch (DockerException | InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  void startSolr(DockerCommandExecutor executor, SolrArgsBuilder baseStartArgs, String logFilePath) {
    doStart(executor, baseStartArgs);
    waitSolrUp(executor, logFilePath);
  }

  void doStart(DockerCommandExecutor executor, SolrArgsBuilder baseStartArgs) {
    String[] solrStart = baseStartArgs.build();
    DockerCommandExecutor.ExecutionResult result = executor.execute(solrStart);
    boolean isHappy = result.getStdout().contains(SOLR_STARTUP_LOG_MESSAGE);
    if (!isHappy) {
      throw new RuntimeException(SOLR_STARTUP_LOG_MESSAGE + "is not found in standard output");
    }
  }

  public void waitSolrUp(DockerCommandExecutor commandExecutor, String logFilePath) {
    try {
      awaitSolr();
    } finally {
      commandExecutor.execute("tail", "-n200", logFilePath);
    }
  }

  public void awaitSolr() {
    int countDown = REQUIRED_SUCCESSFUL_REQUESTS;
    long before = System.nanoTime();
    while (countDown > 0) {
      try {
        CloseableHttpClient httpClient = HttpClientBuilder.create().build();
        HttpGet pingRequest = new HttpGet(SOLR_URI_INDICATING_AVAILABILITY);
        CloseableHttpResponse res = httpClient.execute(pingRequest);
        int status = res.getStatusLine().getStatusCode();
        if (status == HttpStatus.SC_OK) {
          LOG.info("Solr is available, successful checks left: " + --countDown);
        } else if (elapsedSecondsSince(before) > MAX_SOLR_STARTUP_TIME_SEC)
          throw new RuntimeException("Solr cannot be started (status code of start page: " + status + ")");
        LOG.info("Solr ping not yet successful, status code: {}", status);
      } catch (IOException | SolrException e) {
        if (elapsedSecondsSince(before) > MAX_SOLR_STARTUP_TIME_SEC)
          throw new RuntimeException("Solr cannot be started", e);
        LOG.info("Solr ping not yet successful: {}", e.getMessage());
      }
      sleep(SOLR_POLL_DELAY_MILLIS);
    }
    LOG.info("Solr ping was successful {} times, considered running", REQUIRED_SUCCESSFUL_REQUESTS);
  }

  long elapsedSecondsSince(long before) {
    return SECONDS.convert(System.nanoTime() - before, NANOSECONDS);
  }

  void sleep(int millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  static Path createTempDir(String prefix) throws IOException {
    return Files.createTempDirectory(prefix).toRealPath().toAbsolutePath();
  }

  String runInContainer(ContainerConfig container) {
    try {
      ContainerCreation creation = newContainer(container);
      final String id = creation.id();
      docker.startContainer(id);
      return id;
    } catch (DockerException | InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  void startSolr4(DockerCommandExecutor executor) {
    SolrArgsBuilder baseStartArgs = new SolrArgsBuilder(SOLR_FROM).start().port(SOLR_PORT).coresDirWithExampleConfig(CORES_SUB_DIR);
    doStart(executor, baseStartArgs);
    waitSolrUp(executor, SOLR_FROM + "/example/logs/solr.log");
  }


  public void stopSolr4(DockerCommandExecutor executor) {
    DockerCommandExecutor.ExecutionResult stop = executor.execute(new SolrArgsBuilder(SOLR_FROM).stop().allInstances().build());
  }
}
