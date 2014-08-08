/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.bwcompat;

import com.carrotsearch.randomizedtesting.LifecycleScope;
import com.carrotsearch.randomizedtesting.generators.RandomPicks;
import org.apache.lucene.util.English;
import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.Version;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthStatus;
import org.elasticsearch.action.admin.cluster.snapshots.create.CreateSnapshotResponse;
import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotResponse;
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsResponse;
import org.elasticsearch.action.count.CountResponse;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.deletebyquery.DeleteByQueryResponse;
import org.elasticsearch.action.deletebyquery.IndexDeleteByQueryResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.allocation.decider.EnableAllocationDecider;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.regex.Regex;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.internal.FieldNamesFieldMapper;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.snapshots.SnapshotState;
import org.elasticsearch.test.ElasticsearchBackwardsCompatIntegrationTest;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.index.query.FilterBuilders.existsFilter;
import static org.elasticsearch.index.query.FilterBuilders.missingFilter;
import static org.elasticsearch.index.query.QueryBuilders.*;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.hamcrest.Matchers.*;

/**
 */
public class BasicBackwardsCompatibilityTest extends ElasticsearchBackwardsCompatIntegrationTest {

    /**
     * Basic test using Index & Realtime Get with external versioning. This test ensures routing works correctly across versions.
     */
    @Test
    public void testExternalVersion() throws Exception {
        createIndex("test");
        final boolean routing = randomBoolean();
        int numDocs = randomIntBetween(10, 20);
        for (int i = 0; i < numDocs; i++) {
            String id = Integer.toString(i);
            String routingKey = routing ? randomRealisticUnicodeOfLength(10) : null;
            final long version = randomIntBetween(0, Integer.MAX_VALUE);
            client().prepareIndex("test", "type1", id).setRouting(routingKey).setVersion(version).setVersionType(VersionType.EXTERNAL).setSource("field1", English.intToEnglish(i)).get();
            GetResponse get = client().prepareGet("test", "type1", id).setRouting(routingKey).setVersion(version).get();
            assertThat("Document with ID " +id + " should exist but doesn't", get.isExists(), is(true));
            assertThat(get.getVersion(), equalTo(version));
            final long nextVersion = version + randomIntBetween(0, Integer.MAX_VALUE);
            client().prepareIndex("test", "type1", id).setRouting(routingKey).setVersion(nextVersion).setVersionType(VersionType.EXTERNAL).setSource("field1", English.intToEnglish(i)).get();
            get = client().prepareGet("test", "type1", id).setRouting(routingKey).setVersion(nextVersion).get();
            assertThat("Document with ID " +id + " should exist but doesn't", get.isExists(), is(true));
            assertThat(get.getVersion(), equalTo(nextVersion));
        }
    }

    /**
     * Basic test using Index & Realtime Get with internal versioning. This test ensures routing works correctly across versions.
     */
    @Test
    public void testInternalVersion() throws Exception {
        createIndex("test");
        final boolean routing = randomBoolean();
        int numDocs = randomIntBetween(10, 20);
        for (int i = 0; i < numDocs; i++) {
            String routingKey = routing ? randomRealisticUnicodeOfLength(10) : null;
            String id = Integer.toString(i);
            assertThat(id, client().prepareIndex("test", "type1", id).setRouting(routingKey).setSource("field1", English.intToEnglish(i)).get().isCreated(), is(true));
            GetResponse get = client().prepareGet("test", "type1", id).setRouting(routingKey).setVersion(1).get();
            assertThat("Document with ID " +id + " should exist but doesn't", get.isExists(), is(true));
            assertThat(get.getVersion(), equalTo(1l));
            client().prepareIndex("test", "type1", id).setRouting(routingKey).setSource("field1", English.intToEnglish(i)).execute().actionGet();
            get = client().prepareGet("test", "type1", id).setRouting(routingKey).setVersion(2).get();
            assertThat("Document with ID " +id + " should exist but doesn't", get.isExists(), is(true));
            assertThat(get.getVersion(), equalTo(2l));
        }

        assertVersionCreated(compatibilityVersion(), "test");
    }

    /**
     * Very basic bw compat test with a mixed version cluster random indexing and lookup by ID via term query
     */
    @Test
    public void testIndexAndSearch() throws Exception {
        createIndex("test");
        int numDocs = randomIntBetween(10, 20);
        List<IndexRequestBuilder> builder = new ArrayList<>();
        for (int i = 0; i < numDocs; i++) {
            String id = Integer.toString(i);
            builder.add(client().prepareIndex("test", "type1", id).setSource("field1", English.intToEnglish(i), "the_id", id));
        }
        indexRandom(true, builder);
        for (int i = 0; i < numDocs; i++) {
            String id = Integer.toString(i);
            assertHitCount(client().prepareSearch().setQuery(QueryBuilders.termQuery("the_id", id)).get(), 1);
        }
        assertVersionCreated(compatibilityVersion(), "test");
    }

    @Test
    public void testRecoverFromPreviousVersion() throws ExecutionException, InterruptedException {
        assertAcked(prepareCreate("test").setSettings(ImmutableSettings.builder().put("index.routing.allocation.exclude._name", backwardsCluster().newNodePattern()).put(indexSettings())));
        ensureYellow();
        assertAllShardsOnNodes("test", backwardsCluster().backwardsNodePattern());
        int numDocs = randomIntBetween(100, 150);
        IndexRequestBuilder[] docs = new IndexRequestBuilder[numDocs];
        for (int i = 0; i < numDocs; i++) {
            docs[i] = client().prepareIndex("test", "type1", randomRealisticUnicodeOfLength(10) + String.valueOf(i)).setSource("field1", English.intToEnglish(i));
        }
        indexRandom(true, docs);
        CountResponse countResponse = client().prepareCount().get();
        assertHitCount(countResponse, numDocs);
        backwardsCluster().allowOnlyNewNodes("test");
        ensureYellow("test");// move all shards to the new node
        final int numIters = randomIntBetween(10, 20);
        for (int i = 0; i < numIters; i++) {
            countResponse = client().prepareCount().get();
            assertHitCount(countResponse, numDocs);
        }
        assertVersionCreated(compatibilityVersion(), "test");
    }

    /**
     * Test that ensures that we will never recover from a newer to an older version (we are not forward compatible)
     */
    @Test
    public void testNoRecoveryFromNewNodes() throws ExecutionException, InterruptedException {
        assertAcked(prepareCreate("test").setSettings(ImmutableSettings.builder().put("index.routing.allocation.exclude._name", backwardsCluster().backwardsNodePattern()).put(indexSettings())));
        if (backwardsCluster().numNewDataNodes() == 0) {
            backwardsCluster().startNewNode();
        }
        ensureYellow();
        assertAllShardsOnNodes("test", backwardsCluster().newNodePattern());
        if (randomBoolean()) {
            backwardsCluster().allowOnAllNodes("test");
        }
        int numDocs = randomIntBetween(100, 150);
        IndexRequestBuilder[] docs = new IndexRequestBuilder[numDocs];
        for (int i = 0; i < numDocs; i++) {
            docs[i] = client().prepareIndex("test", "type1", randomRealisticUnicodeOfLength(10) + String.valueOf(i)).setSource("field1", English.intToEnglish(i), "num_int", randomInt(), "num_double", randomDouble());
        }
        indexRandom(true, docs);
        backwardsCluster().allowOnAllNodes("test");
        while(ensureYellow() != ClusterHealthStatus.GREEN) {
            backwardsCluster().startNewNode();
        }
        assertAllShardsOnNodes("test", backwardsCluster().newNodePattern());
        CountResponse countResponse = client().prepareCount().get();
        assertHitCount(countResponse, numDocs);
        final int numIters = randomIntBetween(10, 20);
        for (int i = 0; i < numIters; i++) {
            countResponse = client().prepareCount().get();
            assertHitCount(countResponse, numDocs);
            assertSimpleSort("num_double", "num_int");
        }
        assertVersionCreated(compatibilityVersion(), "test");
    }


    public void assertSimpleSort(String... numericFields) {
        for(String field : numericFields) {
            SearchResponse searchResponse = client().prepareSearch().addSort(field, SortOrder.ASC).get();
            SearchHit[] hits = searchResponse.getHits().getHits();
            assertThat(hits.length, greaterThan(0));
            Number previous = null;
            for (SearchHit hit : hits) {
                assertNotNull(hit.getSource().get(field));
                if (previous != null) {
                    assertThat(previous.doubleValue(), lessThanOrEqualTo(((Number) hit.getSource().get(field)).doubleValue()));
                }
                previous = (Number) hit.getSource().get(field);
            }
        }
    }

    public void assertAllShardsOnNodes(String index, String pattern) {
        ClusterState clusterState = client().admin().cluster().prepareState().execute().actionGet().getState();
        for (IndexRoutingTable indexRoutingTable : clusterState.routingTable()) {
            for (IndexShardRoutingTable indexShardRoutingTable : indexRoutingTable) {
                for (ShardRouting shardRouting : indexShardRoutingTable) {
                    if (shardRouting.currentNodeId() != null &&  index.equals(shardRouting.getIndex())) {
                        String name = clusterState.nodes().get(shardRouting.currentNodeId()).name();
                        assertThat("Allocated on new node: " + name, Regex.simpleMatch(pattern, name), is(true));
                    }
                }
            }
        }
    }

    /**
     * Upgrades a single node to the current version
     */
    @Test
    public void testIndexUpgradeSingleNode() throws Exception {
        assertAcked(prepareCreate("test").setSettings(ImmutableSettings.builder().put("index.routing.allocation.exclude._name", backwardsCluster().newNodePattern()).put(indexSettings())));
        ensureYellow();
        int numDocs = randomIntBetween(100, 150);
        IndexRequestBuilder[] docs = new IndexRequestBuilder[numDocs];
        for (int i = 0; i < numDocs; i++) {
            docs[i] = client().prepareIndex("test", "type1", String.valueOf(i)).setSource("field1", English.intToEnglish(i), "num_int", randomInt(), "num_double", randomDouble());
        }

        indexRandom(true, docs);
        assertAllShardsOnNodes("test", backwardsCluster().backwardsNodePattern());
        client().admin().indices().prepareUpdateSettings("test").setSettings(ImmutableSettings.builder().put(EnableAllocationDecider.INDEX_ROUTING_ALLOCATION_ENABLE, "none")).get();
        backwardsCluster().allowOnAllNodes("test");
        CountResponse countResponse = client().prepareCount().get();
        assertHitCount(countResponse, numDocs);
        backwardsCluster().upgradeOneNode();
        ensureYellow();
        if (randomBoolean()) {
            for (int i = 0; i < numDocs; i++) {
                docs[i] = client().prepareIndex("test", "type1", String.valueOf(i)).setSource("field1", English.intToEnglish(i), "num_int", randomInt(), "num_double", randomDouble());
            }
            indexRandom(true, docs);
        }
        client().admin().indices().prepareUpdateSettings("test").setSettings(ImmutableSettings.builder().put(EnableAllocationDecider.INDEX_ROUTING_ALLOCATION_ENABLE, "all")).get();
        ensureYellow();
        final int numIters = randomIntBetween(1, 20);
        for (int i = 0; i < numIters; i++) {
            assertHitCount(client().prepareCount().get(), numDocs);
            assertSimpleSort("num_double", "num_int");
        }
        assertVersionCreated(compatibilityVersion(), "test");
    }

    /**
     * Test that allocates an index on one or more old nodes and then do a rolling upgrade
     * one node after another is shut down and restarted from a newer version and we verify
     * that all documents are still around after each nodes upgrade.
     */
    @Test
    public void testIndexRollingUpgrade() throws Exception {
        String[] indices = new String[randomIntBetween(1,3)];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = "test" + i;
            assertAcked(prepareCreate(indices[i]).setSettings(ImmutableSettings.builder().put("index.routing.allocation.exclude._name", backwardsCluster().newNodePattern()).put(indexSettings())));
        }

        int numDocs = randomIntBetween(100, 150);
        IndexRequestBuilder[] docs = new IndexRequestBuilder[numDocs];
        String[] indexForDoc = new String[docs.length];
        for (int i = 0; i < numDocs; i++) {
            docs[i] = client().prepareIndex(indexForDoc[i] = RandomPicks.randomFrom(getRandom(), indices), "type1", String.valueOf(i)).setSource("field1", English.intToEnglish(i), "num_int", randomInt(), "num_double", randomDouble());
        }
        indexRandom(true, docs);
        for (int i = 0; i < indices.length; i++) {
            assertAllShardsOnNodes(indices[i], backwardsCluster().backwardsNodePattern());
        }
        client().admin().indices().prepareUpdateSettings(indices).setSettings(ImmutableSettings.builder().put(EnableAllocationDecider.INDEX_ROUTING_ALLOCATION_ENABLE, "none")).get();
        backwardsCluster().allowOnAllNodes(indices);
        logClusterState();
        boolean upgraded;
        do {
            logClusterState();
            CountResponse countResponse = client().prepareCount().get();
            assertHitCount(countResponse, numDocs);
            assertSimpleSort("num_double", "num_int");
            upgraded = backwardsCluster().upgradeOneNode();
            ensureYellow();
            countResponse = client().prepareCount().get();
            assertHitCount(countResponse, numDocs);
            for (int i = 0; i < numDocs; i++) {
                docs[i] = client().prepareIndex(indexForDoc[i], "type1", String.valueOf(i)).setSource("field1", English.intToEnglish(i), "num_int", randomInt(), "num_double", randomDouble());
            }
            indexRandom(true, docs);
        } while (upgraded);
        client().admin().indices().prepareUpdateSettings(indices).setSettings(ImmutableSettings.builder().put(EnableAllocationDecider.INDEX_ROUTING_ALLOCATION_ENABLE, "all")).get();
        CountResponse countResponse  = client().prepareCount().get();
        assertHitCount(countResponse, numDocs);
        assertSimpleSort("num_double", "num_int");

        String[] newIndices = new String[randomIntBetween(1,3)];

        for (int i = 0; i < newIndices.length; i++) {
            newIndices[i] = "new_index" + i;
            createIndex(newIndices[i]);
        }
        assertVersionCreated(Version.CURRENT, newIndices); // new indices are all created with the new version
        assertVersionCreated(compatibilityVersion(), indices);
    }

    public void assertVersionCreated(Version version, String... index) {
        GetSettingsResponse getSettingsResponse = client().admin().indices().prepareGetSettings(index).get();
        ImmutableOpenMap<String,Settings> indexToSettings = getSettingsResponse.getIndexToSettings();
        for (int i = 0; i < index.length; i++) {
            Settings settings = indexToSettings.get(index[i]);
            assertThat(settings.getAsVersion(IndexMetaData.SETTING_VERSION_CREATED, null), notNullValue());
            assertThat(settings.getAsVersion(IndexMetaData.SETTING_VERSION_CREATED, null), equalTo(version));
        }
    }

    @Test
    public void testUnsupportedFeatures() throws IOException {
        if (compatibilityVersion().before(Version.V_1_3_0)) {
            XContentBuilder mapping = XContentBuilder.builder(JsonXContent.jsonXContent)
                    .startObject()
                        .startObject("type")
                            .startObject(FieldNamesFieldMapper.NAME)
                               // by setting randomly index to no we also test the pre-1.3 behavior
                                .field("index", randomFrom("no", "not_analyzed"))
                                .field("store", randomFrom("no", "yes"))
                            .endObject()
                        .endObject()
                    .endObject();

            try {
                assertAcked(prepareCreate("test").
                    setSettings(ImmutableSettings.builder().put("index.routing.allocation.exclude._name", backwardsCluster().newNodePattern()).put(indexSettings()))
                    .addMapping("type", mapping));
            } catch (MapperParsingException ex) {
                if (getMasterVersion().onOrAfter(Version.V_1_3_0)) {
                    assertThat(ex.getCause(), instanceOf(ElasticsearchIllegalArgumentException.class));
                    assertThat(ex.getCause().getMessage(), equalTo("type=_field_names is not supported on indices created before version 1.3.0 is your cluster running multiple datanode versions?"));
                } else {
                    assertThat(ex.getCause(), instanceOf(MapperParsingException.class));
                    assertThat(ex.getCause().getMessage(), startsWith("Root type mapping not empty after parsing!"));
                }
            }
        }

    }

    /**
     * This filter had a major upgrade in 1.3 where we started to index the field names. Lets see if they still work as expected...
     * this test is basically copied from SimpleQueryTests...
     */
    @Test
    public void testExistsFilter() throws IOException, ExecutionException, InterruptedException {
        assumeTrue("this test fails often with 1.0.3 skipping for now....",compatibilityVersion().onOrAfter(Version.V_1_1_0));
        for (;;)  {
            createIndex("test");
            ensureYellow();
            indexRandom(true,
                    client().prepareIndex("test", "type1", "1").setSource(jsonBuilder().startObject().startObject("obj1").field("obj1_val", "1").endObject().field("x1", "x_1").field("field1", "value1_1").field("field2", "value2_1").endObject()),
                    client().prepareIndex("test", "type1", "2").setSource(jsonBuilder().startObject().startObject("obj1").field("obj1_val", "1").endObject().field("x2", "x_2").field("field1", "value1_2").endObject()),
                    client().prepareIndex("test", "type1", "3").setSource(jsonBuilder().startObject().startObject("obj2").field("obj2_val", "1").endObject().field("y1", "y_1").field("field2", "value2_3").endObject()),
                    client().prepareIndex("test", "type1", "4").setSource(jsonBuilder().startObject().startObject("obj2").field("obj2_val", "1").endObject().field("y2", "y_2").field("field3", "value3_4").endObject()));

            CountResponse countResponse = client().prepareCount().setQuery(filteredQuery(matchAllQuery(), existsFilter("field1"))).get();
            assertHitCount(countResponse, 2l);

            countResponse = client().prepareCount().setQuery(constantScoreQuery(existsFilter("field1"))).get();
            assertHitCount(countResponse, 2l);

            countResponse = client().prepareCount().setQuery(queryString("_exists_:field1")).get();
            assertHitCount(countResponse, 2l);

            countResponse = client().prepareCount().setQuery(filteredQuery(matchAllQuery(), existsFilter("field2"))).get();
            assertHitCount(countResponse, 2l);

            countResponse = client().prepareCount().setQuery(filteredQuery(matchAllQuery(), existsFilter("field3"))).get();
            assertHitCount(countResponse, 1l);

            // wildcard check
            countResponse = client().prepareCount().setQuery(filteredQuery(matchAllQuery(), existsFilter("x*"))).get();
            assertHitCount(countResponse, 2l);

            // object check
            countResponse = client().prepareCount().setQuery(filteredQuery(matchAllQuery(), existsFilter("obj1"))).get();
            assertHitCount(countResponse, 2l);

            countResponse = client().prepareCount().setQuery(filteredQuery(matchAllQuery(), missingFilter("field1"))).get();
            assertHitCount(countResponse, 2l);

            countResponse = client().prepareCount().setQuery(filteredQuery(matchAllQuery(), missingFilter("field1"))).get();
            assertHitCount(countResponse, 2l);

            countResponse = client().prepareCount().setQuery(constantScoreQuery(missingFilter("field1"))).get();
            assertHitCount(countResponse, 2l);

            countResponse = client().prepareCount().setQuery(queryString("_missing_:field1")).get();
            assertHitCount(countResponse, 2l);

            // wildcard check
            countResponse = client().prepareCount().setQuery(filteredQuery(matchAllQuery(), missingFilter("x*"))).get();
            assertHitCount(countResponse, 2l);

            // object check
            countResponse = client().prepareCount().setQuery(filteredQuery(matchAllQuery(), missingFilter("obj1"))).get();
            assertHitCount(countResponse, 2l);
            if (!backwardsCluster().upgradeOneNode()) {
                 break;
            }
            ensureYellow();
            assertVersionCreated(compatibilityVersion(), "test"); // we had an old node in the cluster so we have to be on the compat version
            assertAcked(client().admin().indices().prepareDelete("test"));
        }

        assertVersionCreated(Version.CURRENT, "test"); // after upgrade we have current version
    }


    public Version getMasterVersion() {
        return client().admin().cluster().prepareState().get().getState().nodes().masterNode().getVersion();
    }

    @Test
    public void testSnapshotAndRestore() throws ExecutionException, InterruptedException, IOException {
        logger.info("-->  creating repository");
        assertAcked(client().admin().cluster().preparePutRepository("test-repo")
                .setType("fs").setSettings(ImmutableSettings.settingsBuilder()
                        .put("location", newTempDir(LifecycleScope.SUITE).getAbsolutePath())
                        .put("compress", randomBoolean())
                        .put("chunk_size", randomIntBetween(100, 1000))));
        String[] indices = new String[randomIntBetween(1,5)];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = "index_" + i;
            createIndex(indices[i]);
        }
        ensureYellow();
        logger.info("--> indexing some data");
        IndexRequestBuilder[] builders = new IndexRequestBuilder[randomIntBetween(10, 200)];
        for (int i = 0; i < builders.length; i++) {
            builders[i] = client().prepareIndex(RandomPicks.randomFrom(getRandom(), indices), "foo", Integer.toString(i)).setSource("{ \"foo\" : \"bar\" } ");
        }
        indexRandom(true, builders);
        assertThat(client().prepareCount(indices).get().getCount(), equalTo((long)builders.length));
        long[] counts = new long[indices.length];
        for (int i = 0; i < indices.length; i++) {
            counts[i] = client().prepareCount(indices[i]).get().getCount();
        }

        logger.info("--> snapshot");
        CreateSnapshotResponse createSnapshotResponse = client().admin().cluster().prepareCreateSnapshot("test-repo", "test-snap").setWaitForCompletion(true).setIndices("index_*").get();
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), greaterThan(0));
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), equalTo(createSnapshotResponse.getSnapshotInfo().totalShards()));

        assertThat(client().admin().cluster().prepareGetSnapshots("test-repo").setSnapshots("test-snap").get().getSnapshots().get(0).state(), equalTo(SnapshotState.SUCCESS));

        logger.info("--> delete some data");
        int howMany = randomIntBetween(1, builders.length);
        
        for (int i = 0; i < howMany; i++) {
            IndexRequestBuilder indexRequestBuilder = RandomPicks.randomFrom(getRandom(), builders);
            IndexRequest request = indexRequestBuilder.request();
            client().prepareDelete(request.index(), request.type(), request.id()).get();
        }
        refresh();
        final long numDocs = client().prepareCount(indices).get().getCount();
        assertThat(client().prepareCount(indices).get().getCount(), lessThan((long)builders.length));


        client().admin().indices().prepareUpdateSettings(indices).setSettings(ImmutableSettings.builder().put(EnableAllocationDecider.INDEX_ROUTING_ALLOCATION_ENABLE, "none")).get();
        backwardsCluster().allowOnAllNodes(indices);
        logClusterState();
        boolean upgraded;
        do {
            logClusterState();
            CountResponse countResponse = client().prepareCount().get();
            assertHitCount(countResponse, numDocs);
            upgraded = backwardsCluster().upgradeOneNode();
            ensureYellow();
            countResponse = client().prepareCount().get();
            assertHitCount(countResponse, numDocs);
        } while (upgraded);
        client().admin().indices().prepareUpdateSettings(indices).setSettings(ImmutableSettings.builder().put(EnableAllocationDecider.INDEX_ROUTING_ALLOCATION_ENABLE, "all")).get();

        logger.info("--> close indices");

        client().admin().indices().prepareClose(indices).get();

        logger.info("--> restore all indices from the snapshot");
        RestoreSnapshotResponse restoreSnapshotResponse = client().admin().cluster().prepareRestoreSnapshot("test-repo", "test-snap").setWaitForCompletion(true).execute().actionGet();
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));

        ensureYellow();
        assertThat(client().prepareCount(indices).get().getCount(), equalTo((long)builders.length));
        for (int i = 0; i < indices.length; i++) {
            assertThat(counts[i], equalTo(client().prepareCount(indices[i]).get().getCount()));
        }

        // Test restore after index deletion
        logger.info("--> delete indices");
        String index = RandomPicks.randomFrom(getRandom(), indices);
        cluster().wipeIndices(index);
        logger.info("--> restore one index after deletion");
        restoreSnapshotResponse = client().admin().cluster().prepareRestoreSnapshot("test-repo", "test-snap").setWaitForCompletion(true).setIndices(index).execute().actionGet();
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));
        ensureYellow();
        assertThat(client().prepareCount(indices).get().getCount(), equalTo((long)builders.length));
        for (int i = 0; i < indices.length; i++) {
            assertThat(counts[i], equalTo(client().prepareCount(indices[i]).get().getCount()));
        }
    }

    @Test
    public void testDeleteByQuery() throws ExecutionException, InterruptedException {
        createIndex("test");
        ensureYellow("test");

        int numDocs = iterations(10, 50);
        IndexRequestBuilder[] indexRequestBuilders = new IndexRequestBuilder[numDocs + 1];
        for (int i = 0; i < numDocs; i++) {
            indexRequestBuilders[i] = client().prepareIndex("test", "test", Integer.toString(i)).setSource("field", "value");
        }
        indexRequestBuilders[numDocs] = client().prepareIndex("test", "test", Integer.toString(numDocs)).setSource("field", "other_value");
        indexRandom(true, indexRequestBuilders);

        SearchResponse searchResponse = client().prepareSearch("test").get();
        assertThat(searchResponse.getHits().totalHits(), equalTo((long)numDocs + 1));

        DeleteByQueryResponse deleteByQueryResponse = client().prepareDeleteByQuery("test").setQuery(QueryBuilders.termQuery("field", "value")).get();
        assertThat(deleteByQueryResponse.getIndices().size(), equalTo(1));
        for (IndexDeleteByQueryResponse indexDeleteByQueryResponse : deleteByQueryResponse) {
            assertThat(indexDeleteByQueryResponse.getIndex(), equalTo("test"));
            assertThat(indexDeleteByQueryResponse.getFailures().length, equalTo(0));
        }

        refresh();
        searchResponse = client().prepareSearch("test").get();
        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));
    }

    @Test
    public void testDeleteRoutingRequired() throws ExecutionException, InterruptedException, IOException {
        assertAcked(prepareCreate("test").addMapping("test",
                XContentFactory.jsonBuilder().startObject().startObject("test").startObject("_routing").field("required", true).endObject().endObject().endObject()));
        ensureYellow("test");

        int numDocs = iterations(10, 50);
        IndexRequestBuilder[] indexRequestBuilders = new IndexRequestBuilder[numDocs];
        for (int i = 0; i < numDocs - 2; i++) {
            indexRequestBuilders[i] =  client().prepareIndex("test", "test", Integer.toString(i))
                    .setRouting(randomAsciiOfLength(randomIntBetween(1, 10))).setSource("field", "value");
        }
        String firstDocId = Integer.toString(numDocs - 2);
        indexRequestBuilders[numDocs - 2] = client().prepareIndex("test", "test", firstDocId)
                .setRouting("routing").setSource("field", "value");
        String secondDocId = Integer.toString(numDocs - 1);
        String secondRouting = randomAsciiOfLength(randomIntBetween(1, 10));
        indexRequestBuilders[numDocs - 1] = client().prepareIndex("test", "test", secondDocId)
                .setRouting(secondRouting).setSource("field", "value");

        indexRandom(true, indexRequestBuilders);

        SearchResponse searchResponse = client().prepareSearch("test").get();
        assertThat(searchResponse.getHits().totalHits(), equalTo((long)numDocs));

        //use routing
        DeleteResponse deleteResponse = client().prepareDelete("test", "test", firstDocId).setRouting("routing").get();
        assertThat(deleteResponse.isFound(), equalTo(true));
        GetResponse getResponse = client().prepareGet("test", "test", firstDocId).setRouting("routing").get();
        assertThat(getResponse.isExists(), equalTo(false));
        refresh();
        searchResponse = client().prepareSearch("test").get();
        assertThat(searchResponse.getHits().totalHits(), equalTo((long)numDocs - 1));

        //don't use routing and trigger a broadcast delete
        deleteResponse = client().prepareDelete("test", "test", secondDocId).get();
        assertThat(deleteResponse.isFound(), equalTo(true));
        getResponse = client().prepareGet("test", "test", secondDocId).setRouting(secondRouting).get();
        assertThat(getResponse.isExists(), equalTo(false));
        refresh();
        searchResponse = client().prepareSearch("test").get();
        assertThat(searchResponse.getHits().totalHits(), equalTo((long)numDocs - 2));
    }
}
