// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/utframe/AnotherDemoTest.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.utframe;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.starrocks.analysis.CreateDbStmt;
import com.starrocks.analysis.CreateTableStmt;
import com.starrocks.catalog.Catalog;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.DiskInfo;
import com.starrocks.catalog.OlapTable;
import com.starrocks.common.DdlException;
import com.starrocks.common.FeConstants;
import com.starrocks.common.util.UUIDUtil;
import com.starrocks.planner.OlapScanNode;
import com.starrocks.planner.PlanFragment;
import com.starrocks.planner.Planner;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.StmtExecutor;
import com.starrocks.system.Backend;
import com.starrocks.system.SystemInfoService;
import com.starrocks.thrift.TNetworkAddress;
import com.starrocks.utframe.MockedBackendFactory.DefaultBeThriftServiceImpl;
import com.starrocks.utframe.MockedBackendFactory.DefaultHeartbeatServiceImpl;
import com.starrocks.utframe.MockedBackendFactory.DefaultPBackendServiceImpl;
import com.starrocks.utframe.MockedFrontend.EnvVarNotSetException;
import com.starrocks.utframe.MockedFrontend.FeStartException;
import com.starrocks.utframe.MockedFrontend.NotInitException;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/*
 * This demo is mainly used to confirm that
 * repeatedly starting FE and BE in 2 UnitTest will not cause conflict
 */
public class AnotherDemoTest {

    private static int fe_http_port;
    private static int fe_rpc_port;
    private static int fe_query_port;
    private static int fe_edit_log_port;

    private static int be_heartbeat_port;
    private static int be_thrift_port;
    private static int be_brpc_port;
    private static int be_http_port;

    // use a unique dir so that it won't be conflict with other unit test which
    // may also start a Mocked Frontend
    private static String runningDirBase = "fe";
    private static String runningDir = runningDirBase + "/mocked/AnotherDemoTest/" + UUID.randomUUID().toString() + "/";

    @BeforeClass
    public static void beforeClass() throws EnvVarNotSetException, IOException,
            FeStartException, NotInitException, DdlException, InterruptedException {
        FeConstants.default_scheduler_interval_millisecond = 10;
        // get STARROCKS_HOME
        String starRocksHome = System.getenv("STARROCKS_HOME");
        if (Strings.isNullOrEmpty(starRocksHome)) {
            starRocksHome = Files.createTempDirectory("STARROCKS_HOME").toAbsolutePath().toString();
        }

        getPorts();

        // start fe in "STARROCKS_HOME/fe/mocked/"
        MockedFrontend frontend = MockedFrontend.getInstance();
        Map<String, String> feConfMap = Maps.newHashMap();
        // set additional fe config
        feConfMap.put("http_port", String.valueOf(fe_http_port));
        feConfMap.put("rpc_port", String.valueOf(fe_rpc_port));
        feConfMap.put("query_port", String.valueOf(fe_query_port));
        feConfMap.put("edit_log_port", String.valueOf(fe_edit_log_port));
        feConfMap.put("tablet_create_timeout_second", "10");
        frontend.init(starRocksHome + "/" + runningDir, feConfMap);
        frontend.start(new String[0]);

        // start be
        MockedBackend backend = MockedBackendFactory.createBackend("127.0.0.1",
                be_heartbeat_port, be_thrift_port, be_brpc_port, be_http_port,
                new DefaultHeartbeatServiceImpl(be_thrift_port, be_http_port, be_brpc_port),
                new DefaultBeThriftServiceImpl(), new DefaultPBackendServiceImpl());
        backend.setFeAddress(new TNetworkAddress("127.0.0.1", frontend.getRpcPort()));
        backend.start();

        // add be
        Backend be = new Backend(10001, backend.getHost(), backend.getHeartbeatPort());
        Map<String, DiskInfo> disks = Maps.newHashMap();
        DiskInfo diskInfo1 = new DiskInfo("/path1");
        diskInfo1.setTotalCapacityB(1000000);
        diskInfo1.setAvailableCapacityB(500000);
        diskInfo1.setDataUsedCapacityB(480000);
        disks.put(diskInfo1.getRootPath(), diskInfo1);
        be.setDisks(ImmutableMap.copyOf(disks));
        be.setAlive(true);
        be.setOwnerClusterName(SystemInfoService.DEFAULT_CLUSTER);
        Catalog.getCurrentSystemInfo().addBackend(be);

        // sleep to wait first heartbeat
        for (int retry = 0; retry < 5; retry++) {
            if (Catalog.getCurrentSystemInfo().getBackend(10001).getBePort() != -1) {
                break;
            } else {
                Thread.sleep(5000);
            }
        }
    }

    @AfterClass
    public static void TearDown() {
        UtFrameUtils.cleanStarRocksFeDir(runningDirBase);
    }

    // generate all port from valid ports
    private static void getPorts() {
        fe_http_port = UtFrameUtils.findValidPort();
        fe_rpc_port = UtFrameUtils.findValidPort();
        fe_query_port = UtFrameUtils.findValidPort();
        fe_edit_log_port = UtFrameUtils.findValidPort();

        be_heartbeat_port = UtFrameUtils.findValidPort();
        be_thrift_port = UtFrameUtils.findValidPort();
        be_brpc_port = UtFrameUtils.findValidPort();
        be_http_port = UtFrameUtils.findValidPort();
    }

    @Test
    public void testCreateDbAndTable() throws Exception {
        // 1. create connect context
        ConnectContext ctx = UtFrameUtils.createDefaultCtx();
        ctx.setQueryId(UUIDUtil.genUUID());
        // 2. create database db1
        String createDbStmtStr = "create database db1;";
        CreateDbStmt createDbStmt = (CreateDbStmt) UtFrameUtils.parseAndAnalyzeStmt(createDbStmtStr, ctx);
        Catalog.getCurrentCatalog().createDb(createDbStmt);
        System.out.println(Catalog.getCurrentCatalog().getDbNames());
        // 3. create table tbl1
        String createTblStmtStr =
                "create table db1.tbl1(k1 int) distributed by hash(k1) buckets 3 properties('replication_num' = '1');";
        CreateTableStmt createTableStmt = (CreateTableStmt) UtFrameUtils.parseAndAnalyzeStmt(createTblStmtStr, ctx);
        Catalog.getCurrentCatalog().createTable(createTableStmt);
        // 4. get and test the created db and table
        Database db = Catalog.getCurrentCatalog().getDb("default_cluster:db1");
        Assert.assertNotNull(db);
        db.readLock();
        try {
            OlapTable tbl = (OlapTable) db.getTable("tbl1");
            Assert.assertNotNull(tbl);
            System.out.println(tbl.getName());
            Assert.assertEquals("StarRocks", tbl.getEngine());
            Assert.assertEquals(1, tbl.getBaseSchema().size());
        } finally {
            db.readUnlock();
        }
        // 5. query
        // TODO: we can not process real query for now. So it has to be a explain query
        String queryStr = "explain select * from db1.tbl1";
        StmtExecutor stmtExecutor = new StmtExecutor(ctx, queryStr);
        stmtExecutor.execute();
        Planner planner = stmtExecutor.planner();
        List<PlanFragment> fragments = planner.getFragments();
        Assert.assertEquals(1, fragments.size());
        PlanFragment fragment = fragments.get(0);
        Assert.assertTrue(fragment.getPlanRoot() instanceof OlapScanNode);
        Assert.assertEquals(0, fragment.getChildren().size());
    }
}
