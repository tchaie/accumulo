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
package org.apache.accumulo.core.client.mapred;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;

import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.mapred.JobConf;
import org.junit.jupiter.api.Test;

@Deprecated(since = "2.0.0")
public class AccumuloOutputFormatTest {

  @Test
  public void testBWSettings() throws IOException {
    JobConf job = new JobConf();

    // make sure we aren't testing defaults
    final BatchWriterConfig bwDefaults = new BatchWriterConfig();
    assertNotEquals(7654321L, bwDefaults.getMaxLatency(MILLISECONDS));
    assertNotEquals(9898989L, bwDefaults.getTimeout(MILLISECONDS));
    assertNotEquals(42, bwDefaults.getMaxWriteThreads());
    assertNotEquals(1123581321L, bwDefaults.getMaxMemory());

    final BatchWriterConfig bwConfig = new BatchWriterConfig();
    bwConfig.setMaxLatency(7654321L, MILLISECONDS);
    bwConfig.setTimeout(9898989L, MILLISECONDS);
    bwConfig.setMaxWriteThreads(42);
    bwConfig.setMaxMemory(1123581321L);
    AccumuloOutputFormat.setBatchWriterOptions(job, bwConfig);

    AccumuloOutputFormat myAOF = new AccumuloOutputFormat() {
      @Override
      public void checkOutputSpecs(FileSystem ignored, JobConf job) {
        BatchWriterConfig bwOpts = getBatchWriterOptions(job);

        // passive check
        assertEquals(bwConfig.getMaxLatency(MILLISECONDS), bwOpts.getMaxLatency(MILLISECONDS));
        assertEquals(bwConfig.getTimeout(MILLISECONDS), bwOpts.getTimeout(MILLISECONDS));
        assertEquals(bwConfig.getMaxWriteThreads(), bwOpts.getMaxWriteThreads());
        assertEquals(bwConfig.getMaxMemory(), bwOpts.getMaxMemory());

        // explicit check
        assertEquals(7654321L, bwOpts.getMaxLatency(MILLISECONDS));
        assertEquals(9898989L, bwOpts.getTimeout(MILLISECONDS));
        assertEquals(42, bwOpts.getMaxWriteThreads());
        assertEquals(1123581321L, bwOpts.getMaxMemory());

      }
    };
    myAOF.checkOutputSpecs(null, job);
  }

}
