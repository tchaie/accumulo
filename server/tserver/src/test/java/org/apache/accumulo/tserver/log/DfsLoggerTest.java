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
package org.apache.accumulo.tserver.log;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.apache.accumulo.core.client.Durability;
import org.apache.accumulo.tserver.TabletMutations;
import org.apache.accumulo.tserver.tablet.CommitSession;
import org.easymock.EasyMock;
import org.junit.jupiter.api.Test;

public class DfsLoggerTest {

  @Test
  public void testDurabilityForGroupCommit() {
    List<TabletMutations> lst = new ArrayList<>();
    CommitSession commitSession = EasyMock.createMock(CommitSession.class);
    assertEquals(Durability.NONE, chooseDurabilityForGroupCommit(lst));
    TabletMutations m1 =
        new TabletMutations(commitSession, Collections.emptyList(), Durability.NONE);
    lst.add(m1);
    assertEquals(Durability.NONE, chooseDurabilityForGroupCommit(lst));
    TabletMutations m2 =
        new TabletMutations(commitSession, Collections.emptyList(), Durability.LOG);
    lst.add(m2);
    assertEquals(Durability.LOG, chooseDurabilityForGroupCommit(lst));
    TabletMutations m3 =
        new TabletMutations(commitSession, Collections.emptyList(), Durability.NONE);
    lst.add(m3);
    assertEquals(Durability.LOG, chooseDurabilityForGroupCommit(lst));
    TabletMutations m4 =
        new TabletMutations(commitSession, Collections.emptyList(), Durability.FLUSH);
    lst.add(m4);
    assertEquals(Durability.FLUSH, chooseDurabilityForGroupCommit(lst));
    TabletMutations m5 =
        new TabletMutations(commitSession, Collections.emptyList(), Durability.LOG);
    lst.add(m5);
    assertEquals(Durability.FLUSH, chooseDurabilityForGroupCommit(lst));
    TabletMutations m6 =
        new TabletMutations(commitSession, Collections.emptyList(), Durability.SYNC);
    lst.add(m6);
    assertEquals(Durability.SYNC, chooseDurabilityForGroupCommit(lst));
    TabletMutations m7 =
        new TabletMutations(commitSession, Collections.emptyList(), Durability.FLUSH);
    lst.add(m7);
    assertEquals(Durability.SYNC, chooseDurabilityForGroupCommit(lst));
  }

  static Durability chooseDurabilityForGroupCommit(Collection<TabletMutations> mutations) {
    Durability result = Durability.NONE;
    for (TabletMutations tabletMutations : mutations) {
      result = DfsLogger.maxDurability(tabletMutations.getDurability(), result);
    }
    return result;
  }

}
