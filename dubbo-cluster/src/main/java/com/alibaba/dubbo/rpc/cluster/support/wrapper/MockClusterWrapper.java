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
package com.alibaba.dubbo.rpc.cluster.support.wrapper;

import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Cluster;
import com.alibaba.dubbo.rpc.cluster.Directory;

/**
 * mock impl
 *
 * MockClusterWrapper是一个包装类，包装类会被自动注入合适的扩展点实现，它的逻
 * 辑很简单，只是把被包装扩展类作为初始化参数来创建并返回一个MockClusterlnvoker,
 *
 *
 * Mock只有在拦截到RpcException的时候会启用，属于异常容错方式的一种。业务层面其
 * 实也可以用try-catch来实现这种功能，如果使用下沉到框架中的Mock机制，则可以让业务的
 * 实现更优雅。
 *
 * 〃配置方式1：可以在配置文件中配置
 * <dubbo:reference interface="com.foo.BarService" mock="true" />
 * <dubbo:reference interface="com.foo.BarService" mock="com.foo.BarServiceMock" />
 * <dubbo:reference interface="com.foo.BarService" mock="return null" />
 *
 * 当接口配置了 Mock,在RPC调用抛出RpcException时就会执行Mock方法。最后一种return
 * null的配置方式通常会在想直接忽略异常的时候使用。
 *
 * 服务的降级是在dubbo-admin中通过override协议更新Invoker的Mock参数实现的。
 * 如果Mock参数设置为mock=force: return+null,则表明是强制Mock,强制Mock会让消费者对该
 * 服务的调用直接返回null,不再发起远程调用。
 *
 * 通常使用在非重要服务己经不可用的时候，可以屏蔽下游对上游系统造成的影响。
 * 此外，还能把参数设置为mock=fail:return+null,这样
 * 消费者还是会发起远程调用，不过失败后会返回null,但是不抛出异常。 最后，如果配置的参数是以throw开头的，即mock= throw,则直接抛出RpcException,不
 * 会发起远程调用。
 *
 */
public class MockClusterWrapper implements Cluster {

    private Cluster cluster;

    public MockClusterWrapper(Cluster cluster) {
        this.cluster = cluster;
    }

    @Override
    public <T> Invoker<T> join(Directory<T> directory) throws RpcException {
        return new MockClusterInvoker<T>(directory,
                this.cluster.join(directory));
    }

}
