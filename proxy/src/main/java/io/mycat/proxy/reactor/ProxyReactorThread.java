/**
 * Copyright (C) <2019>  <chen junwen>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
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
package io.mycat.proxy.reactor;

import io.mycat.buffer.BufferPool;
import io.mycat.logTip.MycatLogger;
import io.mycat.logTip.MycatLoggerFactory;
import io.mycat.proxy.handler.BackendNIOHandler;
import io.mycat.proxy.handler.NIOHandler;
import io.mycat.proxy.packet.MySQLPacketResolver;
import io.mycat.proxy.session.Session;
import io.mycat.proxy.session.SessionManager.FrontSessionManager;
import io.mycat.util.nio.SelectorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Objects;
import java.util.Set;

/**
 * Reactor 任务调度,内存资源单位 无论是本线程内还是其他的线程,提交任务只能通过pendingQueue
 *
 * @author jamie12221 date 2019-05-10 13:21
 **/
public abstract class ProxyReactorThread<T extends Session> extends ReactorEnvThread {

    /**
     * 定时唤醒selector的时间 1.防止写入事件得不到处理 2.处理pending队列
     */
    protected final static long SELECTOR_TIMEOUT = 500L;
    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyReactorThread.class);
    protected final FrontSessionManager<T> frontManager;
    protected Selector selector;
    protected final BufferPool bufPool;
    protected Session session;
    protected volatile boolean prepareStop = false;

    private static long activeTime = System.currentTimeMillis();

    long ioTimes = 0;
    boolean pendingJobsEmpty = true;
    ///////////////////////
    int invalidSelectCount = 0;
    /////////////////////////

    @SuppressWarnings("unchecked")
    public ProxyReactorThread(BufferPool bufPool, FrontSessionManager<T> sessionMan)
            throws IOException {
        this.bufPool = bufPool;
        this.selector = Selector.open();
        this.frontManager = sessionMan;
    }

    public FrontSessionManager<T> getFrontManager() {
        return frontManager;
    }

    public Selector getSelector() {
        return selector;
    }

    /**
     * 处理连接请求 连接事件 连接事件由NIOAcceptor调用此函数 NIOAcceptor没有bufferpool
     */
    public boolean acceptNewSocketChannel(Object keyAttachement, final SocketChannel socketChannel) {
        assert Thread.currentThread() instanceof NIOAcceptor;
        if (prepareStop){
            return false;
        }
        boolean offered = pendingJobs.offer(new NIOJob() {
            @Override
            public void run(ReactorEnvThread reactor) {
                try {
                    // 完成事件注册
                    frontManager.acceptNewSocketChannel(keyAttachement, bufPool,
                                    selector, socketChannel);
                } catch (Exception e) {
                    LOGGER.warn("Register new connection error", e);
                }
            }

            @Override
            public void stop(ReactorEnvThread reactor, Exception reason) {
                LOGGER.warn("Register new connection error", reason);
            }

            @Override
            public String message() {
                return "acceptNewSocketChannel";
            }
        });
        return offered;
    }

    public BufferPool getBufPool() {
        return bufPool;
    }

    /**
     * 该方法仅NIOAcceptor使用
     */
    protected void processAcceptKey(SelectionKey curKey) throws IOException {
        assert false;
    }

    /**
     * 该方法仅Reactor自身创建的主动连接使用
     */
    @SuppressWarnings("unchecked")
    protected void processConnectKey(SelectionKey curKey) throws IOException {
        T session = (T) curKey.attachment();
        setCurSession(session);
        SocketChannel channel = (SocketChannel) curKey.channel();
        NIOHandler curNIOHandler = session.getCurNIOHandler();
        if (curNIOHandler instanceof BackendNIOHandler) {
            BackendNIOHandler handler = (BackendNIOHandler) curNIOHandler;
            try {
                if (channel.finishConnect()) {
                    handler.onConnect(curKey, session, true, null);
                }else {
                    handler.onConnect(curKey, session, false, new ConnectException());
                }
            } catch (Exception e) {
                handler.onConnect(curKey, session, false, e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    protected void processReadKey(SelectionKey curKey) throws IOException {
        T session = (T) curKey.attachment();
        setCurSession(session);
        // 默认是MycatHandler： MySQLClientAuthHandler/MyCatHandler/NIOHandler
        session.getCurNIOHandler().onSocketRead(session);
    }

    @SuppressWarnings("unchecked")
    protected void processWriteKey(SelectionKey curKey) throws IOException {
        T session = (T) curKey.attachment();
        setCurSession(session);
        session.getCurNIOHandler().onSocketWrite(session);
    }

    /**
     * socket请求的入口: 循环监听。
     * 此处的任务设计与redis有异曲同工之妙(基本原理一样)
     */
    public void run() {
        while (!this.isInterrupted()) {
            try {
                // 队列中的任务
                pendingJobsEmpty = pendingJobs.isEmpty();
                long startTime = System.nanoTime();
                // 如果为空，则监听epoll,阻塞，阻塞时间通过超时时间处理。与redis的ae event事件类似
                if (pendingJobsEmpty) {
                    ///////////////epoll///////////////////
                    int numOfKeys = selector.select(SELECTOR_TIMEOUT);
                    //////////////////////////////////
                    if (numOfKeys == 0) {
                        long dis = System.nanoTime() - startTime;
                        if (dis < (SELECTOR_TIMEOUT / 2)) {
                            // 记录空转次数，防止cpu 100%空轮询
                            invalidSelectCount++;
                        }
                    }
                    ////////epoll///////////
                } else {
                    // 如果有任务，马上select一次，不会阻塞
                    selector.selectNow();
                }
                updateLastActiveTime();
                // 获取所有Keys
                final Set<SelectionKey> keys = selector.selectedKeys();
                if (keys.isEmpty()) {
                    if (!pendingJobsEmpty) {
                        ioTimes = 0;
                        // 处理队列中的任务
                        this.processNIOJob();
                    }
                    continue;
                } else if ((ioTimes > 5) & !pendingJobsEmpty) {
                    // io超过5次，并且pending队列不为空，也需要处理
                    ioTimes = 0;
                    this.processNIOJob();
                }
                ioTimes++;
                for (final SelectionKey key : keys) {
                    try {
                        if (!key.isValid() || !key.channel().isOpen()) {
                            continue;
                        }
                        int readdyOps = key.readyOps();
                        setCurSession(null);
                        // ==========>如果当前收到连接请求,注册channel，并绑定对应的selector的attachent
                        if ((readdyOps & SelectionKey.OP_ACCEPT) != 0) {
                            processAcceptKey(key);
                        }
                        // 如果当前连接事件
                        else if ((readdyOps & SelectionKey.OP_CONNECT) != 0) {
                            this.processConnectKey(key);
                        } else if ((readdyOps & SelectionKey.OP_READ) != 0) {
                            // 处理读Key(通常的SQL命令均是此入口)
                            this.processReadKey(key);
                        } else if ((readdyOps & SelectionKey.OP_WRITE) != 0) {
                            // 处理写
                            this.processWriteKey(key);
                        }
                    } catch (Exception e) {//如果设置为IOException方便调试,避免吞没其他类型异常
                        LOGGER.error("{}", e);
                        Session curSession = getCurSession();
                        if (curSession != null) {
                            NIOHandler curNIOHandler = curSession.getCurNIOHandler();
                            if (curNIOHandler != null) {
                                curNIOHandler.onException(curSession, e);
                            } else {
                                curSession.close(false, curSession.setLastMessage(e));
                            }
                            setCurSession(null);
                        }
                    }
                }
                keys.clear();
                /////////epoll ：epoll空转导致cpu使用率高//////////
                if (invalidSelectCount > 512) {
                    Selector newSelector = SelectorUtil.rebuildSelector(this.selector);
                    if (newSelector != null) {
                        this.selector = newSelector;
                    }
                    invalidSelectCount = 0;
                }
                /////////epoll//////////
            } catch (ClosedSelectorException e) {
                LOGGER.warn("selector is closed");
                break;
            } catch (Throwable e) {
                LOGGER.warn("Unknown session:{}", getCurSession(), e);
            }
        }
    }

    /**
     * 获取该session,最近活跃的时间
     */
    public long updateLastActiveTime() {
        return activeTime = System.currentTimeMillis();
    }

    public long getLastActiveTime() {
        return activeTime;
    }

    public void close(Exception throwable) {
        Objects.requireNonNull(throwable);
        super.close(throwable);
        String message = throwable.toString();
        try {
            this.interrupt();
            if (frontManager != null) {
                for (T s : frontManager.getAllSessions()) {
                    try {
                        frontManager.removeSession(s, true, message);
                    } catch (Exception e) {
                        LOGGER.error("{}", e);
                    }
                }
            }
            selector.close();
        } catch (Exception e) {
            LOGGER.warn("", e);
        }
        //close buffer
    }

    @Override
    public Session getCurSession() {
        return session;
    }

    @Override
    public void setCurSession(Session session) {
        this.session = session;
    }

    public boolean isPrepareStop() {
        return prepareStop;
    }

    public void setPrepareStop(boolean prepareStop) {
        this.prepareStop = prepareStop;
    }

    @Override
    public void wakeup() {
        selector.wakeup();
    }
}
