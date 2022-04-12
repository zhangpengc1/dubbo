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
package com.alibaba.dubbo.rpc.cluster;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.loadbalance.RandomLoadBalance;

import java.util.List;

/**
 * Dubbo现在内置了 4种负载均衡算法，用户也可以自行扩展，因为LoadBalance接口上有@SPI注解
 *
 * 从 @SPI(RandomLoadBalance.NAME) 中可以知道默认的负载均衡实现就是RandomLoadBalance,即随机负载均衡。
 * 由于select方法上有 Adaptive ("loadbalance")注解，因此我们在URL中可以通过loadbalance=xxx来动态指定select时的负载均衡算法。
 *
 * 官方文档中对所有负载均衡算法的说明：
 *
 * Random LoadBalance
 *     随机，按权重设置随机概率。在一个节点上碰撞的概率高，但调用量越大分布越均匀，而且按概率使用权重后也比较均匀，有利于动态调整提
 * 供者的权重
 *
 * RoundRobin LoadBalance
 *     轮询，按公约后的权重设置轮询比例。存在慢的提供者累积请求的问题，
 * 比如：第二台机器很慢，但没“挂”，当请求调到第二台时就卡在那里， 久而久之，所有请求都卡在调到第二台上
 *
 * LeastActive LoadBalance
 *     最少活跃调用数，如果活跃数相同则随机调用，活跃数指调用前后计数差。使慢的提供者收到更少请求，因为越慢的提供者的调用前后计数差
 * 会越大
 *
 * ConsistentHash LoadBalance
 *      一致性Hash,相同参数的请求总是发到同一提供者。当某一台提供者“挂”时，原本发往该提供者的请求，基于虚拟节点，会平摊到其他提
 * 供者，不会引起剧烈变动。默认只对第一个参数“Hash”，如果要修改， 则配置 <dubbo:parameter key="hash.arguments" value="0,1"
 * />o默认使用160份虚拟节点，如果要修改，则配置〈dubbo:parameter
 * key="hash.nodes" value="320" />
 *
 *
 *
 * LoadBalance. (SPI, Singleton, ThreadSafe)
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Load_balancing_(computing)">Load-Balancing</a>
 *
 * @see com.alibaba.dubbo.rpc.cluster.Cluster#join(Directory)
 */
@SPI(RandomLoadBalance.NAME)
public interface LoadBalance {

    /**
     * select one invoker in list.
     *
     * @param invokers   invokers.
     * @param url        refer url
     * @param invocation invocation.
     * @return selected invoker.
     */
    @Adaptive("loadbalance")
    <T> Invoker<T> select(List<Invoker<T>> invokers, URL url, Invocation invocation) throws RpcException;

}