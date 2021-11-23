/*
 * Tencent is pleased to support the open source community by making
 * Firestorm-Spark remote shuffle server available.
 * Copyright (C) 2021 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * https://opensource.org/licenses/Apache-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.tencent.rss.test;

import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Uninterruptibles;
import com.tencent.rss.client.request.RssGetShuffleAssignmentsRequest;
import com.tencent.rss.client.response.ResponseStatusCode;
import com.tencent.rss.client.response.RssGetShuffleAssignmentsResponse;
import com.tencent.rss.common.util.Constants;
import com.tencent.rss.coordinator.CoordinatorConf;
import com.tencent.rss.coordinator.ServerNode;
import com.tencent.rss.server.ShuffleServerConf;
import com.tencent.rss.storage.util.StorageType;

import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.io.Files;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HealthCheckCoordinatorGrpcTest extends CoordinatorTestBase  {

  private static File serverTmpDir = Files.createTempDir();
  private static File tempDataFile = new File(serverTmpDir, "data");
  private static int writeDataSize;

  @BeforeClass
  public static void setupServers() throws Exception {
    serverTmpDir.deleteOnExit();
    long totalSize = serverTmpDir.getTotalSpace();
    long usedSize = serverTmpDir.getTotalSpace() - serverTmpDir.getUsableSpace();
    File data1 = new File(serverTmpDir, "data1");
    data1.mkdirs();
    File data2 = new File(serverTmpDir, "data2");
    data2.mkdirs();
    long freeSize = serverTmpDir.getUsableSpace();
    double maxUsage;
    double healthUsage;
    if (freeSize > 400 * 1024 * 1024) {
      writeDataSize = 200 * 1024 * 1024;
    } else {
      writeDataSize = (int) freeSize / 2;
    }
    maxUsage = (writeDataSize * 0.75 + usedSize) * 100.0 / totalSize;
    healthUsage = (writeDataSize * 0.5 + usedSize) * 100.0 /totalSize;
    CoordinatorConf coordinatorConf = getCoordinatorConf();
    coordinatorConf.setLong(CoordinatorConf.COORDINATOR_APP_EXPIRED, 2000);
    coordinatorConf.setLong(CoordinatorConf.COORDINATOR_HEARTBEAT_TIMEOUT, 3000);
    createCoordinatorServer(coordinatorConf);
    ShuffleServerConf shuffleServerConf = getShuffleServerConf();
    shuffleServerConf.setBoolean(ShuffleServerConf.HEALTH_CHECK_ENABLE, true);
    shuffleServerConf.setString(ShuffleServerConf.RSS_STORAGE_TYPE, StorageType.LOCALFILE.name());
    shuffleServerConf.setString(ShuffleServerConf.RSS_STORAGE_BASE_PATH, data1.getAbsolutePath());
    shuffleServerConf.setDouble(ShuffleServerConf.HEALTH_STORAGE_RECOVERY_USAGE_PERCENTAGE, healthUsage);
    shuffleServerConf.setDouble(ShuffleServerConf.HEALTH_STORAGE_MAX_USAGE_PERCENTAGE, maxUsage);
    shuffleServerConf.setLong(ShuffleServerConf.HEALTH_CHECK_INTERVAL, 1000L);
    createShuffleServer(shuffleServerConf);
    shuffleServerConf.setInteger(ShuffleServerConf.RPC_SERVER_PORT, SHUFFLE_SERVER_PORT + 1);
    shuffleServerConf.setInteger(ShuffleServerConf.JETTY_HTTP_PORT, 18081);
    shuffleServerConf.setString(ShuffleServerConf.RSS_STORAGE_TYPE, StorageType.LOCALFILE.name());
    shuffleServerConf.setString(ShuffleServerConf.RSS_STORAGE_BASE_PATH, data2.getAbsolutePath());
    shuffleServerConf.setDouble(ShuffleServerConf.HEALTH_STORAGE_RECOVERY_USAGE_PERCENTAGE, healthUsage);
    shuffleServerConf.setDouble(ShuffleServerConf.HEALTH_STORAGE_MAX_USAGE_PERCENTAGE, maxUsage);
    shuffleServerConf.setLong(ShuffleServerConf.HEALTH_CHECK_INTERVAL, 1000L);
    shuffleServerConf.setBoolean(ShuffleServerConf.HEALTH_CHECK_ENABLE, true);
    createShuffleServer(shuffleServerConf);
    startServers();
  }

  @Test
  public void healthCheckTest() throws Exception {
    RssGetShuffleAssignmentsRequest request =
      new RssGetShuffleAssignmentsRequest(
          "1",
          1,
          1,
          1,
          1,
          Sets.newHashSet(Constants.SHUFFLE_SERVER_VERSION));
    Uninterruptibles.sleepUninterruptibly(3, TimeUnit.SECONDS);
    assertEquals(2, coordinatorClient.getShuffleServerList().getServersCount());
    List<ServerNode> nodes  = coordinators.get(0).getClusterManager()
        .getServerList(Sets.newHashSet(Constants.SHUFFLE_SERVER_VERSION));
    assertEquals(2, coordinatorClient.getShuffleServerList().getServersCount());
    assertEquals(2, nodes.size());
    RssGetShuffleAssignmentsResponse response =
        coordinatorClient.getShuffleAssignments(request);
    assertFalse(response.getPartitionToServers().isEmpty());
    for (ServerNode node : nodes) {
      assertTrue(node.isHealthy());
    }
    byte[] bytes = new byte[writeDataSize];
    new Random().nextBytes(bytes);
    try (FileOutputStream out = new FileOutputStream(tempDataFile)) {
      out.write(bytes);
    }
    Uninterruptibles.sleepUninterruptibly(3, TimeUnit.SECONDS);
    nodes  = coordinators.get(0).getClusterManager()
        .getServerList(Sets.newHashSet(Constants.SHUFFLE_SERVER_VERSION));
    for (ServerNode node : nodes) {
      assertFalse(node.isHealthy());
    }
    assertEquals(0, nodes.size());
    response = coordinatorClient.getShuffleAssignments(request);
    assertEquals(ResponseStatusCode.INTERNAL_ERROR, response.getStatusCode());

    tempDataFile.delete();
    Uninterruptibles.sleepUninterruptibly(3, TimeUnit.SECONDS);
    nodes  = coordinators.get(0).getClusterManager()
        .getServerList(Sets.newHashSet(Constants.SHUFFLE_SERVER_VERSION));
    for (ServerNode node : nodes) {
      assertTrue(node.isHealthy());
    }
    assertEquals(2, nodes.size());
    response =
        coordinatorClient.getShuffleAssignments(request);
    assertFalse(response.getPartitionToServers().isEmpty());
  }
}
