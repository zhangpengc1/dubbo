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

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.threadlocal.NamedInternalThreadFactory;
import com.alibaba.dubbo.rpc.*;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Invoke a specific number of invokers concurrently, usually used for demanding real-time operations, but need to waste more service resources.
 *
 * <a href="http://en.wikipedia.org/wiki/Fork_(topology)">Fork</a>
 * <p>
 * Forking可以同时并行请求多个服务，有任何一个返回，则直接返回
 */
public class ForkingClusterInvoker<T> extends AbstractClusterInvoker<T> {

    /**
     * Use {@link NamedInternalThreadFactory} to produce {@link com.alibaba.dubbo.common.threadlocal.InternalThread}
     * which with the use of {@link com.alibaba.dubbo.common.threadlocal.InternalThreadLocal} in {@link RpcContext}.
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(
            new NamedInternalThreadFactory("forking-cluster-timer", true));

    public ForkingClusterInvoker(Directory<T> directory) {
        super(directory);
    }


    /**
     * (1) 准备工作。校验传入的Invoker列表是否可用；初始化一个Invoker集合，用于保存真正要调用的Invoker列表；从URL中得到最大并行数、超时时间。
     *
     * (2) 获取最终要调用的Invoker列表。假设用户设置最大的并行数为n， 实际可以调用的
     * 最大服务数为V。如果n<0或n<v,则说明可用的服务数小于用户的设置，因此最终要调用的
     * Invoker 可能有v个；如果〃<v,则会循环调用负载均衡方法，不断得到可调用的Invoker,加入步骤1中的Invoker集合里。
     *
     * 这里有一点需要注意：在Invoker加入集合时，会做去重操作。因此，如果用户设置的负载
     * 均衡策略每次返回的都是同一个Invoker,那么集合中最后只会存在一个Invoker,也就是只会
     * 调用一个节点。
     *
     * (3) 调用前的准备工作。设置要调用的Invoker列表到RPC上下文；初始化一个异常计数
     * 器；初始化一个阻塞队列，用于记录并行调用的结果。
     *
     * (4) 执行调用。循环使用线程池并行调用，调用成功，则把结果加入阻塞队列；调用失败， 则失败计数+1。如果所有线程的调用都失败了，即失败计数》所有可调用的Invoker时，则把
     * 异常信息加入阻塞队列。
     * 这里有一点需要注意：并行调用是如何保证个别调用失败不返回异常信息，只有全部失败
     * 才返回异常信息的呢？因为有判断条件，当失败计数N所有可调用的Invoker时，才会把异常信
     * 息放入阻塞队列，所以只有当最后一个Invoker也调用失败时才会把异常信息保存到阻塞队列， 从而达到全部失败才返回异常的效果。
     *
     * (5) 同步等待结果。由于步骤4中的步骤是在线程池中执行的，因此主线程还会继续往下
     * 执行，主线程中会使用阻塞队列的poll(-超时时间“)方法，同步等待阻塞队列中的第一个结果， 如果是正常结果则返回，如果是异常则抛出。
     *
     *
     * 从上面步骤可以得知,Forking的超时是通过在阻塞队列的poll方法中传入超时时间实现的；
     * 线程池中的并发调用会获取第一个正常返回结果。只有所有请求都失败了，Forking才会失败。
     *
     * @param invocation
     * @param invokers
     * @param loadbalance
     * @return
     * @throws RpcException
     */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Result doInvoke(final Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        try {
            checkInvokers(invokers, invocation);
            final List<Invoker<T>> selected;
            final int forks = getUrl().getParameter(Constants.FORKS_KEY, Constants.DEFAULT_FORKS);
            final int timeout = getUrl().getParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);
            if (forks <= 0 || forks >= invokers.size()) {
                selected = invokers;
            } else {
                selected = new ArrayList<Invoker<T>>();
                for (int i = 0; i < forks; i++) {
                    // TODO. Add some comment here, refer chinese version for more details.
                    Invoker<T> invoker = select(loadbalance, invocation, invokers, selected);
                    if (!selected.contains(invoker)) {//Avoid add the same invoker several times.
                        selected.add(invoker);
                    }
                }
            }
            RpcContext.getContext().setInvokers((List) selected);
            final AtomicInteger count = new AtomicInteger();
            final BlockingQueue<Object> ref = new LinkedBlockingQueue<Object>();

            for (final Invoker<T> invoker : selected) {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Result result = invoker.invoke(invocation);
                            ref.offer(result);
                        } catch (Throwable e) {
                            int value = count.incrementAndGet();
                            if (value >= selected.size()) {
                                ref.offer(e);
                            }
                        }
                    }
                });
            }
            try {
                Object ret = ref.poll(timeout, TimeUnit.MILLISECONDS);
                if (ret instanceof Throwable) {
                    Throwable e = (Throwable) ret;
                    throw new RpcException(e instanceof RpcException ? ((RpcException) e).getCode() : 0, "Failed to forking invoke provider " + selected + ", but no luck to perform the invocation. Last error is: " + e.getMessage(), e.getCause() != null ? e.getCause() : e);
                }
                return (Result) ret;
            } catch (InterruptedException e) {
                throw new RpcException("Failed to forking invoke provider " + selected + ", but no luck to perform the invocation. Last error is: " + e.getMessage(), e);
            }
        } finally {
            // clear attachments which is binding to current thread.
            RpcContext.getContext().clearAttachments();
        }
    }
}
