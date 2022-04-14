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
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.Merger;
import com.alibaba.dubbo.rpc.cluster.merger.MergerFactory;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * MergeableClusterlnvoker串起了整个合并器逻辑，在讲解MergeableClusterlnvoker的机制之
 * 前，先回顾一下整个调用的过程：MergeableCluster#join方法中直接生成并返回了
 * MergeableClusterlnvoker, MergeableClusterInvoker#invoke 方法又通过 MergerFactory 工厂
 * 获取不同的Merger接口实现，完成了合并的具体逻辑。
 *
 * MergeableCluster并没有继承抽象的Cluster实现，而是独立完成了自己的逻辑。因此，它
 * 的整个逻辑和之前的Failover等机制不同，其步骤如下：#invoke
 *
 *
 *
 * @param <T>
 */
@SuppressWarnings("unchecked")
public class MergeableClusterInvoker<T> implements Invoker<T> {

    private static final Logger log = LoggerFactory.getLogger(MergeableClusterInvoker.class);
    private final Directory<T> directory;
    private ExecutorService executor = Executors.newCachedThreadPool(new NamedThreadFactory("mergeable-cluster-executor", true));

    public MergeableClusterInvoker(Directory<T> directory) {
        this.directory = directory;
    }

    /**
     * 逻辑：
     * (1) 前置准备。通过directory获取所有Invoker列表。
     *
     * (2) 合并器检查。判断某个方法是否有合并器，如果没有，则不会并行调用多个group,
     * 找到第一个可以调用的Invoker直接调用就返回了。如果有合并器，则进入第3步。
     *
     * (3) 获取接口的返回类型。通过反射获得返回类型，后续要根据这个返回值查找不同的合
     * 并器。
     *
     * (4) 并行调用。把Invoker的调用封装成一个个Callable对象，放到线程池中执行，保存
     * 线程池返回的future对象到HashMap中，用于等待后续结果返回。
     *
     * (5) 等待future对象的返回结果。获取配置的超时参数，遍历(4)中得到的future对象， 设置Future#get的超时时间，同步等待得到并行调用的结果。异常的结果会被忽略，正常的结果
     * 会被保存到list中。如果最终没有返回结果，则直接返回一个空的RpcResult；如果只有一个结果， 那么也直接返回，不需要再做合并；如果返回类型是void,则说明没有返回值，也直接返回。
     *
     * (6) 合并结果集。如果配置的是merger^1.addAll",则直接通过反射调用返回类型中
     * 的.addAll方法合并结果集。例如：返回类型是Set,则调用Set.addAll来合并结果
     *
     * @param invocation
     * @return
     * @throws RpcException
     */
    @Override
    @SuppressWarnings("rawtypes")
    public Result invoke(final Invocation invocation) throws RpcException {
        List<Invoker<T>> invokers = directory.list(invocation);

        String merger = getUrl().getMethodParameter(invocation.getMethodName(), Constants.MERGER_KEY);
        if (ConfigUtils.isEmpty(merger)) { // If a method doesn't have a merger, only invoke one Group
            for (final Invoker<T> invoker : invokers) {
                if (invoker.isAvailable()) {
                    return invoker.invoke(invocation);
                }
            }
            return invokers.iterator().next().invoke(invocation);
        }

        Class<?> returnType;
        try {
            returnType = getInterface().getMethod(
                    invocation.getMethodName(), invocation.getParameterTypes()).getReturnType();
        } catch (NoSuchMethodException e) {
            returnType = null;
        }

        Map<String, Future<Result>> results = new HashMap<String, Future<Result>>();
        for (final Invoker<T> invoker : invokers) {
            Future<Result> future = executor.submit(new Callable<Result>() {
                @Override
                public Result call() throws Exception {
                    return invoker.invoke(new RpcInvocation(invocation, invoker));
                }
            });
            results.put(invoker.getUrl().getServiceKey(), future);
        }

        Object result = null;

        List<Result> resultList = new ArrayList<Result>(results.size());

        int timeout = getUrl().getMethodParameter(invocation.getMethodName(), Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);
        for (Map.Entry<String, Future<Result>> entry : results.entrySet()) {
            Future<Result> future = entry.getValue();
            try {
                Result r = future.get(timeout, TimeUnit.MILLISECONDS);
                if (r.hasException()) {
                    log.error("Invoke " + getGroupDescFromServiceKey(entry.getKey()) + 
                                    " failed: " + r.getException().getMessage(), 
                            r.getException());
                } else {
                    resultList.add(r);
                }
            } catch (Exception e) {
                throw new RpcException("Failed to invoke service " + entry.getKey() + ": " + e.getMessage(), e);
            }
        }

        if (resultList.isEmpty()) {
            return new RpcResult((Object) null);
        } else if (resultList.size() == 1) {
            return resultList.iterator().next();
        }

        if (returnType == void.class) {
            return new RpcResult((Object) null);
        }

        // 获取真正的方法对象
        if (merger.startsWith(".")) {
            merger = merger.substring(1);
            Method method;
            try {
                method = returnType.getMethod(merger, returnType);
            } catch (NoSuchMethodException e) {
                throw new RpcException("Can not merge result because missing method [ " + merger + " ] in class [ " + 
                        returnType.getClass().getName() + " ]");
            }

            // 如果是private等不可访问的方法，则设置为可以访问
            if (!Modifier.isPublic(method.getModifiers())) {
                method.setAccessible(true);
            }
            result = resultList.remove(0).getValue();
            try {
                // 如果返回类型不为void,并会返回相同的类型，则反射调用该方法合并结果，并修改result
                if (method.getReturnType() != void.class
                        && method.getReturnType().isAssignableFrom(result.getClass())) {
                    for (Result r : resultList) {
                        result = method.invoke(result, r.getValue());
                    }
                } else {
                    // 如果不符合，则直接把结果合并进去即可
                    for (Result r : resultList) {
                        method.invoke(result, r.getValue());
                    }
                }
            } catch (Exception e) {
                throw new RpcException("Can not merge result: " + e.getMessage(), e);
            }
        } else {
            Merger resultMerger;
            // 如果是默认的Merger 参数为true或default ,则用MergerFactory获取默认的合并器，
            // 否则通过ExtensionLoader获取对应名字的合并器
            if (ConfigUtils.isDefault(merger)) {
                resultMerger = MergerFactory.getMerger(returnType);
            } else {
                resultMerger = ExtensionLoader.getExtensionLoader(Merger.class).getExtension(merger);
            }
            // 找到合并器则合并，否则抛出异常
            if (resultMerger != null) {
                List<Object> rets = new ArrayList<Object>(resultList.size());
                for (Result r : resultList) {
                    rets.add(r.getValue());
                }
                result = resultMerger.merge(
                        rets.toArray((Object[]) Array.newInstance(returnType, 0)));
            } else {
                throw new RpcException("There is no merger to merge result.");
            }
        }
        return new RpcResult(result);
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
        return directory.isAvailable();
    }

    @Override
    public void destroy() {
        directory.destroy();
    }

    private String getGroupDescFromServiceKey(String key) {
        int index = key.indexOf("/");
        if (index > 0) {
            return "group [ " + key.substring(0, index) + " ]";
        }
        return key;
    }
}
