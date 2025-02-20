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
package org.apache.accumulo.core.clientImpl;

import static org.easymock.EasyMock.replay;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.accumulo.core.clientImpl.TabletLocator.TabletLocation;
import org.apache.accumulo.core.clientImpl.TabletLocator.TabletLocations;
import org.apache.accumulo.core.clientImpl.TabletLocator.TabletServerMutations;
import org.apache.accumulo.core.clientImpl.TabletLocatorImpl.TabletLocationObtainer;
import org.apache.accumulo.core.clientImpl.TabletLocatorImpl.TabletServerLockChecker;
import org.apache.accumulo.core.data.InstanceId;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.PartialKey;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.TableId;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.dataImpl.KeyExtent;
import org.apache.accumulo.core.metadata.MetadataLocationObtainer;
import org.apache.accumulo.core.metadata.MetadataTable;
import org.apache.accumulo.core.metadata.RootTable;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.CurrentLocationColumnFamily;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.TabletColumnFamily;
import org.apache.hadoop.io.Text;
import org.easymock.EasyMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TabletLocatorImplTest {

  private static final KeyExtent RTE = RootTable.EXTENT;
  private static final KeyExtent MTE = new KeyExtent(MetadataTable.ID, null, RTE.endRow());

  static KeyExtent nke(String t, String er, String per) {
    return new KeyExtent(TableId.of(t), er == null ? null : new Text(er),
        per == null ? null : new Text(per));
  }

  static Range nr(String k1, boolean si, String k2, boolean ei) {
    return new Range(k1 == null ? null : new Text(k1), si, k2 == null ? null : new Text(k2), ei);
  }

  static Range nr(String k1, String k2) {
    return new Range(k1 == null ? null : new Text(k1), k2 == null ? null : new Text(k2));
  }

  static List<Range> nrl(Range... ranges) {
    return Arrays.asList(ranges);
  }

  static Object[] nol(Object... objs) {
    return objs;
  }

  @SuppressWarnings("unchecked")
  static Map<String,Map<KeyExtent,List<Range>>> createExpectedBinnings(Object... data) {

    Map<String,Map<KeyExtent,List<Range>>> expBinnedRanges = new HashMap<>();

    for (int i = 0; i < data.length; i += 2) {
      String loc = (String) data[i];
      Object[] binData = (Object[]) data[i + 1];

      HashMap<KeyExtent,List<Range>> binnedKE = new HashMap<>();

      expBinnedRanges.put(loc, binnedKE);

      for (int j = 0; j < binData.length; j += 2) {
        KeyExtent ke = (KeyExtent) binData[j];
        List<Range> ranges = (List<Range>) binData[j + 1];

        binnedKE.put(ke, ranges);
      }
    }

    return expBinnedRanges;
  }

  static TreeMap<KeyExtent,TabletLocation> createMetaCacheKE(Object... data) {
    TreeMap<KeyExtent,TabletLocation> mcke = new TreeMap<>();

    for (int i = 0; i < data.length; i += 2) {
      KeyExtent ke = (KeyExtent) data[i];
      String loc = (String) data[i + 1];
      mcke.put(ke, new TabletLocation(ke, loc, "1"));
    }

    return mcke;
  }

  static TreeMap<Text,TabletLocation> createMetaCache(Object... data) {
    TreeMap<KeyExtent,TabletLocation> mcke = createMetaCacheKE(data);

    TreeMap<Text,TabletLocation> mc = new TreeMap<>(TabletLocatorImpl.END_ROW_COMPARATOR);

    for (Entry<KeyExtent,TabletLocation> entry : mcke.entrySet()) {
      if (entry.getKey().endRow() == null)
        mc.put(TabletLocatorImpl.MAX_TEXT, entry.getValue());
      else
        mc.put(entry.getKey().endRow(), entry.getValue());
    }

    return mc;
  }

  static TabletLocatorImpl createLocators(TServers tservers, String rootTabLoc, String metaTabLoc,
      String table, TabletServerLockChecker tslc, Object... data) {

    TreeMap<KeyExtent,TabletLocation> mcke = createMetaCacheKE(data);

    TestTabletLocationObtainer ttlo = new TestTabletLocationObtainer(tservers);

    RootTabletLocator rtl = new TestRootTabletLocator();
    TabletLocatorImpl rootTabletCache =
        new TabletLocatorImpl(MetadataTable.ID, rtl, ttlo, new YesLockChecker());
    TabletLocatorImpl tab1TabletCache =
        new TabletLocatorImpl(TableId.of(table), rootTabletCache, ttlo, tslc);

    setLocation(tservers, rootTabLoc, RTE, MTE, metaTabLoc);

    for (Entry<KeyExtent,TabletLocation> entry : mcke.entrySet()) {
      setLocation(tservers, metaTabLoc, MTE, entry.getKey(), entry.getValue().tablet_location);
    }

    return tab1TabletCache;

  }

  static TabletLocatorImpl createLocators(TServers tservers, String rootTabLoc, String metaTabLoc,
      String table, Object... data) {
    return createLocators(tservers, rootTabLoc, metaTabLoc, table, new YesLockChecker(), data);
  }

  static TabletLocatorImpl createLocators(String table, Object... data) {
    TServers tservers = new TServers();
    return createLocators(tservers, "tserver1", "tserver2", table, data);
  }

  private ClientContext context;
  private InstanceId iid;

  @BeforeEach
  public void setUp() {
    context = EasyMock.createMock(ClientContext.class);
    iid = InstanceId.of("instance1");
    EasyMock.expect(context.getRootTabletLocation()).andReturn("tserver1").anyTimes();
    EasyMock.expect(context.getInstanceID()).andReturn(iid).anyTimes();
    replay(context);
  }

  private void runTest(List<Range> ranges, TabletLocatorImpl tab1TabletCache,
      Map<String,Map<KeyExtent,List<Range>>> expected) throws Exception {
    List<Range> failures = Collections.emptyList();
    runTest(ranges, tab1TabletCache, expected, failures);
  }

  private void runTest(List<Range> ranges, TabletLocatorImpl tab1TabletCache,
      Map<String,Map<KeyExtent,List<Range>>> expected, List<Range> efailures) throws Exception {

    Map<String,Map<KeyExtent,List<Range>>> binnedRanges = new HashMap<>();
    List<Range> f = tab1TabletCache.binRanges(context, ranges, binnedRanges);
    assertEquals(expected, binnedRanges);

    HashSet<Range> f1 = new HashSet<>(f);
    HashSet<Range> f2 = new HashSet<>(efailures);

    assertEquals(f2, f1);
  }

  static Set<KeyExtent> nkes(KeyExtent... extents) {
    HashSet<KeyExtent> kes = new HashSet<>();

    Collections.addAll(kes, extents);

    return kes;
  }

  static void runTest(TreeMap<Text,TabletLocation> mc, KeyExtent remove, Set<KeyExtent> expected) {
    // copy so same metaCache can be used for multiple test

    mc = new TreeMap<>(mc);

    TabletLocatorImpl.removeOverlapping(mc, remove);

    HashSet<KeyExtent> eic = new HashSet<>();
    for (TabletLocation tl : mc.values()) {
      eic.add(tl.tablet_extent);
    }

    assertEquals(expected, eic);
  }

  static Mutation nm(String row, String... data) {
    Mutation mut = new Mutation(new Text(row));

    for (String element : data) {
      String[] cvp = element.split("=");
      String[] cols = cvp[0].split(":");

      mut.put(cols[0], cols[1], cvp[1]);
    }

    return mut;
  }

  static List<Mutation> nml(Mutation... ma) {
    return Arrays.asList(ma);
  }

  private void runTest(TabletLocatorImpl metaCache, List<Mutation> ml,
      Map<String,Map<KeyExtent,List<String>>> emb, String... efailures) throws Exception {
    Map<String,TabletServerMutations<Mutation>> binnedMutations = new HashMap<>();
    List<Mutation> afailures = new ArrayList<>();
    metaCache.binMutations(context, ml, binnedMutations, afailures);

    verify(emb, binnedMutations);

    ArrayList<String> afs = new ArrayList<>();
    ArrayList<String> efs = new ArrayList<>(Arrays.asList(efailures));

    for (Mutation mutation : afailures) {
      afs.add(new String(mutation.getRow()));
    }

    Collections.sort(afs);
    Collections.sort(efs);

    assertEquals(efs, afs);

  }

  private void verify(Map<String,Map<KeyExtent,List<String>>> expected,
      Map<String,TabletServerMutations<Mutation>> actual) {
    assertEquals(expected.keySet(), actual.keySet());

    for (String server : actual.keySet()) {
      TabletServerMutations<Mutation> atb = actual.get(server);
      Map<KeyExtent,List<String>> etb = expected.get(server);

      assertEquals(etb.keySet(), atb.getMutations().keySet());

      for (KeyExtent ke : etb.keySet()) {
        ArrayList<String> eRows = new ArrayList<>(etb.get(ke));
        ArrayList<String> aRows = new ArrayList<>();

        for (Mutation m : atb.getMutations().get(ke)) {
          aRows.add(new String(m.getRow()));
        }

        Collections.sort(eRows);
        Collections.sort(aRows);

        assertEquals(eRows, aRows);
      }
    }

  }

  static Map<String,Map<KeyExtent,List<String>>> cemb(Object[]... ols) {

    Map<String,Map<KeyExtent,List<String>>> emb = new HashMap<>();

    for (Object[] ol : ols) {
      String row = (String) ol[0];
      String server = (String) ol[1];
      KeyExtent ke = (KeyExtent) ol[2];
      emb.computeIfAbsent(server, k -> new HashMap<>()).computeIfAbsent(ke, k -> new ArrayList<>())
          .add(row);
    }

    return emb;
  }

  @Test
  public void testRemoveOverlapping1() {
    TreeMap<Text,TabletLocation> mc = createMetaCache(nke("0", null, null), "l1");

    runTest(mc, nke("0", "a", null), nkes());
    runTest(mc, nke("0", null, null), nkes());
    runTest(mc, nke("0", null, "a"), nkes());

    mc = createMetaCache(nke("0", "g", null), "l1", nke("0", "r", "g"), "l1", nke("0", null, "r"),
        "l1");
    runTest(mc, nke("0", null, null), nkes());

    runTest(mc, nke("0", "a", null), nkes(nke("0", "r", "g"), nke("0", null, "r")));
    runTest(mc, nke("0", "g", null), nkes(nke("0", "r", "g"), nke("0", null, "r")));
    runTest(mc, nke("0", "h", null), nkes(nke("0", null, "r")));
    runTest(mc, nke("0", "r", null), nkes(nke("0", null, "r")));
    runTest(mc, nke("0", "s", null), nkes());

    runTest(mc, nke("0", "b", "a"), nkes(nke("0", "r", "g"), nke("0", null, "r")));
    runTest(mc, nke("0", "g", "a"), nkes(nke("0", "r", "g"), nke("0", null, "r")));
    runTest(mc, nke("0", "h", "a"), nkes(nke("0", null, "r")));
    runTest(mc, nke("0", "r", "a"), nkes(nke("0", null, "r")));
    runTest(mc, nke("0", "s", "a"), nkes());

    runTest(mc, nke("0", "h", "g"), nkes(nke("0", "g", null), nke("0", null, "r")));
    runTest(mc, nke("0", "r", "g"), nkes(nke("0", "g", null), nke("0", null, "r")));
    runTest(mc, nke("0", "s", "g"), nkes(nke("0", "g", null)));

    runTest(mc, nke("0", "i", "h"), nkes(nke("0", "g", null), nke("0", null, "r")));
    runTest(mc, nke("0", "r", "h"), nkes(nke("0", "g", null), nke("0", null, "r")));
    runTest(mc, nke("0", "s", "h"), nkes(nke("0", "g", null)));

    runTest(mc, nke("0", "z", "f"), nkes());
    runTest(mc, nke("0", "z", "g"), nkes(nke("0", "g", null)));
    runTest(mc, nke("0", "z", "q"), nkes(nke("0", "g", null)));
    runTest(mc, nke("0", "z", "r"), nkes(nke("0", "g", null), nke("0", "r", "g")));
    runTest(mc, nke("0", "z", "s"), nkes(nke("0", "g", null), nke("0", "r", "g")));

    runTest(mc, nke("0", null, "f"), nkes());
    runTest(mc, nke("0", null, "g"), nkes(nke("0", "g", null)));
    runTest(mc, nke("0", null, "q"), nkes(nke("0", "g", null)));
    runTest(mc, nke("0", null, "r"), nkes(nke("0", "g", null), nke("0", "r", "g")));
    runTest(mc, nke("0", null, "s"), nkes(nke("0", "g", null), nke("0", "r", "g")));

  }

  @Test
  public void testRemoveOverlapping2() {

    // test removes when cache does not contain all tablets in a table
    TreeMap<Text,TabletLocation> mc =
        createMetaCache(nke("0", "r", "g"), "l1", nke("0", null, "r"), "l1");

    runTest(mc, nke("0", "a", null), nkes(nke("0", "r", "g"), nke("0", null, "r")));
    runTest(mc, nke("0", "g", null), nkes(nke("0", "r", "g"), nke("0", null, "r")));
    runTest(mc, nke("0", "h", null), nkes(nke("0", null, "r")));
    runTest(mc, nke("0", "r", null), nkes(nke("0", null, "r")));
    runTest(mc, nke("0", "s", null), nkes());

    runTest(mc, nke("0", "b", "a"), nkes(nke("0", "r", "g"), nke("0", null, "r")));
    runTest(mc, nke("0", "g", "a"), nkes(nke("0", "r", "g"), nke("0", null, "r")));
    runTest(mc, nke("0", "h", "a"), nkes(nke("0", null, "r")));
    runTest(mc, nke("0", "r", "a"), nkes(nke("0", null, "r")));
    runTest(mc, nke("0", "s", "a"), nkes());

    runTest(mc, nke("0", "h", "g"), nkes(nke("0", null, "r")));

    mc = createMetaCache(nke("0", "g", null), "l1", nke("0", null, "r"), "l1");

    runTest(mc, nke("0", "h", "g"), nkes(nke("0", "g", null), nke("0", null, "r")));
    runTest(mc, nke("0", "h", "a"), nkes(nke("0", null, "r")));
    runTest(mc, nke("0", "s", "g"), nkes(nke("0", "g", null)));
    runTest(mc, nke("0", "s", "a"), nkes());

    mc = createMetaCache(nke("0", "g", null), "l1", nke("0", "r", "g"), "l1");

    runTest(mc, nke("0", "z", "f"), nkes());
    runTest(mc, nke("0", "z", "g"), nkes(nke("0", "g", null)));
    runTest(mc, nke("0", "z", "q"), nkes(nke("0", "g", null)));
    runTest(mc, nke("0", "z", "r"), nkes(nke("0", "g", null), nke("0", "r", "g")));
    runTest(mc, nke("0", "z", "s"), nkes(nke("0", "g", null), nke("0", "r", "g")));

    runTest(mc, nke("0", null, "f"), nkes());
    runTest(mc, nke("0", null, "g"), nkes(nke("0", "g", null)));
    runTest(mc, nke("0", null, "q"), nkes(nke("0", "g", null)));
    runTest(mc, nke("0", null, "r"), nkes(nke("0", "g", null), nke("0", "r", "g")));
    runTest(mc, nke("0", null, "s"), nkes(nke("0", "g", null), nke("0", "r", "g")));
  }

  static class TServers {
    private final Map<String,Map<KeyExtent,SortedMap<Key,Value>>> tservers = new HashMap<>();
  }

  static class TestTabletLocationObtainer implements TabletLocationObtainer {

    private final Map<String,Map<KeyExtent,SortedMap<Key,Value>>> tservers;

    TestTabletLocationObtainer(TServers tservers) {
      this.tservers = tservers.tservers;
    }

    @Override
    public TabletLocations lookupTablet(ClientContext context, TabletLocation src, Text row,
        Text stopRow, TabletLocator parent) {

      // System.out.println("lookupTablet("+src+","+row+","+stopRow+","+ parent+")");
      // System.out.println(tservers);

      Map<KeyExtent,SortedMap<Key,Value>> tablets = tservers.get(src.tablet_location);

      if (tablets == null) {
        parent.invalidateCache(context, src.tablet_location);
        return null;
      }

      SortedMap<Key,Value> tabletData = tablets.get(src.tablet_extent);

      if (tabletData == null) {
        parent.invalidateCache(src.tablet_extent);
        return null;
      }

      // the following clip is done on a tablet, do it here to see if it throws exceptions
      src.tablet_extent.toDataRange().clip(new Range(row, true, stopRow, true));

      Key startKey = new Key(row);
      Key stopKey = new Key(stopRow).followingKey(PartialKey.ROW);

      SortedMap<Key,Value> results = tabletData.tailMap(startKey).headMap(stopKey);

      return MetadataLocationObtainer.getMetadataLocationEntries(results);
    }

    @Override
    public List<TabletLocation> lookupTablets(ClientContext context, String tserver,
        Map<KeyExtent,List<Range>> map, TabletLocator parent) {

      ArrayList<TabletLocation> list = new ArrayList<>();

      Map<KeyExtent,SortedMap<Key,Value>> tablets = tservers.get(tserver);

      if (tablets == null) {
        parent.invalidateCache(context, tserver);
        return list;
      }

      TreeMap<Key,Value> results = new TreeMap<>();

      Set<Entry<KeyExtent,List<Range>>> es = map.entrySet();
      List<KeyExtent> failures = new ArrayList<>();
      for (Entry<KeyExtent,List<Range>> entry : es) {
        SortedMap<Key,Value> tabletData = tablets.get(entry.getKey());

        if (tabletData == null) {
          failures.add(entry.getKey());
          continue;
        }
        List<Range> ranges = entry.getValue();
        for (Range range : ranges) {
          SortedMap<Key,Value> tm;
          if (range.getStartKey() == null)
            tm = tabletData;
          else
            tm = tabletData.tailMap(range.getStartKey());

          for (Entry<Key,Value> de : tm.entrySet()) {
            if (range.afterEndKey(de.getKey())) {
              break;
            }

            if (range.contains(de.getKey())) {
              results.put(de.getKey(), de.getValue());
            }
          }
        }
      }

      if (!failures.isEmpty())
        parent.invalidateCache(failures);

      return MetadataLocationObtainer.getMetadataLocationEntries(results).getLocations();

    }

  }

  static class YesLockChecker implements TabletServerLockChecker {
    @Override
    public boolean isLockHeld(String tserver, String session) {
      return true;
    }

    @Override
    public void invalidateCache(String server) {}
  }

  static class TestRootTabletLocator extends RootTabletLocator {

    TestRootTabletLocator() {
      super(new YesLockChecker());
    }

    @Override
    protected TabletLocation getRootTabletLocation(ClientContext context) {
      return new TabletLocation(RootTable.EXTENT, context.getRootTabletLocation(), "1");
    }

    @Override
    public void invalidateCache(ClientContext context, String server) {}

  }

  static void createEmptyTablet(TServers tservers, String server, KeyExtent tablet) {
    Map<KeyExtent,SortedMap<Key,Value>> tablets =
        tservers.tservers.computeIfAbsent(server, k -> new HashMap<>());
    SortedMap<Key,Value> tabletData = tablets.computeIfAbsent(tablet, k -> new TreeMap<>());
    if (!tabletData.isEmpty()) {
      throw new RuntimeException("Asked for empty tablet, but non empty tablet exists");
    }
  }

  static void clearLocation(TServers tservers, String server, KeyExtent tablet, KeyExtent ke,
      String instance) {
    Map<KeyExtent,SortedMap<Key,Value>> tablets = tservers.tservers.get(server);
    if (tablets == null) {
      return;
    }

    SortedMap<Key,Value> tabletData = tablets.get(tablet);
    if (tabletData == null) {
      return;
    }

    Text mr = ke.toMetaRow();
    Key lk = new Key(mr, CurrentLocationColumnFamily.NAME, new Text(instance));
    tabletData.remove(lk);

  }

  static void setLocation(TServers tservers, String server, KeyExtent tablet, KeyExtent ke,
      String location, String instance) {
    Map<KeyExtent,SortedMap<Key,Value>> tablets =
        tservers.tservers.computeIfAbsent(server, k -> new HashMap<>());
    SortedMap<Key,Value> tabletData = tablets.computeIfAbsent(tablet, k -> new TreeMap<>());

    Text mr = ke.toMetaRow();
    Value per = TabletColumnFamily.encodePrevEndRow(ke.prevEndRow());

    if (location != null) {
      if (instance == null)
        instance = "";
      Key lk = new Key(mr, CurrentLocationColumnFamily.NAME, new Text(instance));
      tabletData.put(lk, new Value(location));
    }

    Key pk = new Key(mr, TabletColumnFamily.PREV_ROW_COLUMN.getColumnFamily(),
        TabletColumnFamily.PREV_ROW_COLUMN.getColumnQualifier());
    tabletData.put(pk, per);
  }

  static void setLocation(TServers tservers, String server, KeyExtent tablet, KeyExtent ke,
      String location) {
    setLocation(tservers, server, tablet, ke, location, "");
  }

  static void deleteServer(TServers tservers, String server) {
    tservers.tservers.remove(server);

  }

  private void locateTabletTest(TabletLocatorImpl cache, String row, boolean skipRow,
      KeyExtent expected, String server) throws Exception {
    TabletLocation tl = cache.locateTablet(context, new Text(row), skipRow, false);

    if (expected == null) {
      if (tl != null)
        System.out.println("tl = " + tl);
      assertNull(tl);
    } else {
      assertNotNull(tl);
      assertEquals(server, tl.tablet_location);
      assertEquals(expected, tl.tablet_extent);
    }
  }

  private void locateTabletTest(TabletLocatorImpl cache, String row, KeyExtent expected,
      String server) throws Exception {
    locateTabletTest(cache, row, false, expected, server);
  }

  @Test
  public void test1() throws Exception {
    TServers tservers = new TServers();
    TestTabletLocationObtainer ttlo = new TestTabletLocationObtainer(tservers);

    RootTabletLocator rtl = new TestRootTabletLocator();
    TabletLocatorImpl rootTabletCache =
        new TabletLocatorImpl(MetadataTable.ID, rtl, ttlo, new YesLockChecker());
    TabletLocatorImpl tab1TabletCache =
        new TabletLocatorImpl(TableId.of("tab1"), rootTabletCache, ttlo, new YesLockChecker());

    locateTabletTest(tab1TabletCache, "r1", null, null);

    KeyExtent tab1e = nke("tab1", null, null);

    setLocation(tservers, "tserver1", RTE, MTE, "tserver2");
    setLocation(tservers, "tserver2", MTE, tab1e, "tserver3");

    locateTabletTest(tab1TabletCache, "r1", tab1e, "tserver3");
    locateTabletTest(tab1TabletCache, "r2", tab1e, "tserver3");

    // simulate a split
    KeyExtent tab1e1 = nke("tab1", "g", null);
    KeyExtent tab1e2 = nke("tab1", null, "g");

    setLocation(tservers, "tserver2", MTE, tab1e1, "tserver4");
    setLocation(tservers, "tserver2", MTE, tab1e2, "tserver5");

    locateTabletTest(tab1TabletCache, "r1", tab1e, "tserver3");
    tab1TabletCache.invalidateCache(tab1e);
    locateTabletTest(tab1TabletCache, "r1", tab1e2, "tserver5");
    locateTabletTest(tab1TabletCache, "a", tab1e1, "tserver4");
    locateTabletTest(tab1TabletCache, "a", true, tab1e1, "tserver4");
    locateTabletTest(tab1TabletCache, "g", tab1e1, "tserver4");
    locateTabletTest(tab1TabletCache, "g", true, tab1e2, "tserver5");

    // simulate a partial split
    KeyExtent tab1e22 = nke("tab1", null, "m");
    setLocation(tservers, "tserver2", MTE, tab1e22, "tserver6");
    locateTabletTest(tab1TabletCache, "r1", tab1e2, "tserver5");
    tab1TabletCache.invalidateCache(tab1e2);
    locateTabletTest(tab1TabletCache, "r1", tab1e22, "tserver6");
    locateTabletTest(tab1TabletCache, "h", null, null);
    locateTabletTest(tab1TabletCache, "a", tab1e1, "tserver4");
    KeyExtent tab1e21 = nke("tab1", "m", "g");
    setLocation(tservers, "tserver2", MTE, tab1e21, "tserver7");
    locateTabletTest(tab1TabletCache, "r1", tab1e22, "tserver6");
    locateTabletTest(tab1TabletCache, "h", tab1e21, "tserver7");
    locateTabletTest(tab1TabletCache, "a", tab1e1, "tserver4");

    // simulate a migration
    setLocation(tservers, "tserver2", MTE, tab1e21, "tserver8");
    tab1TabletCache.invalidateCache(tab1e21);
    locateTabletTest(tab1TabletCache, "r1", tab1e22, "tserver6");
    locateTabletTest(tab1TabletCache, "h", tab1e21, "tserver8");
    locateTabletTest(tab1TabletCache, "a", tab1e1, "tserver4");

    // simulate a server failure
    setLocation(tservers, "tserver2", MTE, tab1e21, "tserver9");
    tab1TabletCache.invalidateCache(context, "tserver8");
    locateTabletTest(tab1TabletCache, "r1", tab1e22, "tserver6");
    locateTabletTest(tab1TabletCache, "h", tab1e21, "tserver9");
    locateTabletTest(tab1TabletCache, "a", tab1e1, "tserver4");

    // simulate all servers failing
    deleteServer(tservers, "tserver1");
    deleteServer(tservers, "tserver2");
    tab1TabletCache.invalidateCache(context, "tserver4");
    tab1TabletCache.invalidateCache(context, "tserver6");
    tab1TabletCache.invalidateCache(context, "tserver9");

    locateTabletTest(tab1TabletCache, "r1", null, null);
    locateTabletTest(tab1TabletCache, "h", null, null);
    locateTabletTest(tab1TabletCache, "a", null, null);

    EasyMock.verify(context);

    context = EasyMock.createMock(ClientContext.class);
    EasyMock.expect(context.getInstanceID()).andReturn(iid).anyTimes();
    EasyMock.expect(context.getRootTabletLocation()).andReturn("tserver4").anyTimes();
    replay(context);

    setLocation(tservers, "tserver4", RTE, MTE, "tserver5");
    setLocation(tservers, "tserver5", MTE, tab1e1, "tserver1");
    setLocation(tservers, "tserver5", MTE, tab1e21, "tserver2");
    setLocation(tservers, "tserver5", MTE, tab1e22, "tserver3");

    locateTabletTest(tab1TabletCache, "a", tab1e1, "tserver1");
    locateTabletTest(tab1TabletCache, "h", tab1e21, "tserver2");
    locateTabletTest(tab1TabletCache, "r", tab1e22, "tserver3");

    // simulate the metadata table splitting
    KeyExtent mte1 = new KeyExtent(MetadataTable.ID, tab1e21.toMetaRow(), RTE.endRow());
    KeyExtent mte2 = new KeyExtent(MetadataTable.ID, null, tab1e21.toMetaRow());

    setLocation(tservers, "tserver4", RTE, mte1, "tserver5");
    setLocation(tservers, "tserver4", RTE, mte2, "tserver6");
    deleteServer(tservers, "tserver5");
    setLocation(tservers, "tserver5", mte1, tab1e1, "tserver7");
    setLocation(tservers, "tserver5", mte1, tab1e21, "tserver8");
    setLocation(tservers, "tserver6", mte2, tab1e22, "tserver9");

    tab1TabletCache.invalidateCache(tab1e1);
    tab1TabletCache.invalidateCache(tab1e21);
    tab1TabletCache.invalidateCache(tab1e22);

    locateTabletTest(tab1TabletCache, "a", tab1e1, "tserver7");
    locateTabletTest(tab1TabletCache, "h", tab1e21, "tserver8");
    locateTabletTest(tab1TabletCache, "r", tab1e22, "tserver9");

    // simulate metadata and regular server down and the reassigned
    deleteServer(tservers, "tserver5");
    tab1TabletCache.invalidateCache(context, "tserver7");
    locateTabletTest(tab1TabletCache, "a", null, null);
    locateTabletTest(tab1TabletCache, "h", tab1e21, "tserver8");
    locateTabletTest(tab1TabletCache, "r", tab1e22, "tserver9");

    setLocation(tservers, "tserver4", RTE, mte1, "tserver10");
    setLocation(tservers, "tserver10", mte1, tab1e1, "tserver7");
    setLocation(tservers, "tserver10", mte1, tab1e21, "tserver8");

    locateTabletTest(tab1TabletCache, "a", tab1e1, "tserver7");
    locateTabletTest(tab1TabletCache, "h", tab1e21, "tserver8");
    locateTabletTest(tab1TabletCache, "r", tab1e22, "tserver9");
    tab1TabletCache.invalidateCache(context, "tserver7");
    setLocation(tservers, "tserver10", mte1, tab1e1, "tserver2");
    locateTabletTest(tab1TabletCache, "a", tab1e1, "tserver2");
    locateTabletTest(tab1TabletCache, "h", tab1e21, "tserver8");
    locateTabletTest(tab1TabletCache, "r", tab1e22, "tserver9");

    // simulate a hole in the metadata, caused by a partial split
    KeyExtent mte11 = new KeyExtent(MetadataTable.ID, tab1e1.toMetaRow(), RTE.endRow());
    KeyExtent mte12 = new KeyExtent(MetadataTable.ID, tab1e21.toMetaRow(), tab1e1.toMetaRow());
    deleteServer(tservers, "tserver10");
    setLocation(tservers, "tserver4", RTE, mte12, "tserver10");
    setLocation(tservers, "tserver10", mte12, tab1e21, "tserver12");

    // at this point should be no table1 metadata
    tab1TabletCache.invalidateCache(tab1e1);
    tab1TabletCache.invalidateCache(tab1e21);
    locateTabletTest(tab1TabletCache, "a", null, null);
    locateTabletTest(tab1TabletCache, "h", tab1e21, "tserver12");
    locateTabletTest(tab1TabletCache, "r", tab1e22, "tserver9");

    setLocation(tservers, "tserver4", RTE, mte11, "tserver5");
    setLocation(tservers, "tserver5", mte11, tab1e1, "tserver13");

    locateTabletTest(tab1TabletCache, "a", tab1e1, "tserver13");
    locateTabletTest(tab1TabletCache, "h", tab1e21, "tserver12");
    locateTabletTest(tab1TabletCache, "r", tab1e22, "tserver9");
  }

  @Test
  public void test2() throws Exception {
    TServers tservers = new TServers();
    TabletLocatorImpl metaCache = createLocators(tservers, "tserver1", "tserver2", "foo");

    KeyExtent ke1 = nke("foo", "m", null);
    KeyExtent ke2 = nke("foo", null, "m");

    setLocation(tservers, "tserver2", MTE, ke1, null);
    setLocation(tservers, "tserver2", MTE, ke2, "L1");

    locateTabletTest(metaCache, "a", null, null);
    locateTabletTest(metaCache, "r", ke2, "L1");

    setLocation(tservers, "tserver2", MTE, ke1, "L2");

    locateTabletTest(metaCache, "a", ke1, "L2");
    locateTabletTest(metaCache, "r", ke2, "L1");
  }

  @Test
  public void testBinRanges1() throws Exception {

    TabletLocatorImpl metaCache = createLocators("foo", nke("foo", null, null), "l1");

    List<Range> ranges = nrl(nr(null, null));
    Map<String,Map<KeyExtent,List<Range>>> expected =
        createExpectedBinnings("l1", nol(nke("foo", null, null), nrl(nr(null, null)))

        );

    runTest(ranges, metaCache, expected);

    ranges = nrl(nr("a", null));
    expected = createExpectedBinnings("l1", nol(nke("foo", null, null), nrl(nr("a", null)))

    );

    runTest(ranges, metaCache, expected);

    ranges = nrl(nr(null, "b"));
    expected = createExpectedBinnings("l1", nol(nke("foo", null, null), nrl(nr(null, "b")))

    );

    runTest(ranges, metaCache, expected);
  }

  @Test
  public void testBinRanges2() throws Exception {

    List<Range> ranges = nrl(nr(null, null));
    TabletLocatorImpl metaCache =
        createLocators("foo", nke("foo", "g", null), "l1", nke("foo", null, "g"), "l2");

    Map<String,Map<KeyExtent,List<Range>>> expected =
        createExpectedBinnings("l1", nol(nke("foo", "g", null), nrl(nr(null, null))), "l2",
            nol(nke("foo", null, "g"), nrl(nr(null, null)))

        );

    runTest(ranges, metaCache, expected);
  }

  @Test
  public void testBinRanges3() throws Exception {

    // test with three tablets and a range that covers the whole table
    List<Range> ranges = nrl(nr(null, null));
    TabletLocatorImpl metaCache = createLocators("foo", nke("foo", "g", null), "l1",
        nke("foo", "m", "g"), "l2", nke("foo", null, "m"), "l2");

    Map<String,Map<KeyExtent,List<Range>>> expected = createExpectedBinnings("l1",
        nol(nke("foo", "g", null), nrl(nr(null, null))), "l2",
        nol(nke("foo", "m", "g"), nrl(nr(null, null)), nke("foo", null, "m"), nrl(nr(null, null)))

    );

    runTest(ranges, metaCache, expected);

    // test with three tablets where one range falls within the first tablet and last two ranges
    // fall within the last tablet
    ranges = nrl(nr(null, "c"), nr("s", "y"), nr("z", null));
    expected = createExpectedBinnings("l1", nol(nke("foo", "g", null), nrl(nr(null, "c"))), "l2",
        nol(nke("foo", null, "m"), nrl(nr("s", "y"), nr("z", null)))

    );

    runTest(ranges, metaCache, expected);

    // test is same as above, but has an additional range that spans the first two tablets
    ranges = nrl(nr(null, "c"), nr("f", "i"), nr("s", "y"), nr("z", null));
    expected =
        createExpectedBinnings("l1", nol(nke("foo", "g", null), nrl(nr(null, "c"), nr("f", "i"))),
            "l2", nol(nke("foo", "m", "g"), nrl(nr("f", "i")), nke("foo", null, "m"),
                nrl(nr("s", "y"), nr("z", null)))

        );

    runTest(ranges, metaCache, expected);

    // test where start of range is not inclusive and same as tablet endRow
    ranges = nrl(nr("g", false, "m", true));
    expected =
        createExpectedBinnings("l2", nol(nke("foo", "m", "g"), nrl(nr("g", false, "m", true)))

        );

    runTest(ranges, metaCache, expected);

    // test where start of range is inclusive and same as tablet endRow
    ranges = nrl(nr("g", true, "m", true));
    expected =
        createExpectedBinnings("l1", nol(nke("foo", "g", null), nrl(nr("g", true, "m", true))),
            "l2", nol(nke("foo", "m", "g"), nrl(nr("g", true, "m", true)))

        );

    runTest(ranges, metaCache, expected);

    ranges = nrl(nr("g", true, "m", false));
    expected =
        createExpectedBinnings("l1", nol(nke("foo", "g", null), nrl(nr("g", true, "m", false))),
            "l2", nol(nke("foo", "m", "g"), nrl(nr("g", true, "m", false)))

        );

    runTest(ranges, metaCache, expected);

    ranges = nrl(nr("g", false, "m", false));
    expected =
        createExpectedBinnings("l2", nol(nke("foo", "m", "g"), nrl(nr("g", false, "m", false)))

        );

    runTest(ranges, metaCache, expected);
  }

  @Test
  public void testBinRanges4() throws Exception {

    List<Range> ranges = nrl(new Range(new Text("1")));
    TabletLocatorImpl metaCache =
        createLocators("foo", nke("foo", "0", null), "l1", nke("foo", "1", "0"), "l2",
            nke("foo", "2", "1"), "l3", nke("foo", "3", "2"), "l4", nke("foo", null, "3"), "l5");

    Map<String,Map<KeyExtent,List<Range>>> expected =
        createExpectedBinnings("l2", nol(nke("foo", "1", "0"), nrl(new Range(new Text("1"))))

        );

    runTest(ranges, metaCache, expected);

    Key rowColKey = new Key(new Text("3"), new Text("cf1"), new Text("cq1"));
    Range range =
        new Range(rowColKey, true, new Key(new Text("3")).followingKey(PartialKey.ROW), false);

    ranges = nrl(range);
    Map<String,Map<KeyExtent,List<Range>>> expected4 =
        createExpectedBinnings("l4", nol(nke("foo", "3", "2"), nrl(range))

        );

    runTest(ranges, metaCache, expected4, nrl());

    range = new Range(rowColKey, true, new Key(new Text("3")).followingKey(PartialKey.ROW), true);

    ranges = nrl(range);
    Map<String,Map<KeyExtent,List<Range>>> expected5 = createExpectedBinnings("l4",
        nol(nke("foo", "3", "2"), nrl(range)), "l5", nol(nke("foo", null, "3"), nrl(range))

    );

    runTest(ranges, metaCache, expected5, nrl());

    range = new Range(new Text("2"), false, new Text("3"), false);
    ranges = nrl(range);
    Map<String,Map<KeyExtent,List<Range>>> expected6 =
        createExpectedBinnings("l4", nol(nke("foo", "3", "2"), nrl(range))

        );
    runTest(ranges, metaCache, expected6, nrl());

    range = new Range(new Text("2"), true, new Text("3"), false);
    ranges = nrl(range);
    Map<String,Map<KeyExtent,List<Range>>> expected7 = createExpectedBinnings("l3",
        nol(nke("foo", "2", "1"), nrl(range)), "l4", nol(nke("foo", "3", "2"), nrl(range))

    );
    runTest(ranges, metaCache, expected7, nrl());

    range = new Range(new Text("2"), false, new Text("3"), true);
    ranges = nrl(range);
    Map<String,Map<KeyExtent,List<Range>>> expected8 =
        createExpectedBinnings("l4", nol(nke("foo", "3", "2"), nrl(range))

        );
    runTest(ranges, metaCache, expected8, nrl());

    range = new Range(new Text("2"), true, new Text("3"), true);
    ranges = nrl(range);
    Map<String,Map<KeyExtent,List<Range>>> expected9 = createExpectedBinnings("l3",
        nol(nke("foo", "2", "1"), nrl(range)), "l4", nol(nke("foo", "3", "2"), nrl(range))

    );
    runTest(ranges, metaCache, expected9, nrl());

  }

  @Test
  public void testBinRanges5() throws Exception {
    // Test binning when there is a hole in the metadata

    List<Range> ranges = nrl(new Range(new Text("1")));
    TabletLocatorImpl metaCache = createLocators("foo", nke("foo", "0", null), "l1",
        nke("foo", "1", "0"), "l2", nke("foo", "3", "2"), "l4", nke("foo", null, "3"), "l5");

    Map<String,Map<KeyExtent,List<Range>>> expected1 =
        createExpectedBinnings("l2", nol(nke("foo", "1", "0"), nrl(new Range(new Text("1"))))

        );

    runTest(ranges, metaCache, expected1);

    ranges = nrl(new Range(new Text("2")), new Range(new Text("11")));
    Map<String,Map<KeyExtent,List<Range>>> expected2 = createExpectedBinnings();

    runTest(ranges, metaCache, expected2, ranges);

    ranges = nrl(new Range(new Text("1")), new Range(new Text("2")));

    runTest(ranges, metaCache, expected1, nrl(new Range(new Text("2"))));

    ranges = nrl(nr("0", "2"), nr("3", "4"));
    Map<String,Map<KeyExtent,List<Range>>> expected3 =
        createExpectedBinnings("l4", nol(nke("foo", "3", "2"), nrl(nr("3", "4"))), "l5",
            nol(nke("foo", null, "3"), nrl(nr("3", "4")))

        );

    runTest(ranges, metaCache, expected3, nrl(nr("0", "2")));

    ranges =
        nrl(nr("0", "1"), nr("0", "11"), nr("1", "2"), nr("0", "4"), nr("2", "4"), nr("21", "4"));
    Map<String,Map<KeyExtent,List<Range>>> expected4 =
        createExpectedBinnings("l1", nol(nke("foo", "0", null), nrl(nr("0", "1"))), "l2",
            nol(nke("foo", "1", "0"), nrl(nr("0", "1"))), "l4",
            nol(nke("foo", "3", "2"), nrl(nr("21", "4"))), "l5",
            nol(nke("foo", null, "3"), nrl(nr("21", "4")))

        );

    runTest(ranges, metaCache, expected4,
        nrl(nr("0", "11"), nr("1", "2"), nr("0", "4"), nr("2", "4")));
  }

  @Test
  public void testBinMutations1() throws Exception {
    // one tablet table
    KeyExtent ke1 = nke("foo", null, null);
    TabletLocatorImpl metaCache = createLocators("foo", ke1, "l1");

    List<Mutation> ml =
        nml(nm("a", "cf1:cq1=v1", "cf1:cq2=v2"), nm("c", "cf1:cq1=v3", "cf1:cq2=v4"));
    Map<String,Map<KeyExtent,List<String>>> emb = cemb(nol("a", "l1", ke1), nol("c", "l1", ke1));
    runTest(metaCache, ml, emb);

    ml = nml(nm("a", "cf1:cq1=v1", "cf1:cq2=v2"));
    emb = cemb(nol("a", "l1", ke1));
    runTest(metaCache, ml, emb);

    ml = nml(nm("a", "cf1:cq1=v1", "cf1:cq2=v2"), nm("a", "cf1:cq3=v3"));
    emb = cemb(nol("a", "l1", ke1), nol("a", "l1", ke1));
    runTest(metaCache, ml, emb);

  }

  @Test
  public void testBinMutations2() throws Exception {
    // no tablets for table
    TabletLocatorImpl metaCache = createLocators("foo");

    List<Mutation> ml =
        nml(nm("a", "cf1:cq1=v1", "cf1:cq2=v2"), nm("c", "cf1:cq1=v3", "cf1:cq2=v4"));
    Map<String,Map<KeyExtent,List<String>>> emb = cemb();
    runTest(metaCache, ml, emb, "a", "c");
  }

  @Test
  public void testBinMutations3() throws Exception {
    // three tablet table
    KeyExtent ke1 = nke("foo", "h", null);
    KeyExtent ke2 = nke("foo", "t", "h");
    KeyExtent ke3 = nke("foo", null, "t");

    TabletLocatorImpl metaCache = createLocators("foo", ke1, "l1", ke2, "l2", ke3, "l3");

    List<Mutation> ml =
        nml(nm("a", "cf1:cq1=v1", "cf1:cq2=v2"), nm("i", "cf1:cq1=v3", "cf1:cq2=v4"));
    Map<String,Map<KeyExtent,List<String>>> emb = cemb(nol("a", "l1", ke1), nol("i", "l2", ke2));
    runTest(metaCache, ml, emb);

    ml = nml(nm("a", "cf1:cq1=v1", "cf1:cq2=v2"));
    emb = cemb(nol("a", "l1", ke1));
    runTest(metaCache, ml, emb);

    ml = nml(nm("a", "cf1:cq1=v1", "cf1:cq2=v2"), nm("a", "cf1:cq3=v3"));
    emb = cemb(nol("a", "l1", ke1), nol("a", "l1", ke1));
    runTest(metaCache, ml, emb);

    ml = nml(nm("a", "cf1:cq1=v1", "cf1:cq2=v2"), nm("w", "cf1:cq3=v3"));
    emb = cemb(nol("a", "l1", ke1), nol("w", "l3", ke3));
    runTest(metaCache, ml, emb);

    ml = nml(nm("a", "cf1:cq1=v1", "cf1:cq2=v2"), nm("w", "cf1:cq3=v3"), nm("z", "cf1:cq4=v4"));
    emb = cemb(nol("a", "l1", ke1), nol("w", "l3", ke3), nol("z", "l3", ke3));
    runTest(metaCache, ml, emb);

    ml = nml(nm("h", "cf1:cq1=v1", "cf1:cq2=v2"), nm("t", "cf1:cq1=v1", "cf1:cq2=v2"));
    emb = cemb(nol("h", "l1", ke1), nol("t", "l2", ke2));
    runTest(metaCache, ml, emb);
  }

  @Test
  public void testBinMutations4() throws Exception {
    // three table with hole
    KeyExtent ke1 = nke("foo", "h", null);

    KeyExtent ke3 = nke("foo", null, "t");

    TabletLocatorImpl metaCache = createLocators("foo", ke1, "l1", ke3, "l3");

    List<Mutation> ml =
        nml(nm("a", "cf1:cq1=v1", "cf1:cq2=v2"), nm("i", "cf1:cq1=v3", "cf1:cq2=v4"));
    Map<String,Map<KeyExtent,List<String>>> emb = cemb(nol("a", "l1", ke1));
    runTest(metaCache, ml, emb, "i");

    ml = nml(nm("a", "cf1:cq1=v1", "cf1:cq2=v2"));
    emb = cemb(nol("a", "l1", ke1));
    runTest(metaCache, ml, emb);

    ml = nml(nm("a", "cf1:cq1=v1", "cf1:cq2=v2"), nm("a", "cf1:cq3=v3"));
    emb = cemb(nol("a", "l1", ke1), nol("a", "l1", ke1));
    runTest(metaCache, ml, emb);

    ml = nml(nm("a", "cf1:cq1=v1", "cf1:cq2=v2"), nm("w", "cf1:cq3=v3"));
    emb = cemb(nol("a", "l1", ke1), nol("w", "l3", ke3));
    runTest(metaCache, ml, emb);

    ml = nml(nm("a", "cf1:cq1=v1", "cf1:cq2=v2"), nm("w", "cf1:cq3=v3"), nm("z", "cf1:cq4=v4"));
    emb = cemb(nol("a", "l1", ke1), nol("w", "l3", ke3), nol("z", "l3", ke3));
    runTest(metaCache, ml, emb);

    ml = nml(nm("a", "cf1:cq1=v1", "cf1:cq2=v2"), nm("w", "cf1:cq3=v3"), nm("z", "cf1:cq4=v4"),
        nm("t", "cf1:cq5=v5"));
    emb = cemb(nol("a", "l1", ke1), nol("w", "l3", ke3), nol("z", "l3", ke3));
    runTest(metaCache, ml, emb, "t");
  }

  @Test
  public void testBinSplit() throws Exception {
    // try binning mutations and ranges when a tablet splits

    for (int i = 0; i < 3; i++) {
      // when i == 0 only test binning mutations
      // when i == 1 only test binning ranges
      // when i == 2 test both

      KeyExtent ke1 = nke("foo", null, null);
      TServers tservers = new TServers();
      TabletLocatorImpl metaCache =
          createLocators(tservers, "tserver1", "tserver2", "foo", ke1, "l1");

      List<Mutation> ml = nml(nm("a", "cf1:cq1=v1", "cf1:cq2=v2"),
          nm("m", "cf1:cq1=v3", "cf1:cq2=v4"), nm("z", "cf1:cq1=v5"));
      Map<String,Map<KeyExtent,List<String>>> emb =
          cemb(nol("a", "l1", ke1), nol("m", "l1", ke1), nol("z", "l1", ke1));
      if (i == 0 || i == 2)
        runTest(metaCache, ml, emb);

      List<Range> ranges =
          nrl(new Range(new Text("a")), new Range(new Text("m")), new Range(new Text("z")));

      Map<String,Map<KeyExtent,List<Range>>> expected1 =
          createExpectedBinnings("l1", nol(nke("foo", null, null), ranges)

          );

      if (i == 1 || i == 2)
        runTest(ranges, metaCache, expected1);

      KeyExtent ke11 = nke("foo", "n", null);
      KeyExtent ke12 = nke("foo", null, "n");

      setLocation(tservers, "tserver2", MTE, ke12, "l2");

      metaCache.invalidateCache(ke1);

      emb = cemb(nol("z", "l2", ke12));
      if (i == 0 || i == 2)
        runTest(metaCache, ml, emb, "a", "m");

      Map<String,Map<KeyExtent,List<Range>>> expected2 =
          createExpectedBinnings("l2", nol(nke("foo", null, "n"), nrl(new Range(new Text("z"))))

          );

      if (i == 1 || i == 2)
        runTest(ranges, metaCache, expected2,
            nrl(new Range(new Text("a")), new Range(new Text("m"))));

      setLocation(tservers, "tserver2", MTE, ke11, "l3");
      emb = cemb(nol("a", "l3", ke11), nol("m", "l3", ke11), nol("z", "l2", ke12));
      if (i == 0 || i == 2)
        runTest(metaCache, ml, emb);

      Map<String,
          Map<KeyExtent,List<Range>>> expected3 = createExpectedBinnings("l2",
              nol(nke("foo", null, "n"), nrl(new Range(new Text("z")))), "l3",
              nol(nke("foo", "n", null), nrl(new Range(new Text("a")), new Range(new Text("m"))))

      );

      if (i == 1 || i == 2)
        runTest(ranges, metaCache, expected3);
    }
  }

  @Test
  public void testBug1() throws Exception {
    // a bug that occurred while running continuous ingest
    KeyExtent mte1 = new KeyExtent(MetadataTable.ID, new Text("0;0bc"), RTE.endRow());
    KeyExtent mte2 = new KeyExtent(MetadataTable.ID, null, new Text("0;0bc"));

    TServers tservers = new TServers();
    TestTabletLocationObtainer ttlo = new TestTabletLocationObtainer(tservers);

    RootTabletLocator rtl = new TestRootTabletLocator();
    TabletLocatorImpl rootTabletCache =
        new TabletLocatorImpl(MetadataTable.ID, rtl, ttlo, new YesLockChecker());
    TabletLocatorImpl tab0TabletCache =
        new TabletLocatorImpl(TableId.of("0"), rootTabletCache, ttlo, new YesLockChecker());

    setLocation(tservers, "tserver1", RTE, mte1, "tserver2");
    setLocation(tservers, "tserver1", RTE, mte2, "tserver3");

    // create two tablets that straddle a metadata split point
    KeyExtent ke1 = new KeyExtent(TableId.of("0"), new Text("0bbf20e"), null);
    KeyExtent ke2 = new KeyExtent(TableId.of("0"), new Text("0bc0756"), new Text("0bbf20e"));

    setLocation(tservers, "tserver2", mte1, ke1, "tserver4");
    setLocation(tservers, "tserver3", mte2, ke2, "tserver5");

    // look up something that comes after the last entry in mte1
    locateTabletTest(tab0TabletCache, "0bbff", ke2, "tserver5");
  }

  @Test
  public void testBug2() throws Exception {
    // a bug that occurred while running a functional test
    KeyExtent mte1 = new KeyExtent(MetadataTable.ID, new Text("~"), RTE.endRow());
    KeyExtent mte2 = new KeyExtent(MetadataTable.ID, null, new Text("~"));

    TServers tservers = new TServers();
    TestTabletLocationObtainer ttlo = new TestTabletLocationObtainer(tservers);

    RootTabletLocator rtl = new TestRootTabletLocator();
    TabletLocatorImpl rootTabletCache =
        new TabletLocatorImpl(MetadataTable.ID, rtl, ttlo, new YesLockChecker());
    TabletLocatorImpl tab0TabletCache =
        new TabletLocatorImpl(TableId.of("0"), rootTabletCache, ttlo, new YesLockChecker());

    setLocation(tservers, "tserver1", RTE, mte1, "tserver2");
    setLocation(tservers, "tserver1", RTE, mte2, "tserver3");

    // create the ~ tablet so it exists
    Map<KeyExtent,SortedMap<Key,Value>> ts3 = new HashMap<>();
    ts3.put(mte2, new TreeMap<>());
    tservers.tservers.put("tserver3", ts3);

    assertNull(tab0TabletCache.locateTablet(context, new Text("row_0000000000"), false, false));

  }

  // this test reproduces a problem where empty metadata tablets, that were created by user tablets
  // being merged away, caused locating tablets to fail
  @Test
  public void testBug3() throws Exception {
    KeyExtent mte1 = new KeyExtent(MetadataTable.ID, new Text("1;c"), RTE.endRow());
    KeyExtent mte2 = new KeyExtent(MetadataTable.ID, new Text("1;f"), new Text("1;c"));
    KeyExtent mte3 = new KeyExtent(MetadataTable.ID, new Text("1;j"), new Text("1;f"));
    KeyExtent mte4 = new KeyExtent(MetadataTable.ID, new Text("1;r"), new Text("1;j"));
    KeyExtent mte5 = new KeyExtent(MetadataTable.ID, null, new Text("1;r"));

    KeyExtent ke1 = new KeyExtent(TableId.of("1"), null, null);

    TServers tservers = new TServers();
    TestTabletLocationObtainer ttlo = new TestTabletLocationObtainer(tservers);

    RootTabletLocator rtl = new TestRootTabletLocator();

    TabletLocatorImpl rootTabletCache =
        new TabletLocatorImpl(MetadataTable.ID, rtl, ttlo, new YesLockChecker());
    TabletLocatorImpl tab0TabletCache =
        new TabletLocatorImpl(TableId.of("1"), rootTabletCache, ttlo, new YesLockChecker());

    setLocation(tservers, "tserver1", RTE, mte1, "tserver2");
    setLocation(tservers, "tserver1", RTE, mte2, "tserver3");
    setLocation(tservers, "tserver1", RTE, mte3, "tserver4");
    setLocation(tservers, "tserver1", RTE, mte4, "tserver5");
    setLocation(tservers, "tserver1", RTE, mte5, "tserver6");

    createEmptyTablet(tservers, "tserver2", mte1);
    createEmptyTablet(tservers, "tserver3", mte2);
    createEmptyTablet(tservers, "tserver4", mte3);
    createEmptyTablet(tservers, "tserver5", mte4);
    setLocation(tservers, "tserver6", mte5, ke1, "tserver7");

    locateTabletTest(tab0TabletCache, "a", ke1, "tserver7");

  }

  @Test
  public void testAccumulo1248() {
    TServers tservers = new TServers();
    TabletLocatorImpl metaCache = createLocators(tservers, "tserver1", "tserver2", "foo");

    KeyExtent ke1 = nke("foo", null, null);

    // set two locations for a tablet, this is not supposed to happen. The metadata cache should
    // throw an exception if it sees this rather than caching one of
    // the locations.
    setLocation(tservers, "tserver2", MTE, ke1, "L1", "I1");
    setLocation(tservers, "tserver2", MTE, ke1, "L2", "I2");

    var e = assertThrows(IllegalStateException.class,
        () -> metaCache.locateTablet(context, new Text("a"), false, false));
    assertTrue(e.getMessage().startsWith("Tablet has multiple locations : "));

  }

  @Test
  public void testLostLock() throws Exception {

    final HashSet<String> activeLocks = new HashSet<>();

    TServers tservers = new TServers();
    TabletLocatorImpl metaCache =
        createLocators(tservers, "tserver1", "tserver2", "foo", new TabletServerLockChecker() {
          @Override
          public boolean isLockHeld(String tserver, String session) {
            return activeLocks.contains(tserver + ":" + session);
          }

          @Override
          public void invalidateCache(String server) {}
        });

    KeyExtent ke1 = nke("foo", null, null);
    setLocation(tservers, "tserver2", MTE, ke1, "L1", "5");

    activeLocks.add("L1:5");

    locateTabletTest(metaCache, "a", ke1, "L1");
    locateTabletTest(metaCache, "a", ke1, "L1");

    activeLocks.clear();

    locateTabletTest(metaCache, "a", null, null);
    locateTabletTest(metaCache, "a", null, null);
    locateTabletTest(metaCache, "a", null, null);

    clearLocation(tservers, "tserver2", MTE, ke1, "5");
    setLocation(tservers, "tserver2", MTE, ke1, "L2", "6");

    activeLocks.add("L2:6");

    locateTabletTest(metaCache, "a", ke1, "L2");
    locateTabletTest(metaCache, "a", ke1, "L2");

    clearLocation(tservers, "tserver2", MTE, ke1, "6");

    locateTabletTest(metaCache, "a", ke1, "L2");

    setLocation(tservers, "tserver2", MTE, ke1, "L3", "7");

    locateTabletTest(metaCache, "a", ke1, "L2");

    activeLocks.clear();

    locateTabletTest(metaCache, "a", null, null);
    locateTabletTest(metaCache, "a", null, null);

    activeLocks.add("L3:7");

    locateTabletTest(metaCache, "a", ke1, "L3");
    locateTabletTest(metaCache, "a", ke1, "L3");

    List<Mutation> ml = nml(nm("a", "cf1:cq1=v1", "cf1:cq2=v2"), nm("w", "cf1:cq3=v3"));
    Map<String,Map<KeyExtent,List<String>>> emb = cemb(nol("a", "L3", ke1), nol("w", "L3", ke1));
    runTest(metaCache, ml, emb);

    clearLocation(tservers, "tserver2", MTE, ke1, "7");

    runTest(metaCache, ml, emb);

    activeLocks.clear();

    emb.clear();

    runTest(metaCache, ml, emb, "a", "w");
    runTest(metaCache, ml, emb, "a", "w");

    KeyExtent ke11 = nke("foo", "m", null);
    KeyExtent ke12 = nke("foo", null, "m");

    setLocation(tservers, "tserver2", MTE, ke11, "L1", "8");
    setLocation(tservers, "tserver2", MTE, ke12, "L2", "9");

    runTest(metaCache, ml, emb, "a", "w");

    activeLocks.add("L1:8");

    emb = cemb(nol("a", "L1", ke11));
    runTest(metaCache, ml, emb, "w");

    activeLocks.add("L2:9");

    emb = cemb(nol("a", "L1", ke11), nol("w", "L2", ke12));
    runTest(metaCache, ml, emb);

    List<Range> ranges = nrl(new Range("a"), nr("b", "o"), nr("r", "z"));
    Map<String,Map<KeyExtent,List<Range>>> expected =
        createExpectedBinnings("L1", nol(ke11, nrl(new Range("a"), nr("b", "o"))), "L2",
            nol(ke12, nrl(nr("b", "o"), nr("r", "z"))));

    runTest(ranges, metaCache, expected);

    activeLocks.remove("L2:9");

    expected = createExpectedBinnings("L1", nol(ke11, nrl(new Range("a"))));
    runTest(ranges, metaCache, expected, nrl(nr("b", "o"), nr("r", "z")));

    activeLocks.clear();

    expected = createExpectedBinnings();
    runTest(ranges, metaCache, expected, nrl(new Range("a"), nr("b", "o"), nr("r", "z")));

    clearLocation(tservers, "tserver2", MTE, ke11, "8");
    clearLocation(tservers, "tserver2", MTE, ke12, "9");
    setLocation(tservers, "tserver2", MTE, ke11, "L3", "10");
    setLocation(tservers, "tserver2", MTE, ke12, "L4", "11");

    runTest(ranges, metaCache, expected, nrl(new Range("a"), nr("b", "o"), nr("r", "z")));

    activeLocks.add("L3:10");

    expected = createExpectedBinnings("L3", nol(ke11, nrl(new Range("a"))));
    runTest(ranges, metaCache, expected, nrl(nr("b", "o"), nr("r", "z")));

    activeLocks.add("L4:11");

    expected = createExpectedBinnings("L3", nol(ke11, nrl(new Range("a"), nr("b", "o"))), "L4",
        nol(ke12, nrl(nr("b", "o"), nr("r", "z"))));
    runTest(ranges, metaCache, expected);
  }

}
