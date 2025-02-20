/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.test.functional;

import static org.apache.accumulo.fate.util.UtilWaitThread.sleepUninterruptibly;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.metadata.MetadataTable;
import org.apache.accumulo.core.metadata.schema.Ample;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.DeletesSection;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.DeletesSection.SkewedKeyValue;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.TablePermission;
import org.apache.accumulo.core.util.ServerServices;
import org.apache.accumulo.core.util.ServerServices.Service;
import org.apache.accumulo.fate.zookeeper.ServiceLock;
import org.apache.accumulo.fate.zookeeper.ZooReaderWriter;
import org.apache.accumulo.fate.zookeeper.ZooUtil;
import org.apache.accumulo.gc.SimpleGarbageCollector;
import org.apache.accumulo.minicluster.MemoryUnit;
import org.apache.accumulo.minicluster.ServerType;
import org.apache.accumulo.miniclusterImpl.MiniAccumuloClusterImpl.ProcessInfo;
import org.apache.accumulo.miniclusterImpl.MiniAccumuloConfigImpl;
import org.apache.accumulo.miniclusterImpl.ProcessNotFoundException;
import org.apache.accumulo.miniclusterImpl.ProcessReference;
import org.apache.accumulo.test.TestIngest;
import org.apache.accumulo.test.VerifyIngest;
import org.apache.accumulo.test.VerifyIngest.VerifyParams;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RawLocalFileSystem;
import org.apache.hadoop.io.Text;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.junit.jupiter.api.Test;

import com.google.common.collect.Iterators;

public class GarbageCollectorIT extends ConfigurableMacBase {
  private static final String OUR_SECRET = "itsreallysecret";

  @Override
  protected Duration defaultTimeout() {
    return Duration.ofMinutes(5);
  }

  @Override
  public void configure(MiniAccumuloConfigImpl cfg, Configuration hadoopCoreSite) {
    cfg.setProperty(Property.INSTANCE_ZK_TIMEOUT, "15s");
    cfg.setProperty(Property.INSTANCE_SECRET, OUR_SECRET);
    cfg.setProperty(Property.GC_CYCLE_START, "1");
    cfg.setProperty(Property.GC_CYCLE_DELAY, "1");
    cfg.setProperty(Property.GC_PORT, "0");
    cfg.setProperty(Property.TSERV_MAXMEM, "5K");
    cfg.setProperty(Property.TSERV_MAJC_DELAY, "1");
    // reduce the batch size significantly in order to cause the integration tests to have
    // to process many batches of deletion candidates.
    cfg.setProperty(Property.GC_CANDIDATE_BATCH_SIZE, "256K");

    // use raw local file system so walogs sync and flush will work
    hadoopCoreSite.set("fs.file.impl", RawLocalFileSystem.class.getName());
  }

  private void killMacGc() throws ProcessNotFoundException, InterruptedException, KeeperException {
    // kill gc started by MAC
    getCluster().killProcess(ServerType.GARBAGE_COLLECTOR,
        getCluster().getProcesses().get(ServerType.GARBAGE_COLLECTOR).iterator().next());
    // delete lock in zookeeper if there, this will allow next GC to start quickly
    var path = ServiceLock.path(getServerContext().getZooKeeperRoot() + Constants.ZGC_LOCK);
    ZooReaderWriter zk = getServerContext().getZooReaderWriter();
    try {
      ServiceLock.deleteLock(zk, path);
    } catch (IllegalStateException e) {
      log.error("Unable to delete ZooLock for mini accumulo-gc", e);
    }

    assertNull(getCluster().getProcesses().get(ServerType.GARBAGE_COLLECTOR));
  }

  @Test
  public void gcTest() throws Exception {
    killMacGc();
    final String table = "test_ingest";
    try (AccumuloClient c = Accumulo.newClient().from(getClientProperties()).build()) {
      c.tableOperations().create(table);
      c.tableOperations().setProperty(table, Property.TABLE_SPLIT_THRESHOLD.getKey(), "5K");
      VerifyParams params = new VerifyParams(getClientProperties(), table, 10_000);
      params.cols = 1;
      TestIngest.ingest(c, cluster.getFileSystem(), params);
      c.tableOperations().compact(table, null, null, true, true);
      int before = countFiles();
      while (true) {
        sleepUninterruptibly(1, TimeUnit.SECONDS);
        int more = countFiles();
        if (more <= before)
          break;
        before = more;
      }

      // restart GC
      getCluster().start();
      sleepUninterruptibly(15, TimeUnit.SECONDS);
      int after = countFiles();
      VerifyIngest.verifyIngest(c, params);
      assertTrue(after < before);
    }
  }

  @Test
  public void gcLotsOfCandidatesIT() throws Exception {
    killMacGc();

    log.info("Filling metadata table with bogus delete flags");
    try (AccumuloClient c = Accumulo.newClient().from(getClientProperties()).build()) {
      addEntries(c);
      cluster.getConfig().setDefaultMemory(32, MemoryUnit.MEGABYTE);
      ProcessInfo gc = cluster.exec(SimpleGarbageCollector.class);
      sleepUninterruptibly(20, TimeUnit.SECONDS);
      String output = "";
      while (!output.contains("has exceeded the threshold")) {
        try {
          output = gc.readStdOut();
        } catch (UncheckedIOException ex) {
          log.error("IO error reading the IT's accumulo-gc STDOUT", ex);
          break;
        }
      }
      gc.getProcess().destroy();
      assertTrue(output.contains("has exceeded the threshold"));
    }
  }

  @Test
  public void dontGCRootLog() throws Exception {
    killMacGc();
    // dirty metadata
    try (AccumuloClient c = Accumulo.newClient().from(getClientProperties()).build()) {
      String table = getUniqueNames(1)[0];
      c.tableOperations().create(table);
      // let gc run for a bit
      cluster.start();
      sleepUninterruptibly(20, TimeUnit.SECONDS);
      killMacGc();
      // kill tservers
      for (ProcessReference ref : cluster.getProcesses().get(ServerType.TABLET_SERVER)) {
        cluster.killProcess(ServerType.TABLET_SERVER, ref);
      }
      // run recovery
      cluster.start();
      // did it recover?
      try (Scanner scanner = c.createScanner(MetadataTable.NAME, Authorizations.EMPTY)) {
        scanner.forEach((k, v) -> {});
      }
    }
  }

  private Mutation createDelMutation(String path, String cf, String cq, String val) {
    Text row = new Text(DeletesSection.encodeRow(path));
    Mutation delFlag = new Mutation(row);
    delFlag.put(cf, cq, val);
    return delFlag;
  }

  @Test
  public void testInvalidDelete() throws Exception {
    killMacGc();
    try (AccumuloClient c = Accumulo.newClient().from(getClientProperties()).build()) {
      String table = getUniqueNames(1)[0];
      c.tableOperations().create(table);

      try (BatchWriter bw = c.createBatchWriter(table)) {
        Mutation m1 = new Mutation("r1");
        m1.put("cf1", "cq1", "v1");
        bw.addMutation(m1);
      }

      c.tableOperations().flush(table, null, null, true);

      // ensure an invalid delete entry does not cause GC to go berserk ACCUMULO-2520
      c.securityOperations().grantTablePermission(c.whoami(), MetadataTable.NAME,
          TablePermission.WRITE);
      try (BatchWriter bw = c.createBatchWriter(MetadataTable.NAME)) {
        bw.addMutation(createDelMutation("", "", "", ""));
        bw.addMutation(createDelMutation("", "testDel", "test", "valueTest"));
        // path is invalid but value is expected - only way the invalid entry will come through
        // processing and
        // show up to produce error in output to allow while loop to end
        bw.addMutation(createDelMutation("/", "", "", SkewedKeyValue.STR_NAME));
      }

      ProcessInfo gc = cluster.exec(SimpleGarbageCollector.class);
      try {
        String output = "";
        while (!output.contains("Ignoring invalid deletion candidate")) {
          sleepUninterruptibly(250, TimeUnit.MILLISECONDS);
          try {
            output = gc.readStdOut();
          } catch (UncheckedIOException ioe) {
            log.error("Could not read all from cluster.", ioe);
          }
        }
      } finally {
        gc.getProcess().destroy();
      }

      try (Scanner scanner = c.createScanner(table, Authorizations.EMPTY)) {
        Entry<Key,Value> entry = getOnlyElement(scanner);
        assertEquals("r1", entry.getKey().getRow().toString());
        assertEquals("cf1", entry.getKey().getColumnFamily().toString());
        assertEquals("cq1", entry.getKey().getColumnQualifier().toString());
        assertEquals("v1", entry.getValue().toString());
      }
    }
  }

  @Test
  public void testProperPortAdvertisement() throws Exception {

    try (AccumuloClient client = Accumulo.newClient().from(getClientProperties()).build()) {

      ZooReaderWriter zk = cluster.getServerContext().getZooReaderWriter();
      var path = ServiceLock
          .path(ZooUtil.getRoot(client.instanceOperations().getInstanceId()) + Constants.ZGC_LOCK);
      for (int i = 0; i < 5; i++) {
        List<String> locks;
        try {
          locks = ServiceLock.validateAndSort(path, zk.getChildren(path.toString()));
        } catch (NoNodeException e) {
          Thread.sleep(5000);
          continue;
        }

        if (locks != null && !locks.isEmpty()) {
          String lockPath = path + "/" + locks.get(0);

          String gcLoc = new String(zk.getData(lockPath));

          assertTrue(gcLoc.startsWith(Service.GC_CLIENT.name()),
              "Found unexpected data in zookeeper for GC location: " + gcLoc);
          int loc = gcLoc.indexOf(ServerServices.SEPARATOR_CHAR);
          assertNotEquals(-1, loc, "Could not find split point of GC location for: " + gcLoc);
          String addr = gcLoc.substring(loc + 1);

          int addrSplit = addr.indexOf(':');
          assertNotEquals(-1, addrSplit, "Could not find split of GC host:port for: " + addr);

          String host = addr.substring(0, addrSplit), port = addr.substring(addrSplit + 1);
          // We shouldn't have the "bindall" address in zk
          assertNotEquals("0.0.0.0", host);
          // Nor should we have the "random port" in zk
          assertNotEquals(0, Integer.parseInt(port));
          return;
        }

        Thread.sleep(5000);
      }

      fail("Could not find advertised GC address");
    }
  }

  private int countFiles() throws Exception {
    Path path = new Path(cluster.getConfig().getDir() + "/accumulo/tables/1/*/*.rf");
    return Iterators.size(Arrays.asList(cluster.getFileSystem().globStatus(path)).iterator());
  }

  private void addEntries(AccumuloClient client) throws Exception {
    Ample ample = getServerContext().getAmple();
    client.securityOperations().grantTablePermission(client.whoami(), MetadataTable.NAME,
        TablePermission.WRITE);
    try (BatchWriter bw = client.createBatchWriter(MetadataTable.NAME)) {
      for (int i = 0; i < 100000; ++i) {
        String longpath = "aaaaaaaaaabbbbbbbbbbccccccccccddddddddddeeeeeeeeee"
            + "ffffffffffgggggggggghhhhhhhhhhiiiiiiiiiijjjjjjjjjj";
        Mutation delFlag = ample.createDeleteMutation(String.format("file:/%020d/%s", i, longpath));
        bw.addMutation(delFlag);
      }
    }
  }
}
