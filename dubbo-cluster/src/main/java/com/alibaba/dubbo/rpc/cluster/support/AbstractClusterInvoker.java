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
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AbstractClusterInvoker
 *
 * Cluster的总体工作流程可以分为以下几步:
 *
 * 1.生成Invoker对象。不同的Cluster实现会生成不同类型的Clusterinvoker对象并返
 * 回。然后调用Clusterinvoker的Invoker方法，正式开始调用流程。
 *
 * 2.获得可调用的服务列表。首先会做前置校验，检查远程服务是否已被销毁。然后通过
 * Directory#list方法获取所有可用的服务列表。接着使用Router接口处理该服务列表，根据路
 * 由规则过滤一部分服务，最终返回剩余的服务列表。
 *
 * 3.做负载均衡。在第2步中得到的服务列表还需要通过不同的负载均衡策略选出一个服
 * 务，用作最后的调用。首先框架会根据用户的配置，调用ExtensionLoader获取不同负载均衡策
 * 略的扩展点实现（具体负载均衡策略会在后面讲解）。然后做一些后置操作，如果是异步调用则
 * 设置调用编号。接着调用子类实现的dolnvoke方法（父类专门留了这个抽象方法让子类实现）， 子类会根据具体的负载均衡策略选出一个可以调用的服务。
 *
 * 4.做RPC调用。首先保存每次调用的Invoker到RPC上下文，并做RPC调用。然后处
 * 理调用结果，对于调用出现异常、成功、失败等情况，每种容错策略会有不同的处理方式。7.2
 * 节将介绍Cluster接口下不同的容错策略实现
 *
 * 其中1〜3步都是在抽象方法Abstractclusterinvoker中实现的，可以理解为通用的模板流程，主要做了校验、参数准备等工作，最终调用子类实现的
 * dolnvoke方法。不同的Clusterinvoker子类都继承了该抽象类，子类会在上述流程中做个性化
 * 的裁剪。
 *
 * 容错机制的特性: 机制名 机制简介
 * Failover 当出现失败时，会重试其他服务器。用户可以通过retries="2n设置重试次数。这是
 * Dubbo的默认容错机制，会对请求做负载均衡。通常使用在读操作或幕等的写操作上， 但重试会导致接口的延退增大，在下游机器负载已经达到极限时，重试容易加重下游
 * 服务的负载
 *
 * Failfast 快速失败，当请求失败后，快速返回异常结果，不做任何重试。该容错机制会对请求
 * 做负载均衡，通常使用在非幕等接口的调用上。该机制受网络抖动的影响较大
 *
 * Failsafe 当出现异常时，直接忽略异常。会对请求做负载均衡。通常使用在“佛系”调用场景， 即不关心调用是否成功，并且不想抛异常影响外层调用，如某些不重要的日志同步，j
 * 即使出现异常也无所谓
 *
 * Fallback 请求失败后，会自动记录在失败队列中，并由一个定时线程池定时重试，适用于一些
 * 异步或最终一致性的请求。请求会做负载均衡
 *
 * Forking 同时调用多个相同的服务，只要其中一个返回，则立即返回结果。用户可以配置
 *
 * forks：最大并行调用数”参数来确定最大并行调用的服务数量。通常使用在对接口
 * 实时性要求极高的调用上，但也会浪费更多的资源
 *
 * Broadcast 广播调用所有可用的服务，任意一个节点报错则报错。由于是广播，因此请求不需要
 * 做负载均衡。通常用于服务状态更新后的广播
 *
 * Mock 提供调用失败时，返回伪造的响应结果。或直接强制返回伪造的结果，不会发起远程
 * 调用
 *
 * Available 最简单的方式，请求不会做负载均衡，遍历所有服务列表，找到第一个可用的节点， 直接请求并返回结果。如果没有可用的节点，则直接抛出异常
 *
 * Mergeable Mergeable可以自动把多个节点请求得到的结果进行合并
 *
 *
 *
 * Cluseter的具体实现:
 *
 * 用户可以在<dubbo :service>><dubbo:reference>><dubbo:consumer>><dubbo:provider>标签上通过cluster属性设置。
 *
 * 对于Failover容错模式，用户可以通过retries属性来设置最大重试次数。可以设置在dubbo:reference标签上，也可以设置在细粒度的方法标签dubbo:method上。
 *
 * 对于Forking容错模式，用户可通过forks="最大大并行数”属性来设置最大并行数。
 * 假设设置的forks数为n,可用的服务数为v,当n<v时，即可用的服务数大于配置的并行数，则并行请
 * 求n个服务；当n>v时，即可用的服务数小于配置的并行数，则请求所有可用的服务V。
 *
 * 对于Mergeable容错模式，用可以在dubbo:reference标签中通过merger="true"开启，合并时可以通过group="*"属性指定需要合并哪些分组的结果。
 * 默认会根据方法的返回值自动匹配合并器，如果同一个类型有两个不同的合并器实现，则需要在参数中指定合并器的名字merger-“合并器名“）。
 * 例如:用户根据某List类型的返回结果实现了多个合并器，则需要手动指定合并器名称，否则框架不知道要用哪个。如果想调用返回结果的指定方法进行合并（如返
 * 回了一个Set,想调用Set#addAll方法），则可以通过merger=" .addAll'1配置来实现
 *
 */
public abstract class AbstractClusterInvoker<T> implements Invoker<T> {

    private static final Logger logger = LoggerFactory
            .getLogger(AbstractClusterInvoker.class);
    protected final Directory<T> directory;

    protected final boolean availablecheck;

    private AtomicBoolean destroyed = new AtomicBoolean(false);

    private volatile Invoker<T> stickyInvoker = null;

    public AbstractClusterInvoker(Directory<T> directory) {
        this(directory, directory.getUrl());
    }

    public AbstractClusterInvoker(Directory<T> directory, URL url) {
        if (directory == null)
            throw new IllegalArgumentException("service directory == null");

        this.directory = directory;
        //sticky: invoker.isAvailable() should always be checked before using when availablecheck is true.
        this.availablecheck = url.getParameter(Constants.CLUSTER_AVAILABLE_CHECK_KEY, Constants.DEFAULT_CLUSTER_AVAILABLE_CHECK);
    }

    @Override
    public Class<T> getInterface() {
        return directory.getInterface();
    }

    @Override
    public URL getUrl() {
        return directory.getUrl();
    }

    @Override
    public boolean isAvailable() {
        Invoker<T> invoker = stickyInvoker;
        if (invoker != null) {
            return invoker.isAvailable();
        }
        return directory.isAvailable();
    }

    @Override
    public void destroy() {
        if (destroyed.compareAndSet(false, true)) {
            directory.destroy();
        }
    }

    /**
     * 很多容错策略中都会使用负载均衡方法，并且所有的容错策略中的负载均衡都使用了抽象父类Abstractclusterinvoker中定义的Invoker<T> select方法，
     * 而并不是直接使用LoadBalance方法。因为抽象父类在LoadBalance的基础上又封装了一些新的特性：
     *
     * (1) 粘滞连接。Dubbo中有一种特性叫粘滞连接，以下内容摘自官方文档： 粘滞连接用于有状态服务，尽可能让客户端总是向同一提供者发起调用，除非该提供者“挂了”，再连接
     * 另一台。粘滞连接将自动开启延迟连接，以减少长连接数。
     * <dubbo:protocol name=Hdubbo" sticky="true" />
     *
     * (2) 可用检测。Dubbo调用的URL中，如果含有cluster.availablecheck=false,则不
     * 会检测远程服务是否可用，直接调用。如果不设置，则默认会开启检查，对所有的服务都做是
     * 否可用的检查，如果不可用，则再次做负载均衡。
     *
     * (3) 避免重复调用。对于已经调用过的远程服务，避免重复选择，每次都使用同一个节点。
     * 这种特性主要是为了避免并发场景下，某个节点瞬间被大量请求。
     *
     * 整个逻辑过程大致可以分为4步：
     *
     * (1) 检查URL中是否有配置粘滞连接，如果有则使用粘滞连接的Invoker。如果没有配置
     * 粘滞连接，或者重复调用检测不通过、可用检测不通过，则进入第2步。
     *
     * (2) 通过ExtensionLoader获取负载均衡的具体实现，并通过负载均衡做节点的选择。对
     * 选择出来的节点做重复调用、可用性检测，通过则直接返回，否则进入第3步。
     *
     * (3) 进行节点的重新选择。如果需要做可用性检测，则会遍历Directory中得到的所有节
     * 点，过滤不可用和已经调用过的节点，在剩余的节点中重新做负载均衡；如果不需要做可用性
     * 检测，那么也会遍历Directory中得到的所有节点，但只过滤已经调用过的，在剩余的节点中重
     * 新做负载均衡。这里存在一种情况，就是在过滤不可用或已经调用过的节点时，节点全部被过
     * 滤，没有剩下任何节点，此时进入第4步。
     *
     * (4) 遍历所有已经调用过的节点，选出所有可用的节点，再通过负载均衡选出一个节点并
     * 返回。如果还找不到可调用的节点，则返回null。
     *
     * 框架会优先处理粘滞连接。否则会根据可用性检测或重复
     * 调用检测过滤一些节点，并在剩余的节点中做负载均衡。如果可用性检测或重复调用检测把节
     * 点都过滤了，则兜底的策略是：在己经调用过的节点中通过负载均衡选择出一个可用的节点。
     *
     *
     * Select a invoker using loadbalance policy.</br>
     * a)Firstly, select an invoker using loadbalance. If this invoker is in previously selected list, or, 
     * if this invoker is unavailable, then continue step b (reselect), otherwise return the first selected invoker</br>
     * b)Reslection, the validation rule for reselection: selected > available. This rule guarantees that
     * the selected invoker has the minimum chance to be one in the previously selected list, and also 
     * guarantees this invoker is available.
     *
     * @param loadbalance load balance policy
     * @param invocation
     * @param invokers invoker candidates
     * @param selected  exclude selected invokers or not
     * @return
     * @throws RpcException
     */
    protected Invoker<T> select(LoadBalance loadbalance, Invocation invocation, List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {
        if (invokers == null || invokers.isEmpty())
            return null;
        String methodName = invocation == null ? "" : invocation.getMethodName();

        boolean sticky = invokers.get(0).getUrl().getMethodParameter(methodName, Constants.CLUSTER_STICKY_KEY, Constants.DEFAULT_CLUSTER_STICKY);
        {
            //ignore overloaded method
            if (stickyInvoker != null && !invokers.contains(stickyInvoker)) {
                stickyInvoker = null;
            }
            //ignore concurrency problem
            if (sticky && stickyInvoker != null && (selected == null || !selected.contains(stickyInvoker))) {
                if (availablecheck && stickyInvoker.isAvailable()) {
                    return stickyInvoker;
                }
            }
        }
        Invoker<T> invoker = doSelect(loadbalance, invocation, invokers, selected);

        if (sticky) {
            stickyInvoker = invoker;
        }
        return invoker;
    }

    private Invoker<T> doSelect(LoadBalance loadbalance, Invocation invocation, List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {
        if (invokers == null || invokers.isEmpty())
            return null;
        if (invokers.size() == 1)
            return invokers.get(0);

        if (loadbalance == null) {
            loadbalance = ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(Constants.DEFAULT_LOADBALANCE);
        }

        Invoker<T> invoker = loadbalance.select(invokers, getUrl(), invocation);

        //If the `invoker` is in the  `selected` or invoker is unavailable && availablecheck is true, reselect.
        if ((selected != null && selected.contains(invoker))
                || (!invoker.isAvailable() && getUrl() != null && availablecheck)) {
            try {
                Invoker<T> rinvoker = reselect(loadbalance, invocation, invokers, selected, availablecheck);
                if (rinvoker != null) {
                    invoker = rinvoker;
                } else {
                    //Check the index of current selected invoker, if it's not the last one, choose the one at index+1.
                    int index = invokers.indexOf(invoker);
                    try {
                        //Avoid collision
                        invoker = index < invokers.size() - 1 ? invokers.get(index + 1) : invokers.get(0);
                    } catch (Exception e) {
                        logger.warn(e.getMessage() + " may because invokers list dynamic change, ignore.", e);
                    }
                }
            } catch (Throwable t) {
                logger.error("cluster reselect fail reason is :" + t.getMessage() + " if can not solve, you can set cluster.availablecheck=false in url", t);
            }
        }
        return invoker;
    }

    /**
     * Reselect, use invokers not in `selected` first, if all invokers are in `selected`, just pick an available one using loadbalance policy.
     *
     * @param loadbalance
     * @param invocation
     * @param invokers
     * @param selected
     * @return
     * @throws RpcException
     */
    private Invoker<T> reselect(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected, boolean availablecheck)
            throws RpcException {

        //Allocating one in advance, this list is certain to be used.
        List<Invoker<T>> reselectInvokers = new ArrayList<Invoker<T>>(invokers.size() > 1 ? (invokers.size() - 1) : invokers.size());

        //First, try picking a invoker not in `selected`.
        if (availablecheck) { // invoker.isAvailable() should be checked
            for (Invoker<T> invoker : invokers) {
                if (invoker.isAvailable()) {
                    if (selected == null || !selected.contains(invoker)) {
                        reselectInvokers.add(invoker);
                    }
                }
            }
            if (!reselectInvokers.isEmpty()) {
                return loadbalance.select(reselectInvokers, getUrl(), invocation);
            }
        } else { // do not check invoker.isAvailable()
            for (Invoker<T> invoker : invokers) {
                if (selected == null || !selected.contains(invoker)) {
                    reselectInvokers.add(invoker);
                }
            }
            if (!reselectInvokers.isEmpty()) {
                return loadbalance.select(reselectInvokers, getUrl(), invocation);
            }
        }
        // Just pick an available invoker using loadbalance policy
        {
            if (selected != null) {
                for (Invoker<T> invoker : selected) {
                    if ((invoker.isAvailable()) // available first
                            && !reselectInvokers.contains(invoker)) {
                        reselectInvokers.add(invoker);
                    }
                }
            }
            if (!reselectInvokers.isEmpty()) {
                return loadbalance.select(reselectInvokers, getUrl(), invocation);
            }
        }
        return null;
    }

    @Override
    public Result invoke(final Invocation invocation) throws RpcException {
        checkWhetherDestroyed();
        LoadBalance loadbalance = null;

        // binding attachments into invocation.
        Map<String, String> contextAttachments = RpcContext.getContext().getAttachments();
        if (contextAttachments != null && contextAttachments.size() != 0) {
            ((RpcInvocation) invocation).addAttachments(contextAttachments);
        }

        List<Invoker<T>> invokers = list(invocation);
        if (invokers != null && !invokers.isEmpty()) {
            loadbalance = ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(invokers.get(0).getUrl()
                    .getMethodParameter(RpcUtils.getMethodName(invocation), Constants.LOADBALANCE_KEY, Constants.DEFAULT_LOADBALANCE));
        }
        RpcUtils.attachInvocationIdIfAsync(getUrl(), invocation);
        return doInvoke(invocation, invokers, loadbalance);
    }

    protected void checkWhetherDestroyed() {

        if (destroyed.get()) {
            throw new RpcException("Rpc cluster invoker for " + getInterface() + " on consumer " + NetUtils.getLocalHost()
                    + " use dubbo version " + Version.getVersion()
                    + " is now destroyed! Can not invoke any more.");
        }
    }

    @Override
    public String toString() {
        return getInterface() + " -> " + getUrl().toString();
    }

    protected void checkInvokers(List<Invoker<T>> invokers, Invocation invocation) {
        if (invokers == null || invokers.isEmpty()) {
            throw new RpcException("Failed to invoke the method "
                    + invocation.getMethodName() + " in the service " + getInterface().getName()
                    + ". No provider available for the service " + directory.getUrl().getServiceKey()
                    + " from registry " + directory.getUrl().getAddress()
                    + " on the consumer " + NetUtils.getLocalHost()
                    + " using the dubbo version " + Version.getVersion()
                    + ". Please check if the providers have been started and registered.");
        }
    }

    protected abstract Result doInvoke(Invocation invocation, List<Invoker<T>> invokers,
                                       LoadBalance loadbalance) throws RpcException;

    protected List<Invoker<T>> list(Invocation invocation) throws RpcException {
        List<Invoker<T>> invokers = directory.list(invocation);
        return invokers;
    }
}
