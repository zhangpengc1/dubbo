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

import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Cluster;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;

import java.util.List;

/**
 * AvailableCluster
 *
 * 多注册中心集群策略
 *
 */
public class AvailableCluster implements Cluster {

    public static final String NAME = "available";

    /**
     *
     * 在①中实现dolnvoke实际持有的invokers列表是注册中心实例，比如配置了 ZooKeeper和
     * etcd3注册中心，实际调用的invokers列表只有2个元素。在②中会判断具体注册中心中是否有
     * 服务可用，这里发起的invoke实际上会通过注册中心RegistryDirectory获取真实provider机
     * 器列表进行路由和负载均衡调用
     *
     * 使用多注册中心进行服务消费时，给框架开发者和扩展特性的开发人员带来了一些挑战， 特别是在编写同机房路由时，在服务路由层获取的也是注册中心实例Invoker,需要进入Invoker
     * 内部判断服务列表是否符合匹配规则，如果匹配到符合匹配规则的机器，则这个时候只能把外
     * 层注册中心Invoker返回，否则会破坏框架服务调用的生命周期（导致跳过MockClusterlnvoker
     * 服务调用）。
     *
     * @param directory
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> Invoker<T> join(Directory<T> directory) throws RpcException {

        return new AbstractClusterInvoker<T>(directory) {
            @Override
            public Result doInvoke(Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
                // 1 这里是注册中心Invoker 实例
                for (Invoker<T> invoker : invokers) {
                    // ②判断特定注册中心是否包含provider 服务
                    if (invoker.isAvailable()) {
                        return invoker.invoke(invocation);
                    }
                }
                throw new RpcException("No provider available in " + invokers);
            }
        };

    }

}
