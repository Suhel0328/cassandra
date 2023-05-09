/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.distributed.test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import com.google.common.collect.Sets;
import org.junit.Test;

import org.apache.cassandra.distributed.Cluster;
import org.apache.cassandra.distributed.api.ConsistencyLevel;
import org.apache.cassandra.distributed.api.Feature;
import org.apache.cassandra.distributed.api.IInstanceConfig;
import org.apache.cassandra.distributed.api.IInvokableInstance;
import org.apache.cassandra.distributed.api.TokenSupplier;
import org.apache.cassandra.distributed.shared.NetworkTopology;
import org.apache.cassandra.tcm.ClusterMetadata;
import org.apache.cassandra.utils.Pair;

import static com.google.common.collect.Lists.newArrayList;
import static org.apache.cassandra.config.CassandraRelevantProperties.BOOTSTRAP_SKIP_SCHEMA_CHECK;
import static org.apache.cassandra.distributed.shared.ClusterUtils.awaitRingJoin;
import static org.apache.cassandra.distributed.shared.ClusterUtils.replaceHostAndStart;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("unchecked")
public class TransientRangeMovementTest extends TestBaseImpl
{
    @Test
    public void testBootstrap() throws IOException, ExecutionException, InterruptedException
    {

        try (Cluster cluster = init(Cluster.build(3)
                                           .withTokenSupplier(new OPPTokens())
                                           .withNodeIdTopology(NetworkTopology.singleDcNetworkTopology(4, "dc0", "rack0"))
                                           .withConfig(conf -> conf.set("transient_replication_enabled","true")
                                                                   .set("partitioner", "OrderPreservingPartitioner")
                                                                   .set("hinted_handoff_enabled", "false")
                                                                   .with(Feature.NETWORK, Feature.GOSSIP))
                                           .start()))
        {
            populate(cluster);
            IInstanceConfig config = cluster.newInstanceConfig();
            config.set("auto_bootstrap", true);
            IInvokableInstance newInstance = cluster.bootstrap(config);
            newInstance.startup();
            cluster.forEach(i -> i.nodetoolResult("cleanup").asserts().success());

            assertAllContained(localStrs(cluster.get(1)),
                               newArrayList("22", "24", "26", "28", "30"),
                               Pair.create("00", "10"),
                               Pair.create("31", "50"));
            assertAllContained(localStrs(cluster.get(2)),
                               newArrayList("32", "34", "36", "38", "40"),
                               Pair.create("00", "20"),
                               Pair.create("41", "50"));
            assertAllContained(localStrs(cluster.get(3)),
                               newArrayList("00", "02", "04", "06", "08", "10", "42", "44", "46", "48"),
                               Pair.create("11", "30"));
            assertAllContained(localStrs(cluster.get(4)),
                               newArrayList("10", "12", "14", "16", "18", "20"),
                               Pair.create("21", "40"));
        }
    }

    @Test
    public void testReplace() throws IOException, ExecutionException, InterruptedException
    {

        try (Cluster cluster = init(Cluster.build(3)
                                           .withTokenSupplier(new OPPTokensReplace())
                                           .withNodeIdTopology(NetworkTopology.singleDcNetworkTopology(4, "dc0", "rack0"))
                                           .withConfig(conf -> conf.set("transient_replication_enabled","true")
                                                                   .set("partitioner", "OrderPreservingPartitioner")
                                                                   .with(Feature.NETWORK, Feature.GOSSIP))
                                           .start()))
        {
            populate(cluster);
            IInvokableInstance replacingNode = replaceHostAndStart(cluster, cluster.get(2), props -> {
                // since we have a downed host there might be a schema version which is old show up but
                // can't be fetched since the host is down...
                props.set(BOOTSTRAP_SKIP_SCHEMA_CHECK, true);
            });
            awaitRingJoin(cluster.get(1), replacingNode);
            awaitRingJoin(replacingNode, cluster.get(1));
            cluster.forEach(i -> i.nodetoolResult("cleanup").asserts().success());
            assertAllContained(localStrs(cluster.get(1)),
                               newArrayList("12", "14", "16", "18", "20"),
                               Pair.create("00", "10"),
                               Pair.create("21", "50"));
            assertAllContained(localStrs(cluster.get(4)),
                               newArrayList("22", "24", "26", "28", "30"),
                               Pair.create("00", "20"),
                               Pair.create("31", "50"));
            assertAllContained(localStrs(cluster.get(3)),
                               newArrayList("00", "02", "04", "06", "08", "10",
                                                  "32", "34", "36", "38", "40", "42", "44", "46", "48"),
                               Pair.create("11", "30"));
        }
    }

    @Test
    public void testLeave() throws IOException, ExecutionException, InterruptedException
    {

        try (Cluster cluster = init(Cluster.build(4)
                                           .withTokenSupplier(new OPPTokens())
                                           .withNodeIdTopology(NetworkTopology.singleDcNetworkTopology(4, "dc0", "rack0"))
                                           .withConfig(conf -> conf.set("transient_replication_enabled","true")
                                                                   .set("partitioner", "OrderPreservingPartitioner") // just makes it easier to read the tokens in the log
                                                                   .with(Feature.NETWORK, Feature.GOSSIP))
                                           .start()))
        {
            populate(cluster);
            cluster.get(4).nodetoolResult("decommission", "--force").asserts().success();
            cluster.forEach(i -> i.nodetoolResult("cleanup"));
            assertAllContained(localStrs(cluster.get(1)),
                               newArrayList("12", "14", "16", "18", "20"),
                               Pair.create("00", "10"),
                               Pair.create("21", "50"));
            assertAllContained(localStrs(cluster.get(2)),
                               newArrayList("22", "24", "26", "28", "30"),
                               Pair.create("00", "20"),
                               Pair.create("31", "50"));
            assertAllContained(localStrs(cluster.get(3)),
                               newArrayList("00", "02", "04", "06", "08", "10",
                                                  "32", "34", "36", "38", "40", "42", "44", "46", "48"),
                               Pair.create("11", "30"));
        }
    }



    @Test
    public void testMoveBackward() throws IOException, ExecutionException, InterruptedException
    {
        try (Cluster cluster = init(Cluster.build(4)
                                           .withTokenSupplier(new OPPTokens())
                                           .withNodeIdTopology(NetworkTopology.singleDcNetworkTopology(4, "dc0", "rack0"))
                                           .withConfig(conf -> conf.set("transient_replication_enabled","true")
                                                                   .set("partitioner", "OrderPreservingPartitioner") // just makes it easier to read the tokens in the log
                                                                   .with(Feature.NETWORK, Feature.GOSSIP))
                                           .start()))
        {
            populate(cluster);
            cluster.get(3).nodetoolResult("move", "25").asserts().success();
            cluster.forEach(i -> i.nodetoolResult("cleanup").asserts().success());

            assertAllContained(localStrs(cluster.get(1)),
                               newArrayList("22", "24"),
                               Pair.create("00", "10"),
                               Pair.create("26", "50"));
            assertAllContained(localStrs(cluster.get(2)),
                               newArrayList("26", "28", "30", "32", "34", "36", "38", "40"),
                               Pair.create("00", "20"),
                               Pair.create("41", "50"));
            assertAllContained(localStrs(cluster.get(3)),
                               newArrayList("42", "44", "46", "48", "00", "02", "04", "06", "08", "10"),
                               Pair.create("11", "25"));
            assertAllContained(localStrs(cluster.get(4)),
                               newArrayList("12", "14", "16", "18", "20"),
                               Pair.create("21", "40"));
        }
    }

    @Test
    public void testMoveForward() throws IOException, ExecutionException, InterruptedException
    {

        try (Cluster cluster = init(Cluster.build(4)
                                           .withTokenSupplier(new OPPTokens())
                                           .withNodeIdTopology(NetworkTopology.singleDcNetworkTopology(4, "dc0", "rack0"))
                                           .withConfig(conf -> conf.set("transient_replication_enabled","true")
                                                                   .set("partitioner", "OrderPreservingPartitioner") // just makes it easier to read the tokens in the log
                                                                   .set("hinted_handoff_enabled", "false")
                                                                   .with(Feature.NETWORK, Feature.GOSSIP))
                                           .start()))
        {
            populate(cluster);
            cluster.get(1).nodetoolResult("move", "15").asserts().success();
            cluster.forEach((i) -> i.nodetoolResult("cleanup").asserts().success());
            assertAllContained(localStrs(cluster.get(1)),
                               newArrayList("22", "24", "26", "28", "30"),
                               Pair.create("00", "15"),
                               Pair.create("31", "50"));
            assertAllContained(localStrs(cluster.get(2)),
                               newArrayList("32", "34", "36", "38", "40"),
                               Pair.create("00", "20"),
                               Pair.create("41", "50"));
            assertAllContained(localStrs(cluster.get(3)),
                               newArrayList("42", "44", "46", "48", "00", "02", "04", "06", "08", "10", "12", "14"),
                               Pair.create("16", "30"));
            assertAllContained(localStrs(cluster.get(4)),
                               newArrayList("16", "18", "20"),
                               Pair.create("21", "40"));
        }
    }

    @Test
    public void testRebuild() throws ExecutionException, InterruptedException, IOException
    {
        try (Cluster cluster = init(Cluster.build(3)
                                           .withTokenSupplier(new OPPTokens())
                                           .withNodeIdTopology(NetworkTopology.singleDcNetworkTopology(4, "dc0", "rack0"))
                                           .withConfig(conf -> conf.set("transient_replication_enabled","true")
                                                                   .set("partitioner", "OrderPreservingPartitioner")
                                                                   .set("hinted_handoff_enabled", "false")
                                                                   .with(Feature.NETWORK, Feature.GOSSIP))
                                           .start()))
        {
            populate(cluster);
            IInstanceConfig config = cluster.newInstanceConfig();
            config.set("auto_bootstrap", false);
            IInvokableInstance newInstance = cluster.bootstrap(config);
            newInstance.startup();
            cluster.forEach(i -> i.nodetoolResult("cleanup").asserts().success());
            cluster.get(4).nodetoolResult("rebuild", "-ks", "tr", "--tokens", "(15, 18],(20,25]").asserts().success();
            assertAllContained(localStrs(cluster.get(4)),
                               newArrayList(),
                               Pair.create("16", "18"),
                               Pair.create("21", "25"));
        }
    }

    @Test
    public void testRemoveNode() throws IOException, ExecutionException, InterruptedException
    {
        try (Cluster cluster = init(Cluster.build(4)
                                           .withTokenSupplier(new OPPTokens())
                                           .withNodeIdTopology(NetworkTopology.singleDcNetworkTopology(4, "dc0", "rack0"))
                                           .withConfig(conf -> conf.set("transient_replication_enabled","true")
                                                                   .set("partitioner", "OrderPreservingPartitioner")
                                                                   .set("hinted_handoff_enabled", "false")
                                                                   .with(Feature.NETWORK, Feature.GOSSIP))
                                           .start()))
        {
            populate(cluster);
            String nodeId = cluster.get(4).callOnInstance(() -> ClusterMetadata.current().myNodeId().toUUID().toString());
            cluster.get(4).shutdown().get();
            cluster.get(1).nodetoolResult("removenode", nodeId, "--force").asserts().success();
            cluster.forEach(i -> {
                if (i.config().num() != 4)
                    i.nodetoolResult("cleanup").asserts().success();
            });

            assertAllContained(localStrs(cluster.get(1)),
                               newArrayList("12", "14", "16", "18", "20"),
                               Pair.create("00", "10"),
                               Pair.create("21", "50"));
            assertAllContained(localStrs(cluster.get(2)),
                               newArrayList("22", "24", "26", "28", "30"),
                               Pair.create("00", "20"),
                               Pair.create("31", "50"));
            assertAllContained(localStrs(cluster.get(3)),
                               newArrayList("00", "02", "04", "06", "08", "10",
                                            "32", "34", "36", "38", "40", "42", "44", "46", "48"),
                               Pair.create("11", "30"));

        }
    }

    private void assertAllContained(List<String> current, List<String> expectedTransientKeys, Pair<String, String> ... ranges)
    {
        Set<String> cur = Sets.newHashSet(current);
        Set<String> expectTransient = Sets.newHashSet(expectedTransientKeys);
        for (int i = 0; i < 50; i++)
        {
            String key = toStr(i);
            if (contained(key, ranges))
                assertTrue("NOT IN CURRENT: " + key + " -- " + Arrays.toString(ranges), cur.remove(key));
            else if (expectTransient.remove(key))
                cur.remove(key);
            else
                assertFalse("SHOULD NOT BE ON NODE: " + key + " -- " + Arrays.toString(ranges) + ": " + current, cur.contains(key));
        }
        assertTrue(cur.toString(), cur.isEmpty());
        assertTrue(expectTransient.toString(), expectTransient.isEmpty());
    }

    List<String> localStrs(IInvokableInstance inst)
    {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < 50; i++)
        {
            Object[][] res = inst.executeInternal("select * from tr.x where id = ?", toStr(i));
            for (Object[] row : res)
                l.add((String) row[0]);
        }
        return l;
    }

    private String toStr(int x)
    {
        return (x < 10 ? "0" : "") + x;
    }

    //  ranges [a, b] (not (a, b]) for simplicity
    private boolean contained(String key, Pair<String, String> ... ranges)
    {
        for (Pair<String, String> range : ranges)
            if (range.left.compareTo(key) <= 0 && range.right.compareTo(key) >= 0)
                return true;
        return false;
    }
    private static class OPPTokens implements TokenSupplier
    {
        @Override
        public Collection<String> tokens(int i)
        {
            return Collections.singleton(String.valueOf(i*10));
        }
    }

    private static class OPPTokensReplace implements TokenSupplier
    {
        TokenSupplier base = new OPPTokens();
        @Override
        public Collection<String> tokens(int i)
        {
            if (i == 4)
                return base.tokens(2);
            return base.tokens(i);
        }
    }

    private void populate(Cluster cluster) throws ExecutionException, InterruptedException
    {
        cluster.schemaChange("create keyspace tr with replication = {'class':'NetworkTopologyStrategy', 'dc0':'3/1'}");
        cluster.schemaChange("create table tr.x (id varchar primary key) with read_repair = 'NONE'");
        for (int i = 0; i < 50; i++)
            cluster.coordinator(1).execute("insert into tr.x (id) values (?)", ConsistencyLevel.QUORUM, toStr(i));
        cluster.forEach((i) -> i.nodetoolResult("repair", "-pr", "tr").asserts().success());
        cluster.get(1).shutdown().get();
        for (int i = 0; i < 50; i += 2)
            cluster.coordinator(2).execute("insert into tr.x (id) values (?)", ConsistencyLevel.QUORUM, toStr(i));
        cluster.get(1).startup();
    }
}
