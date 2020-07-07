/**
 * Copyright (C) <2019>  <chen junwen>
 * <p>
 * This program is open software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat;

import io.mycat.api.MySQLAPI;
import io.mycat.api.callback.MySQLAPIExceptionCallback;
import io.mycat.api.collector.CollectorUtil;
import io.mycat.api.collector.OneResultSetCollector;
import io.mycat.beans.MySQLDatasource;
import io.mycat.beans.mycat.TransactionType;
import io.mycat.beans.mysql.packet.ErrorPacket;
import io.mycat.booster.BoosterRuntime;
import io.mycat.buffer.BufferPool;
import io.mycat.buffer.HeapBufferPool;
import io.mycat.client.InterceptorRuntime;
import io.mycat.command.CommandDispatcher;
import io.mycat.config.*;
import io.mycat.datasource.jdbc.DatasourceProvider;
import io.mycat.datasource.jdbc.JdbcRuntime;
import io.mycat.datasource.jdbc.datasourceProvider.AtomikosDatasourceProvider;
import io.mycat.datasource.jdbc.transactionSession.JTATransactionSession;
import io.mycat.exporter.PrometheusExporter;
import io.mycat.ext.MySQLAPIImpl;
import io.mycat.manager.ManagerCommandDispatcher;
import io.mycat.metadata.MetadataManager;
import io.mycat.plug.PlugRuntime;
import io.mycat.proxy.buffer.ProxyBufferPoolMonitor;
import io.mycat.proxy.callback.SessionCallBack;
import io.mycat.proxy.reactor.*;
import io.mycat.proxy.session.AuthenticatorImpl;
import io.mycat.proxy.session.MySQLClientSession;
import io.mycat.proxy.session.MycatSession;
import io.mycat.proxy.session.MycatSessionManager;
import io.mycat.replica.ReplicaSelectorRuntime;
import io.mycat.runtime.LocalTransactionSession;
import io.mycat.runtime.MycatDataContextSupport;
import io.mycat.runtime.ProxyTransactionSession;
import io.mycat.util.ApplicationContext;
import io.mycat.util.CharsetUtil;
import io.mycat.util.ClassUtil;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Mycat启动类
 * @author cjw
 **/
public enum MycatCore {
    INSTANCE;
    private ConfigProvider config;
    private static final Logger LOGGER = LoggerFactory.getLogger(MycatCore.class);
    private ConcurrentHashMap<String, MySQLDatasource> datasourceMap = new ConcurrentHashMap<>();
    @Getter
    private final ApplicationContext context = new ApplicationContext();//容器管理实例数量与生命周期
    @Getter
    private ReactorThreadManager reactorManager;
    @Getter
    private ReactorThreadManager managerManager;

    @SneakyThrows
    public void init(ConfigProvider config) {
        this.config = config;
        // 获取配置文件
        MycatConfig mycatConfig = config.currentConfig();
        // 加载插件
        PlugRuntime.INSTANCE.load(mycatConfig);
        // 构造运行时线程池
        MycatWorkerProcessor.INSTANCE.init(mycatConfig.getServer().getWorkerPool(),mycatConfig.getServer().getTimeWorkerPool());
        // 集群配置相关初始化
        ReplicaSelectorRuntime.INSTANCE.load(mycatConfig);
        // JDBC相关工作线程池
        JdbcRuntime.INSTANCE.load(mycatConfig);
        BoosterRuntime.INSTANCE.load(mycatConfig);
        // 指令运行时拦截器
        InterceptorRuntime.INSTANCE.load(mycatConfig);
        // 元数据管理
        MetadataManager.INSTANCE.load(mycatConfig);
        // 字符编码设置
        CharsetUtil.init(null);
        //context.scanner("io.mycat.sqlHandler").inject();
        // ========> 启动Mycat服务器(即代理服务器 ip:port   0.0.0.0:8066)
        startProxy(mycatConfig);
        // ========> 启动Mycat的管理端(即管理服务器 ip:port   0.0.0.0:9066)
        startManager(mycatConfig);
        //插件
        runExtra(mycatConfig);
    }

    /**
     * 默认Mananger的端口是9066 ，IP是127.0.0.1
     * @param config
     * @throws IOException
     */
    private void startManager(MycatConfig config) throws IOException {
        ManagerConfig manager = config.getManager();
        if (manager == null) {
            return;
        }
        // ====> 默认没有User，所以直接结束。
        List<UserConfig> users = manager.getUsers();
        if (users == null || users.isEmpty()) {
            return;
        }
        List<MycatReactorThread> list = new ArrayList<>(1);
        BufferPool bufferPool = new HeapBufferPool();
        bufferPool.init(Collections.emptyMap());
        // 设置对应的指令处理器
        Function<MycatSession, CommandDispatcher> function = session -> {
            try {
                CommandDispatcher commandDispatcher = new ManagerCommandDispatcher();
                commandDispatcher.initRuntime(session);
                return commandDispatcher;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        Map<String, UserConfig> userConfigMap = users.stream().collect((Collectors.toMap(k -> k.getUsername(), v -> v)));
        MycatReactorThread thread = new MycatReactorThread(new ProxyBufferPoolMonitor(bufferPool), new MycatSessionManager(function, new AuthenticatorImpl(userConfigMap)));
        thread.start();
        list.add(thread);


        managerManager = new ReactorThreadManager(list);
        NIOAcceptor acceptor = new NIOAcceptor(managerManager);
        acceptor.startServerChannel(manager.getIp(), manager.getPort());
    }

    /**
     * 启动代理服务器
     * @param mycatConfig
     * @throws ClassNotFoundException
     * @throws NoSuchMethodException
     * @throws InstantiationException
     * @throws IllegalAccessException
     * @throws java.lang.reflect.InvocationTargetException
     * @throws IOException
     * @throws InterruptedException
     */
    private void startProxy(MycatConfig mycatConfig) throws ClassNotFoundException, NoSuchMethodException, InstantiationException, IllegalAccessException, java.lang.reflect.InvocationTargetException, IOException, InterruptedException {
        ServerConfig serverConfig = mycatConfig.getServer();
        // 默认值：public io.mycat.buffer.HeapBufferPool()
        String bufferPoolText = Optional.ofNullable(mycatConfig.getServer()).map(i -> i.getBufferPool()).map(i -> i.getPoolName()).orElse(HeapBufferPool.class.getName());
        // 默认值：io.mycat.DefaultCommandHandler
        String handlerConstructorText = Optional.ofNullable(mycatConfig.getServer()).map(i -> i.getHandlerName()).orElse(DefaultCommandHandler.class.getName());

        Constructor<?> bufferPoolConstructor = getConstructor(bufferPoolText);
        Constructor<?> handlerConstructor = getConstructor(handlerConstructorText);

        int reactorNumber = serverConfig.getReactorNumber();
        List<MycatReactorThread> list = new ArrayList<>(reactorNumber);
        // 根据reactorNumber创建对应的线程
        for (int i = 0; i < reactorNumber; i++) {
            BufferPool bufferPool = (BufferPool) bufferPoolConstructor.newInstance();
            bufferPool.init(mycatConfig.getServer().getBufferPool().getArgs());
            Function<MycatSession, CommandDispatcher> function = session -> {
                try {
                    // 设置对应的指令处理器
                    CommandDispatcher commandDispatcher = (CommandDispatcher) handlerConstructor.newInstance();
                    commandDispatcher.initRuntime(session);
                    return commandDispatcher;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };
            Map<String, UserConfig> userConfigMap = mycatConfig.getInterceptors().stream().map(u -> u.getUser()).collect((Collectors.toMap(k -> k.getUsername(), v -> v)));
            // 创建Mycat响应编程线程
            MycatReactorThread thread = new MycatReactorThread(new ProxyBufferPoolMonitor(bufferPool), new MycatSessionManager(function, new AuthenticatorImpl(userConfigMap)));
            // 启动线程
            thread.start();
            list.add(thread);
        }
        // Reactor线程管理器，因此构造函数的参数为Reactor线程集合
        this.reactorManager = new ReactorThreadManager(list);
        // 启动空闲连接检查
        idleConnectCheck(mycatConfig, reactorManager);
        // 启动心跳连接检查
        heartbeat(mycatConfig, reactorManager);

        TimerConfig timer = mycatConfig.getCluster().getTimer();
        NIOAcceptor acceptor = new NIOAcceptor(reactorManager);


        HashMap<TransactionType, Function<MycatDataContext, TransactionSession>> transactionFactoryMap = new HashMap<>();


        DatasourceProvider datasourceProvider = JdbcRuntime.INSTANCE.getDatasourceProvider();
        if ((datasourceProvider instanceof AtomikosDatasourceProvider)) {
            transactionFactoryMap.put(TransactionType.JDBC_TRANSACTION_TYPE, mycatDataContext -> new JTATransactionSession(mycatDataContext, () -> datasourceProvider.createUserTransaction()));
        } else {
            transactionFactoryMap.put(TransactionType.JDBC_TRANSACTION_TYPE, mycatDataContext -> new LocalTransactionSession(mycatDataContext));
        }
        transactionFactoryMap.put(TransactionType.PROXY_TRANSACTION_TYPE, mycatDataContext -> new ProxyTransactionSession(mycatDataContext));

        MycatDataContextSupport.INSTANCE.init(mycatConfig.getServer().getBindTransactionPool(), transactionFactoryMap);


        long wait = TimeUnit.valueOf(timer.getTimeUnit()).toMillis(timer.getInitialDelay()) + TimeUnit.SECONDS.toMillis(1);
        Thread.sleep(wait);
        // 启动Server相关
        acceptor.startServerChannel(serverConfig.getIp(), serverConfig.getPort());
        // 启动Session检查(定时任务检查)
        initFrontSessionChecker(mycatConfig, reactorManager);

        LOGGER.info("mycat starts successful");
    }

    private void initFrontSessionChecker(MycatConfig mycatConfig, ReactorThreadManager reactorManager) {
        TimerConfig frontSessionChecker = mycatConfig.getServer().getTimer();
        if (frontSessionChecker.getPeriod() > 0) {
            ScheduleUtil.getTimer().scheduleAtFixedRate(() -> {
                try {
                    for (MycatReactorThread thread : reactorManager.getList()) {
                        thread.addNIOJob(new NIOJob() {
                            @Override
                            public void run(ReactorEnvThread reactor) throws Exception {
                                thread.getFrontManager().check();
                            }

                            @Override
                            public void stop(ReactorEnvThread reactor, Exception reason) {

                            }

                            @Override
                            public String message() {
                                return "frontSessionChecker";
                            }
                        });
                    }
                } catch (Exception e) {
                    LOGGER.error("{}", e);
                }
            }, frontSessionChecker.getInitialDelay(), frontSessionChecker.getPeriod(), TimeUnit.valueOf(frontSessionChecker.getTimeUnit()));
        }
    }

    /**
     * 检查空闲连接任务
     * @param mycatConfig
     * @param reactorManager
     */
    private void idleConnectCheck(MycatConfig mycatConfig, ReactorThreadManager reactorManager) {
        TimerConfig timer = mycatConfig.getDatasource().getTimer();
        ScheduleUtil.getTimer().scheduleAtFixedRate(() -> {
            for (MycatReactorThread thread : reactorManager.getList()) {
                thread.addNIOJob(new NIOJob() {
                    @Override
                    public void run(ReactorEnvThread reactor) throws Exception {
                        thread.getMySQLSessionManager().idleConnectCheck();
                    }

                    @Override
                    public void stop(ReactorEnvThread reactor, Exception reason) {

                    }

                    @Override
                    public String message() {
                        return "idleConnectCheck";
                    }
                });
            }
        }, timer.getInitialDelay(), timer.getPeriod(), TimeUnit.valueOf(timer.getTimeUnit()));
    }

    private void heartbeat(MycatConfig mycatConfig, ReactorThreadManager reactorManager) {
        for (ClusterRootConfig.ClusterConfig cluster : mycatConfig.getCluster().getClusters()) {
            if ("mysql".equalsIgnoreCase(cluster.getHeartbeat().getRequestType())) {
                String replicaName = cluster.getName();
                for (String datasource : cluster.getAllDatasources())
                    ReplicaSelectorRuntime.INSTANCE.putHeartFlow(replicaName, datasource, heartBeatStrategy -> reactorManager.getRandomReactor().addNIOJob(new NIOJob() {
                        @Override
                        public void run(ReactorEnvThread reactor) throws Exception {
                            MySQLTaskUtil.getMySQLSessionForTryConnect(datasource, new SessionCallBack<MySQLClientSession>() {
                                @Override
                                public void onSession(MySQLClientSession session, Object sender, Object attr) {
                                    MySQLAPIImpl mySQLAPI = new MySQLAPIImpl(session);
                                    OneResultSetCollector objects = new OneResultSetCollector();
                                    mySQLAPI.query(heartBeatStrategy.getSql(), objects, new MySQLAPIExceptionCallback() {
                                        @Override
                                        public void onException(Exception exception, @NonNull MySQLAPI mySQLAPI) {
                                            heartBeatStrategy.onException(exception);
                                        }

                                        @Override
                                        public void onFinished(boolean monopolize, @NonNull MySQLAPI mySQLAPI) {
                                            try {
                                                List<Map<String, Object>> maps = CollectorUtil.toList(objects);
                                                LOGGER.debug("proxy heartbeat {}", Objects.toString(maps));
                                                heartBeatStrategy.process(maps);
                                            } finally {
                                                mySQLAPI.close();
                                            }
                                        }

                                        @Override
                                        public void onErrorPacket(@NonNull ErrorPacket errorPacket, boolean monopolize, @NonNull MySQLAPI mySQLAPI) {
                                            mySQLAPI.close();
                                            heartBeatStrategy.onError(errorPacket.getErrorMessageString());
                                        }
                                    });
                                }

                                @Override
                                public void onException(Exception exception, Object sender, Object attr) {
                                    heartBeatStrategy.onException(exception);
                                }
                            });
                        }

                        @Override
                        public void stop(ReactorEnvThread reactor, Exception reason) {

                        }

                        @Override
                        public String message() {
                            return "heartbeat";
                        }
                    }));
            }
        }
    }

    /**
     * 启动类
     * @param args
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {
        // 初始化配置(配置初始化)
        ConfigProvider bootConfig = RootHelper.INSTANCE.bootConfig(MycatCore.class);
        // 根据配置启动相关模块
        MycatCore.INSTANCE.init(bootConfig);
    }

    public MySQLDatasource getDatasource(String name) {
        MySQLDatasource datasource2 = datasourceMap.get(name);
        if (datasource2 != null) {
            return datasource2;
        }
        List<DatasourceRootConfig.DatasourceConfig> datasources = config.currentConfig().getDatasource().getDatasources();
        for (DatasourceRootConfig.DatasourceConfig datasourceConfig : datasources) {
            if (datasourceConfig.computeType().isNative() && name.equals(datasourceConfig.getName())) {

                return datasourceMap.computeIfAbsent(name, s -> {
                    MySQLDatasource mySQLDatasource = new MySQLDatasource(datasourceConfig) {
                    };
                    ReplicaSelectorRuntime.INSTANCE.registerDatasource(datasourceConfig.getName(), () -> mySQLDatasource.getConnectionCounter());
                    return mySQLDatasource;
                });
            }
        }
        return null;
    }

    public void removeDatasource(String name) {
        datasourceMap.remove(name);
    }

    private static Constructor<?> getConstructor(String clazz) throws ClassNotFoundException, NoSuchMethodException {
        Class<?> bufferPoolClass = Class.forName(clazz);
        return bufferPoolClass.getDeclaredConstructor();
    }

    public Map<String, MySQLDatasource> getDatasourceMap() {
        return Collections.unmodifiableMap(datasourceMap);
    }

    //动态更新仅更新这两部分
    public void flash(MycatConfig config){
        datasourceMap.clear();
        heartbeat(config, reactorManager);
    }

    private static void runExtra(MycatConfig mycatConfig) {
        for (String clazz : Optional.ofNullable(mycatConfig)
                .map(m -> m.getPlug())
                .map(m -> m.getExtra())
                .orElse(Collections.emptyList())) {
            try {
                Class<?> aClass = Class.forName(clazz);
                Constructor<?> declaredConstructor = aClass.getDeclaredConstructors()[0];
                Runnable o = (Runnable)declaredConstructor.newInstance();
                o.run();
            }catch (Throwable e){
                LOGGER.error("can not run:{}",clazz,e);
            }
        }
    }
}
