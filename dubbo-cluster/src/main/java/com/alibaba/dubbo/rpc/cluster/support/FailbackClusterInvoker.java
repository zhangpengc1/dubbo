/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.rpc.cluster.support;

import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.threadlocal.NamedInternalThreadFactory;
import com.alibaba.dubbo.rpc.*;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * When fails, record failure requests and schedule for retry on a regular interval.
 * Especially useful for services of notification.
 *
 * <a href="http://en.wikipedia.org/wiki/Failback">Failback</a>
 */
public class FailbackClusterInvoker<T> extends AbstractClusterInvoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(FailbackClusterInvoker.class);

    private static final long RETRY_FAILED_PERIOD = 5 * 1000;

    /**
     * Use {@link NamedInternalThreadFactory} to produce {@link com.alibaba.dubbo.common.threadlocal.InternalThread}
     * which with the use of {@link com.alibaba.dubbo.common.threadlocal.InternalThreadLocal} in {@link RpcContext}.
     */
    // 默认每5秒把所有失败的调用拿出来，重试一次。如果调用重试成功，则会从ConcurrentHashMap中移除。
    private final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(2,
            new NamedInternalThreadFactory("failback-cluster-timer", true));

    // 专门用来保存失败的调用
    private final ConcurrentMap<Invocation, AbstractClusterInvoker<?>> failed = new ConcurrentHashMap<Invocation, AbstractClusterInvoker<?>>();

    private volatile ScheduledFuture<?> retryFuture;

    public FailbackClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    private void addFailed(Invocation invocation, AbstractClusterInvoker<?> router) {
        if (retryFuture == null) {
            synchronized (this) {
                if (retryFuture == null) {
                    retryFuture = scheduledExecutorService.scheduleWithFixedDelay(new Runnable() {

                        @Override
                        public void run() {
                            // collect retry statistics
                            try {
                                retryFailed();
                            } catch (Throwable t) { // Defensive fault tolerance
                                logger.error("Unexpected error occur at collect statistic", t);
                            }
                        }
                    }, RETRY_FAILED_PERIOD, RETRY_FAILED_PERIOD, TimeUnit.MILLISECONDS);
                }
            }
        }
        failed.put(invocation, router);
    }

    /**
     * 重试
     */
    void retryFailed() {
        // —没有失败的请求，直接退出
        if (failed.size() == 0) {
            return;
        }
        // 遍历ConcurrentHashMap,得到所有失败请求
        for (Map.Entry<Invocation, AbstractClusterInvoker<?>> entry : new HashMap<Invocation, AbstractClusterInvoker<?>>(
                failed).entrySet()) {
            Invocation invocation = entry.getKey();
            Invoker<?> invoker = entry.getValue();
            try {
                // 重新进行请求，成功则从失败记录中移除
                invoker.invoke(invocation);
                failed.remove(invocation);
            } catch (Throwable e) {
                // 捕获异常，只打印日志，防止异常中断重试过程
                logger.error("Failed retry to invoke method " + invocation.getMethodName() + ", waiting again.", e);
            }
        }
    }

    /**
     * 1.校验传入的参数。校验从AbstractClusterlnvoker传入的Invoker列表是否为空。
     * 2.负载均衡。调用select方法做负载均衡，得到要调用的节点。
     * 3.远程调用。在try代码块中调用invoker#invoke方法做远程调用，“catch”到异常后直接把invocation保存到重试的ConcurrentHashMap中，并返回一个空的结果集。
     * 4.定时线程池会定时把ConcurrentHashMap中的失败请求拿出来重新请求，请求成功则从ConcurrentHashMap中移除。如果请求还是失败，则异常也会被“catch”住，不会影响ConcurrentHashMap中后面的重试。
     *
     * @param invocation
     * @param invokers
     * @param loadbalance
     * @return
     * @throws RpcException
     */
    @Override
    protected Result doInvoke(Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        try {
            checkInvokers(invokers, invocation);
            Invoker<T> invoker = select(loadbalance, invocation, invokers, null);
            return invoker.invoke(invocation);
        } catch (Throwable e) {
            logger.error("Failback to invoke method " + invocation.getMethodName() + ", wait for retry in background. Ignored exception: "
                    + e.getMessage() + ", ", e);
            addFailed(invocation, this);
            return new RpcResult(); // ignore
        }
    }

}
