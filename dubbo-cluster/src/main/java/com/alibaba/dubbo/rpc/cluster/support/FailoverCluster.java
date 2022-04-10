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

import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Cluster;
import com.alibaba.dubbo.rpc.cluster.Directory;

/**
 * {@link FailoverClusterInvoker}
 *
 *
 * FailoverCluster是Cluster的其中一种实现，
 * FailoverCluster中直接创建了一个新的FailoverClusterlnvoker并返回。
 * FailoverClusterlnvoker 继承的接口是 Invoker。
 *
 * Cluster是最上层的接口，下面一共有9个实现类。Cluster接口上有SPI注解，也就是说，
 * 实现类是通过扩展机制动态生成的。每个实现类里都只有一个join方法，实现也很简单，
 * 直接“new” 一个对应的Clusterinvokero其中AvailableCluster例外，直接使用匿名内部类实现了所有功能。
 *
 */
public class FailoverCluster implements Cluster {

    public final static String NAME = "failover";

    @Override
    public <T> Invoker<T> join(Directory<T> directory) throws RpcException {
        return new FailoverClusterInvoker<T>(directory);
    }

}
