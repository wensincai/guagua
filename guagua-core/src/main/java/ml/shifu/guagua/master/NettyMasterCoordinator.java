/*
 * Copyright [2013-2015] eBay Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ml.shifu.guagua.master;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import ml.shifu.guagua.GuaguaConstants;
import ml.shifu.guagua.GuaguaRuntimeException;
import ml.shifu.guagua.io.Bytable;
import ml.shifu.guagua.io.BytableWrapper;
import ml.shifu.guagua.io.NettyBytableDecoder;
import ml.shifu.guagua.io.NettyBytableEncoder;
import ml.shifu.guagua.util.MemoryDiskList;
import ml.shifu.guagua.util.NetworkUtils;
import ml.shifu.guagua.util.NumberFormatUtils;
import ml.shifu.guagua.util.ReflectionUtils;
import ml.shifu.guagua.util.StringUtils;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.ChannelEvent;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelState;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A master coordinator to coordinate with workers through Netty server.
 * 
 * <p>
 * Master still updates results to Zookeeper znodes for fail-over. While workers sends results to master through Netty
 * server connection.
 * 
 * <p>
 * Worker results are persisted into {@link MemoryDiskList}, the reason is that for big model, limited memory may not be
 * enough to store all worker results in memory.
 */
public class NettyMasterCoordinator<MASTER_RESULT extends Bytable, WORKER_RESULT extends Bytable> extends
        AbstractMasterCoordinator<MASTER_RESULT, WORKER_RESULT> {

    private static final Logger LOG = LoggerFactory.getLogger(NettyMasterCoordinator.class);

    /**
     * A server instance used to communicate with all workers.
     */
    private ServerBootstrap messageServer;

    /**
     * Message server port used to for Netty server to communicate with workers.
     */
    private int messageServerPort;

    // TODO , this should be spilled to disk if big memory used
    /**
     * Worker results for each iteration. It should store each worker result in each iteration once.
     */
    private List<BytableWrapper> iterResults = Collections.synchronizedList(new ArrayList<BytableWrapper>());
    // private BytableMemoryDiskList<BytableWrapper> iterResults;

    /**
     * 'indexMap' used to store <containerId, index in iterResults> for search.
     */
    private Map<String, Integer> indexMap = Collections.synchronizedMap(new HashMap<String, Integer>());

    /**
     * Current iteration.
     */
    @SuppressWarnings("unused")
    private int currentInteration;

    @Override
    protected void initialize(Properties props) {
        super.initialize(props);
        initIterResults(props);
    }

    private void initIterResults(Properties props) {
        // BytableDiskList<BytableWrapper> bytableDiskList = new BytableDiskList<BytableWrapper>(
        // System.currentTimeMillis() + "", BytableWrapper.class.getName());
        // double memoryFraction = Double.valueOf(props.getProperty(
        // GuaguaConstants.GUAGUA_MASTER_WORKERESULTS_MEMORY_FRACTION,
        // GuaguaConstants.GUAGUA_MASTER_WORKERESULTS_DEFAULT_MEMORY_FRACTION));
        // long memoryStoreSize = (long) (Runtime.getRuntime().maxMemory() * memoryFraction);
        // this.iterResults = new BytableMemoryDiskList<BytableWrapper>(memoryStoreSize, bytableDiskList);
    }

    /**
     * Do initialization and fail-over checking before all iterations.
     */
    @Override
    public void preApplication(final MasterContext<MASTER_RESULT, WORKER_RESULT> context) {
        // Initialize zookeeper and other props
        initialize(context.getProps());

        // Fail over checking to check current iteration.
        new FailOverCommand(context).execute();

        // Start master netty server
        startNettyServer(context.getProps());

        // Create master initial znode with message server address.
        initMasterZnode(context);

        this.currentInteration = context.getCurrentIteration();

        if(!context.isInitIteration()) {
            // if not init step, return, because of no need initialize twice for fail-over task
            return;
        }

        clear(context.getProps());

        LOG.info("All workers are initiliazed successfully.");
    }

    /**
     * Clear all status before next iteration.
     */
    private void clear(Properties props) {
        // clear and wait for next iteration.
        // this.iterResults.close();
        this.iterResults.clear();
        this.initIterResults(props);
        this.indexMap.clear();
    }

    /**
     * Initialize master znode with master Netty server name <name:port>.
     */
    private void initMasterZnode(final MasterContext<MASTER_RESULT, WORKER_RESULT> context) {
        String znode = null;
        try {
            // create master init znode
            znode = getMasterBaseNode(context.getAppId()).toString();
            getZooKeeper().createExt(znode, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, true);
            znode = getCurrentMasterNode(context.getAppId(), GuaguaConstants.GUAGUA_INIT_STEP).toString();
            getZooKeeper().createExt(
                    znode,
                    (InetAddress.getLocalHost().getHostName() + ":" + NettyMasterCoordinator.this.messageServerPort)
                            .getBytes(Charset.forName("UTF-8")), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, false);
        } catch (KeeperException.NodeExistsException e) {
            LOG.warn("Node exists: {}", znode);
        } catch (Exception e) {
            throw new GuaguaRuntimeException(e);
        }
    }

    /**
     * Start netty server which is used to communicate with workers.
     */
    private void startNettyServer(Properties props) {
        this.messageServerPort = NumberFormatUtils.getInt(props.getProperty(GuaguaConstants.GUAGUA_NETTY_SEVER_PORT),
                GuaguaConstants.GUAGUA_NETTY_SEVER_DEFAULT_PORT);
        this.messageServerPort = NetworkUtils.getValidServerPort(this.messageServerPort);
        this.messageServer = new ServerBootstrap(new NioServerSocketChannelFactory(
                Executors.newFixedThreadPool(GuaguaConstants.GUAGUA_NETTY_SERVER_DEFAULT_THREAD_COUNT / 2),
                Executors.newFixedThreadPool(GuaguaConstants.GUAGUA_NETTY_SERVER_DEFAULT_THREAD_COUNT * 2)));

        // Set up the pipeline factory.
        this.messageServer.setPipelineFactory(new ChannelPipelineFactory() {
            public ChannelPipeline getPipeline() throws Exception {
                return Channels.pipeline(new NettyBytableEncoder(), new NettyBytableDecoder(), new ServerHandler());
            }
        });

        // Bind and start to accept incoming connections.
        this.messageServer.bind(new InetSocketAddress(this.messageServerPort));
    }

    /**
     * {@link ServerHandler} is used to receive {@link Bytable} message from worker..
     */
    private class ServerHandler extends SimpleChannelUpstreamHandler {

        @Override
        public void handleUpstream(ChannelHandlerContext ctx, ChannelEvent e) throws Exception {
            if(e instanceof ChannelStateEvent && ((ChannelStateEvent) e).getState() != ChannelState.INTEREST_OPS) {
                LOG.debug(e.toString());
            }
            super.handleUpstream(ctx, e);
        }

        @Override
        public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) {
            if(!(e.getMessage() instanceof Bytable)) {
                throw new IllegalStateException("Message should be bytable instance.");
            }

            LOG.info("message:{}", e.getMessage());
            // TODO such logic should be locked by one lock
            BytableWrapper bytableWrapper = (BytableWrapper) e.getMessage();
            String containerId = bytableWrapper.getContainerId();
            // if(bytableWrapper.isStopMessage()) {
            // for stop message, no need to check current iteration.
            if(!NettyMasterCoordinator.this.indexMap.containsKey(containerId)) {
                NettyMasterCoordinator.this.iterResults.add(bytableWrapper);
                NettyMasterCoordinator.this.indexMap.put(containerId,
                        (int) (NettyMasterCoordinator.this.iterResults.size() - 1));
            } else {
                // if already exits, no need update, we hope it is the same result as the worker restarted and
                // result computed again. or result is not for current iteration, throw that result.
            }
            // } else {
            // if(!NettyMasterCoordinator.this.indexMap.containsKey(containerId)
            // && NettyMasterCoordinator.this.currentInteration == bytableWrapper.getCurrentIteration()) {
            // NettyMasterCoordinator.this.iterResults.append(bytableWrapper);
            // NettyMasterCoordinator.this.indexMap.put(containerId,
            // (int) (NettyMasterCoordinator.this.iterResults.size() - 1));
            // } else {
            // // if already exits, no need update, we hope it is the same result as the worker restarted and
            // // result computed again. or result is not for current iteration, throw that result.
            // }
            // }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
            e.getChannel().close();
        }
    }

    /**
     * Wait for all workers done in current iteration.
     */
    @Override
    public void preIteration(final MasterContext<MASTER_RESULT, WORKER_RESULT> context) {
        // set current iteration firstly
        this.currentInteration = context.getCurrentIteration();

        long start = System.nanoTime();
        new RetryCoordinatorCommand(isFixedTime(), getSleepTime()) {
            @Override
            public boolean retryExecution() throws KeeperException, InterruptedException {
                // long to int is assumed successful as no such many workers need using long
                int doneWorkers = (int) NettyMasterCoordinator.this.iterResults.size();
                // to avoid log flood
                if(System.nanoTime() % 20 == 0) {
                    LOG.info("iteration {}, workers compelted: {}, still {} workers are not synced.",
                            context.getCurrentIteration(), doneWorkers, (context.getWorkers() - doneWorkers));
                }
                return isTerminated(doneWorkers, context.getWorkers(), context.getMinWorkersRatio(),
                        context.getMinWorkersTimeOut());
            }
        }.execute();

        // switch state to read
        // this.iterResults.switchState();
        // set worker results.
        context.setWorkerResults(new Iterable<WORKER_RESULT>() {
            @Override
            public Iterator<WORKER_RESULT> iterator() {
                return new Iterator<WORKER_RESULT>() {

                    private Iterator<BytableWrapper> localItr;

                    private volatile AtomicBoolean isStart = new AtomicBoolean();

                    @Override
                    public boolean hasNext() {
                        if(this.isStart.compareAndSet(false, true)) {
                            this.localItr = NettyMasterCoordinator.this.iterResults.iterator();
                        }
                        boolean hasNext = this.localItr.hasNext();
                        if(!hasNext) {
                            // to make sure it can be iterated again, it shouldn't be a good case for iterator, we will
                            // iterate again to check if all workers are halt.
                            this.localItr = NettyMasterCoordinator.this.iterResults.iterator();
                            return false;
                        }
                        return hasNext;
                    }

                    @Override
                    public WORKER_RESULT next() {
                        return NettyMasterCoordinator.this.getWorkerSerializer().bytesToObject(
                                localItr.next().getBytes(), context.getWorkerResultClassName());
                    }

                    @Override
                    public void remove() {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        });
        LOG.info("Application {} container {} iteration {} waiting ends with {}ms execution time.", context.getAppId(),
                context.getContainerId(), context.getCurrentIteration(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }

    /**
     * Update master computable result to master znode. At the same time clean znodes for old iterations. Iteration 0
     * and last iteration will not be removed for fail over.
     */
    @Override
    public void postIteration(final MasterContext<MASTER_RESULT, WORKER_RESULT> context) {
        new BasicCoordinatorCommand() {
            @Override
            public void doExecute() throws KeeperException, InterruptedException {
                // update master halt status.
                updateMasterHaltStatus(context);

                // create master znode
                boolean isSplit = false;
                String appCurrentMasterNode = getCurrentMasterNode(context.getAppId(), context.getCurrentIteration())
                        .toString();
                String appCurrentMasterSplitNode = getCurrentMasterSplitNode(context.getAppId(),
                        context.getCurrentIteration()).toString();
                LOG.info("master result:{}", context.getMasterResult());
                try {
                    byte[] bytes = getMasterSerializer().objectToBytes(context.getMasterResult());
                    isSplit = setBytesToZNode(appCurrentMasterNode, appCurrentMasterSplitNode, bytes,
                            CreateMode.PERSISTENT);
                } catch (KeeperException.NodeExistsException e) {
                    LOG.warn("Has such node:", e);
                }

                // remove -2 znode, no need, 0 is needed for fail-over.
                if(context.getCurrentIteration() >= 3) {
                    String znode = getMasterNode(context.getAppId(), context.getCurrentIteration() - 2).toString();
                    try {
                        getZooKeeper().deleteExt(znode, -1, false);
                        if(isSplit) {
                            znode = getCurrentMasterSplitNode(context.getAppId(), context.getCurrentIteration() - 2)
                                    .toString();
                            getZooKeeper().deleteExt(znode, -1, true);
                        }
                    } catch (KeeperException.NoNodeException e) {
                        if(System.nanoTime() % 20 == 0) {
                            LOG.warn("No such node:{}", znode);
                        }
                    }
                }

                LOG.info("master results write to znode.");
            }
        }.execute();

        clear(context.getProps());
    }

    /**
     * Wait for unregister message for all workers and then clean all znodes existing for this job.
     */
    @Override
    public void postApplication(final MasterContext<MASTER_RESULT, WORKER_RESULT> context) {
        // update current iteration for unregister iteration
        this.currentInteration = context.getCurrentIteration();

        new BasicCoordinatorCommand() {
            @Override
            public void doExecute() throws Exception, InterruptedException {
                try {
                    // if clean up zk znodes cost two much running time, one can set zk cleanup flag. But to make sure
                    // clean the znodes manually after application.
                    String zkCleanUpEnabled = StringUtils.get(
                            context.getProps().getProperty(GuaguaConstants.GUAGUA_ZK_CLEANUP_ENABLE),
                            GuaguaConstants.GUAGUA_ZK_DEFAULT_CLEANUP_VALUE);

                    if(Boolean.TRUE.toString().equalsIgnoreCase(zkCleanUpEnabled)) {
                        new RetryCoordinatorCommand(isFixedTime(), getSleepTime()) {
                            @Override
                            public boolean retryExecution() throws KeeperException, InterruptedException {
                                // long to int is assumed successful as no such many workers need using long
                                int doneWorkers = (int) NettyMasterCoordinator.this.iterResults.size();
                                // to avoid log flood
                                if(System.nanoTime() % 20 == 0) {
                                    LOG.info(
                                            "unregister step, workers compelted: {}, still {} workers are not unregistered.",
                                            doneWorkers, (context.getWorkers() - doneWorkers));
                                }
                                return isTerminated(doneWorkers, context.getWorkers(), context.getMinWorkersRatio(),
                                        context.getMinWorkersTimeOut());
                            }
                        }.execute();

                        // delete app znode
                        String appNode = getAppNode(context.getAppId()).toString();
                        try {
                            getZooKeeper().deleteExt(appNode, -1, true);
                        } catch (KeeperException.NoNodeException e) {
                            if(System.nanoTime() % 20 == 0) {
                                LOG.warn("No such node:{}", appNode);
                            }
                        }
                    }
                } finally {
                    if(NettyMasterCoordinator.this.messageServer != null) {
                        Method shutDownMethod = ReflectionUtils.getMethod(
                                NettyMasterCoordinator.this.messageServer.getClass(), "shutdown");
                        if(shutDownMethod != null) {
                            shutDownMethod.invoke(NettyMasterCoordinator.this.messageServer, (Object[]) null);
                        }
                        NettyMasterCoordinator.this.messageServer.releaseExternalResources();
                    }
                    closeZooKeeper();
                    // NettyMasterCoordinator.this.iterResults.close();
                    NettyMasterCoordinator.this.iterResults.clear();
                }
            }
        }.execute();
    }
}