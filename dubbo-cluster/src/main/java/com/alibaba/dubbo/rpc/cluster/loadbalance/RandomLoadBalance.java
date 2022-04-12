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
package com.alibaba.dubbo.rpc.cluster.loadbalance;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;

import java.util.List;
import java.util.Random;

/**
 * random load balance.
 *
 * 随机负载均衡，按权重设置随机概率。在一个节点上碰撞的概率高，但调用量越
 * 大分布越均匀，而且按概率使用权重后也比较均匀，有利于动态调整提
 * 供者的权重
 *
 * Random负载均衡是按照权重设置随机概率做负载均衡的。这种负载均衡算法并不能精确
 * 地平均请求，但是随着请求数量的增加，最终结果是大致平均的。
 *
 */
public class RandomLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "random";

    private final Random random = new Random();

    /**
     * 负载计算步骤如下：
     *
     * (1) 计算总权重并判断每个Invoker的权重是否一样。遍历整个Invoker列表，求和总权
     * 重。在遍历过程中，会对比每个Invoker的权重，判断所有Invoker的权重是否相同。
     *
     * (2) 如果权重相同，则说明每个Invoker的概率都一样，因此直接用nextlnt随机选一个
     * Invoker返回即可。
     *
     * (3) 如果权重不同，则首先得到偏移值，然后根据偏移值找到对应的Invoker
     *
     * 假设有4个Invoker,它们的权重分别是1、2、3、4,则总权重是 1+2+3+4=10。
     * 说明每个 Invoker 分别有 1/10、2/10、3/10、4/10 的概率会被选中。
     * 然后nextlnt(10)会返回0〜10之间的一个整数，假设为5。如果进行累减,则减到3后会小于0,此时会落入3的区间，即选择3号Invoke
     *
     * @param invokers
     * @param url
     * @param invocation
     * @param <T>
     * @return
     */
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        int length = invokers.size(); // Number of invokers
        int totalWeight = 0; // The sum of weights
        boolean sameWeight = true; // Every invoker has the same weight?
        for (int i = 0; i < length; i++) {
            int weight = getWeight(invokers.get(i), invocation);
            totalWeight += weight; // Sum
            if (sameWeight && i > 0
                    && weight != getWeight(invokers.get(i - 1), invocation)) {
                sameWeight = false;
            }
        }

        if (totalWeight > 0 && !sameWeight) {
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on totalWeight.
            int offset = random.nextInt(totalWeight);
            // Return a invoker based on the random value.
            for (int i = 0; i < length; i++) {
                offset -= getWeight(invokers.get(i), invocation);
                if (offset < 0) {
                    return invokers.get(i);
                }
            }
        }
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        return invokers.get(random.nextInt(length));
    }

}
