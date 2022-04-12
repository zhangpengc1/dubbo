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
import com.alibaba.dubbo.rpc.RpcStatus;

import java.util.List;
import java.util.Random;

/**
 * LeastActiveLoadBalance
 *
 * 最少活跃调用数，如果活跃数相同则随机调用，活跃数指调用前后计数差。使慢的提供者收到更少请求，因为越慢的提供者的调用前后计数差
 * 会越大
 *
 * LeastActive负载均衡称为最少活跃调用数负载均衡，即框架会记下每个Invoker的活跃数，每次只从活跃数最少的Invoker里选一个节点。这个负载均衡算法需要配合ActiveLimitFilter
 * 过滤器来计算每个接口方法的活跃数。最少活跃负载均衡可以看作Random负载均衡的“加强
 * 版”，因为最后根据权重做负载均衡的时候，使用的算法和Random的一样
 *
 *
 *
 */
public class LeastActiveLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "leastactive";

    private final Random random = new Random();

    /**
     * 逻辑：
     *
     * 遍历所有Invoker,不断寻找最小的活跃数(leastActive),
     * 如果有多个Invoker的活跃数都等于leastActive,则把它们保存到同一个集合中，
     * 最后在这个Invoker集合中再通过随机的方式选出一个Invoker
     *
     * 那最少活跃的计数又是如何知道的呢？
     *
     * 在ActiveLimitFilter中，只要进来一个请求，该方法的调用的计数就会原子性+1。整个
     * Invoker调用过程会包在try-catch-finally中，无论调用结束或出现异常，finally中都会把计数原
     * 子-1。该原子计数就是最少活跃数。
     *
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
        int leastActive = -1; // The least active value of all invokers
        int leastCount = 0; // The number of invokers having the same least active value (leastActive)
        int[] leastIndexs = new int[length]; // The index of invokers having the same least active value (leastActive)
        int totalWeight = 0; // The sum of with warmup weights
        int firstWeight = 0; // Initial value, used for comparision
        boolean sameWeight = true; // Every invoker has the same weight value?
        // 初始化各种计数器，如最小活跃数计数
        for (int i = 0; i < length; i++) {
            // 获得Invoker的活跃数、预热权重
            Invoker<T> invoker = invokers.get(i);
            int active = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName()).getActive(); // Active number
            int afterWarmup = getWeight(invoker, invocation); // Weight

            // 第一次，或者发现有更小的活跃数
            if (leastActive == -1 || active < leastActive) { // Restart, when find a invoker having smaller least active value.
                // 不管是第一次还是有更小的活跃数，之前的计数都要重新开始这里置空之前的计数。因为只计数最小的活跃数
                leastActive = active; // Record the current least active value
                leastCount = 1; // Reset leastCount, count again based on current leastCount
                leastIndexs[0] = i; // Reset
                totalWeight = afterWarmup; // Reset
                firstWeight = afterWarmup; // Record the weight the first invoker
                sameWeight = true; // Reset, every invoker has the same weight value?
            } else if (active == leastActive) { // If current invoker's active value equals with leaseActive, then accumulating.
                // 当前Invoker的活跃数与计数相同说明有N个Invoker都是最小计数，全部保存到集合中后续就在它们里面根据权重选一个节点
                leastIndexs[leastCount++] = i; // Record index number of this invoker
                totalWeight += afterWarmup; // Add this invoker's weight to totalWeight.
                // If every invoker has the same weight?
                if (sameWeight && i > 0
                        && afterWarmup != firstWeight) {
                    sameWeight = false;
                }
            }
        }
        // 如果只有一个Invoker则直接返回
        // assert(leastCount > 0)
        if (leastCount == 1) {
            // If we got exactly one invoker having the least active value, return this invoker directly.
            return invokers.get(leastIndexs[0]);
        }

        // 如果权重不一样，则使用和Random负载均衡一样的权重算法找到一个Invoker并返回
        if (!sameWeight && totalWeight > 0) {
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on totalWeight.
            int offsetWeight = random.nextInt(totalWeight) + 1;
            // Return a invoker based on the random value.
            for (int i = 0; i < leastCount; i++) {
                int leastIndex = leastIndexs[i];
                offsetWeight -= getWeight(invokers.get(leastIndex), invocation);
                if (offsetWeight <= 0)
                    return invokers.get(leastIndex);
            }
        }
        // 如果权重相同，则直接随机选一个返回
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        return invokers.get(leastIndexs[random.nextInt(leastCount)]);
    }
}
