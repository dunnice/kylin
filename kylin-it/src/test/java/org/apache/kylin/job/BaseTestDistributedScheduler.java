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

package org.apache.kylin.job;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import org.apache.commons.lang.StringUtils;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.util.ClassUtil;
import org.apache.kylin.job.engine.JobEngineConfig;
import org.apache.kylin.job.execution.AbstractExecutable;
import org.apache.kylin.job.execution.ExecutableState;
import org.apache.kylin.job.impl.threadpool.DistributedScheduler;
import org.apache.kylin.job.manager.ExecutableManager;
import org.apache.kylin.storage.hbase.HBaseConnection;
import org.apache.kylin.storage.hbase.util.ZookeeperDistributedJobLock;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;

public class BaseTestDistributedScheduler {
    static ExecutableManager jobService;
    static ZookeeperDistributedJobLock jobLock;
    static DistributedScheduler scheduler1;
    static DistributedScheduler scheduler2;
    static KylinConfig kylinConfig1;
    static KylinConfig kylinConfig2;
    static CuratorFramework zkClient;

    static final String SEGMENT_ID = "segmentId";
    static final String segmentId1 = "segmentId1";
    static final String segmentId2 = "segmentId2";
    static final String serverName1 = "serverName1";
    static final String serverName2 = "serverName2";
    static final String ZOOKEEPER_LOCK_PATH = "/kylin/job_engine/lock";
    static final String confSrcPath = "../examples/test_case_data/sandbox/kylin.properties";
    static final String confDstPath = "../examples/kylin.properties";
    static final String SANDBOX_TEST_DATA = "../examples/test_case_data/sandbox";

    private static final Logger logger = LoggerFactory.getLogger(BaseTestDistributedScheduler.class);

    static {
        try {
            ClassUtil.addClasspath(new File(SANDBOX_TEST_DATA).getAbsolutePath());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @BeforeClass
    public static void setup() throws Exception {
        staticCreateTestMetadata(SANDBOX_TEST_DATA);
        System.setProperty("kylin.job.controller.lock", "org.apache.kylin.storage.hbase.util.ZookeeperDistributedJobLock");

        initZk();

        kylinConfig1 = KylinConfig.getInstanceFromEnv();
        jobService = ExecutableManager.getInstance(kylinConfig1);
        for (String jobId : jobService.getAllJobIds()) {
            jobService.deleteJob(jobId);
        }

        jobLock = new ZookeeperDistributedJobLock();
        scheduler1 = DistributedScheduler.getInstance(kylinConfig1);
        scheduler1.setServerName(serverName1);
        scheduler1.init(new JobEngineConfig(kylinConfig1), jobLock);
        if (!scheduler1.hasStarted()) {
            throw new RuntimeException("scheduler1 not started");
        }

        String absoluteConfSrcPath = new File(confSrcPath).getAbsolutePath();
        String absoluteConfDstPath = new File(confDstPath).getAbsolutePath();
        copyFile(absoluteConfSrcPath, absoluteConfDstPath);
        kylinConfig2 = KylinConfig.createInstanceFromUri(absoluteConfDstPath);

        scheduler2 = DistributedScheduler.getInstance(kylinConfig2);
        scheduler2.setServerName(serverName2);
        scheduler2.init(new JobEngineConfig(kylinConfig2), jobLock);
        if (!scheduler2.hasStarted()) {
            throw new RuntimeException("scheduler2 not started");
        }

        Thread.sleep(10000);
    }

    @AfterClass
    public static void after() throws Exception {
        System.clearProperty(KylinConfig.KYLIN_CONF);
        System.clearProperty("kylin.job.controller.lock");

        deleteFile(confDstPath);
    }

    private static void staticCreateTestMetadata(String kylinConfigFolder) {
        KylinConfig.destroyInstance();

        if (System.getProperty(KylinConfig.KYLIN_CONF) == null && System.getenv(KylinConfig.KYLIN_CONF) == null)
            System.setProperty(KylinConfig.KYLIN_CONF, kylinConfigFolder);
    }

    void waitForJobFinish(String jobId) {
        while (true) {
            AbstractExecutable job = jobService.getJob(jobId);
            final ExecutableState status = job.getStatus();
            if (status == ExecutableState.SUCCEED || status == ExecutableState.ERROR || status == ExecutableState.STOPPED || status == ExecutableState.DISCARDED) {
                break;
            } else {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    void waitForJobStatus(String jobId, ExecutableState state, long interval) {
        while (true) {
            AbstractExecutable job = jobService.getJob(jobId);
            if (state == job.getStatus()) {
                break;
            } else {
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    boolean lock(ZookeeperDistributedJobLock jobLock, String cubeName, String serverName) {
        return jobLock.lockWithName(cubeName, serverName);
    }

    private static void initZk() {
        String zkConnectString = getZKConnectString();
        if (StringUtils.isEmpty(zkConnectString)) {
            throw new IllegalArgumentException("ZOOKEEPER_QUORUM is empty!");
        }
        RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, 3);
        zkClient = CuratorFrameworkFactory.newClient(zkConnectString, retryPolicy);
        zkClient.start();
    }

    private static String getZKConnectString() {
        Configuration conf = HBaseConnection.getCurrentHBaseConfiguration();
        final String serverList = conf.get(HConstants.ZOOKEEPER_QUORUM);
        final String port = conf.get(HConstants.ZOOKEEPER_CLIENT_PORT);
        return org.apache.commons.lang3.StringUtils.join(Iterables.transform(Arrays.asList(serverList.split(",")), new Function<String, String>() {
            @Nullable
            @Override
            public String apply(String input) {
                return input + ":" + port;
            }
        }), ",");
    }

    String getServerName(String cubeName) {
        String lockPath = getLockPath(cubeName);
        String serverName = null;
        if (zkClient.getState().equals(CuratorFrameworkState.STARTED)) {
            try {
                if (zkClient.checkExists().forPath(lockPath) != null) {
                    byte[] data = zkClient.getData().forPath(lockPath);
                    serverName = new String(data, Charset.forName("UTF-8"));
                }
            } catch (Exception e) {
                logger.error("get the serverName failed", e);
            }
        }
        return serverName;
    }

    private String getLockPath(String pathName) {
        return ZOOKEEPER_LOCK_PATH + "/" + KylinConfig.getInstanceFromEnv().getMetadataUrlPrefix() + "/" + pathName;
    }

    private static void copyFile(String srcPath, String dstPath) {
        try {
            File srcFile = new File(srcPath);
            File dstFile = new File(dstPath);
            Files.copy(srcFile.toPath(), dstFile.toPath());
        } catch (Exception e) {
            logger.error("copy the file failed", e);
        }
    }

    private static void deleteFile(String path) {
        try {
            Files.delete(new File(path).toPath());
        } catch (Exception e) {
            logger.error("delete the file failed", e);
        }
    }
}
