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

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Round robin load balance.
 * <p>
 * 轮询，按公约后的权重设置轮询比例。存在慢的提供者累积请求的问题，
 * 比如：第二台机器很慢，但没“挂”，当请求调到第二台时就卡在那里， 久而久之，所有请求都卡在调到第二台上
 * <p>
 * 权重负载均衡，轮询负载均衡会根据设置的权重来判断轮询的比例。普通轮询负载均衡的好处是每个
 * 节点获得的请求会很均匀，如果某些节点的负载能力明显较弱，则这个节点会堆积比较多的请
 * 求。因此普通的轮询还不能满足需求，还需要能根据节点权重进行干预。权重轮询又分为普通
 * 权重轮询和平滑权重轮询。普通权重轮询会造成某个节点会突然被频繁选中，这样很容易突然
 * 让一个节点流量暴增。
 * <p>
 * Nginx中有一种叫平滑轮询的算法(smooth weighted round-robin balancing),
 * 这种算法在轮询时会穿插选择其他节点，让整个服务器选择的过程比较均匀，不会
 * “逮住”一个节点一直调用oDubbo框架中最新的RoundRobin代码已经改为平滑权重轮询算法。
 * <p>
 * <p>
 * <p>
 * Smoothly round robin's implementation @since 2.6.5
 */
public class RoundRobinLoadBalance extends AbstractLoadBalance {
    public static final String NAME = "roundrobin";

    private static int RECYCLE_PERIOD = 60000;

    protected static class WeightedRoundRobin {
        // Invoker 设定的权重
        private int weight;
        // 考虑到并发场景下某个Invoker会被同时选中，表示该节点被所有线程选中的权重总和
        // 例如：某节点权重是100，被4个线程同时选中，则变为400。
        private AtomicLong current = new AtomicLong(0);
        // 最后一次更新的时间，用于后续缓存超时的判断
        private long lastUpdate;

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
            current.set(0);
        }

        public long increaseCurrent() {
            return current.addAndGet(weight);
        }

        public void sel(int total) {
            current.addAndGet(-1 * total);
        }

        public long getLastUpdate() {
            return lastUpdate;
        }

        public void setLastUpdate(long lastUpdate) {
            this.lastUpdate = lastUpdate;
        }
    }

    private ConcurrentMap<String, ConcurrentMap<String, WeightedRoundRobin>> methodWeightMap = new ConcurrentHashMap<String, ConcurrentMap<String, WeightedRoundRobin>>();

    private AtomicBoolean updateLock = new AtomicBoolean();

    /**
     * get invoker addr list cached for specified invocation
     * <p>
     * <b>for unit test only</b>
     *
     * @param invokers
     * @param invocation
     * @return
     */
    protected <T> Collection<String> getInvokerAddrList(List<Invoker<T>> invokers, Invocation invocation) {
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        Map<String, WeightedRoundRobin> map = methodWeightMap.get(key);
        if (map != null) {
            return map.keySet();
        }
        return null;
    }

    /**
     * RoundRobin负载均衡的工作步骤
     * <p>
     * (1 )初始化权重缓存Map。以每个Invoker的URL为key,对象WeightedRoundRobin为value
     * 生成一个 ConcurrentMap,并把这个 Map 保存到全局的 methodWeightMap 中：ConcurrentMap
     * 〈String, ConcurrentMap<StringJ WeightedRoundRobin>> methodWeightMap。methodWeightMap
     * 的key是每个接口+方法名。这一步只会生成这个缓存Map,但里面是空的，第2步才会生成每
     * 个Invoker对应的键值。WeightedRoundRobin封装了每个Invoker的权重，对象中保存了三个属性，
     * <p>
     * (2) 遍历所有Invoker。首先，在遍历的过程中把每个Invoker的数据填充到第1步生成的
     * 权重缓存Map中。其次，获取每个Invoker的预热权重，新版的框架RoundRobin也支持预热， 通过和Random负载均衡中相同的方式获得预热阶段的权重。如果预热权重和Invoker设置的权
     * 重不相等，则说明还在预热阶段，此时会以预热权重为准。然后，进行平滑轮询。每个Invoker
     * 会把权重加到自己的current属性上，并更新当前Invoker的lastUpdate。同时累加每个Invoker
     * 的权重到totalweighto最终，遍历完后，选出所有Invoker中current最大的作为最终要调用
     * 的节点。
     * <p>
     * <p>
     * (3) 清除已经没有使用的缓存节点。由于所有的Invoker的权重都会被封装成一个weightedRoundRobin对象，因此如果可调用的Invoker列表数量和缓存weightedRoundRobin对
     * 象的Map大小不相等，则说明缓存Map中有无用数据（有些Invoker己经不在了，但Map中还
     * 有缓存）。
     * <p>
     * 为什么不相等就说明有老数据呢？如果Invoker列表比缓存Map大，则说明有没被缓存的
     * Invoker,此时缓存Map会新增数据。因此缓存Map永远大于等于Invoker列表。
     * <p>
     * 清除老旧数据时，各线程会先用CAS抢占锁（抢到锁的线程才做清除操作，抢不到的线程
     * 就直接跳过，保证只有一个线程在做清除操作），然后复制原有的Map到一个新的Map中，根
     * 据lastupdate清除新Map中的过期数据（默认60秒算过期），最后把Map从旧的Map引用修
     * 改到新的Map上面。这是一种CopyOnWrite的修改方式
     * <p>
     * 4 返回Invoker。注意，返回之前会把当前Invoker的current减去总权重。这是平滑权
     * 重轮询中重要的一步。
     *
     *
     * 算法逻辑提:
     * 1 每次请求做负载均衡时，会遍历所有可调用的节点（Invoker列表）。对于每个Invoker,
     * 让它的current = current + weight。属性含义见weightedRoundRobin对象。同时累加每个
     * Invoker 的 weight 到 totalWeight,即 totalweight = totalweight + weight
     *
     * 2 遍历完所有Invoker后，current值最大的节点就是本次要选择的节点。最后，把该节
     * 点的 current 值减去 totalWeight,即 current = current - totalweighto
     *
     * 具体的列可以看下书本中的demo p178
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
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        ConcurrentMap<String, WeightedRoundRobin> map = methodWeightMap.get(key);
        if (map == null) {
            methodWeightMap.putIfAbsent(key, new ConcurrentHashMap<String, WeightedRoundRobin>());
            map = methodWeightMap.get(key);
        }
        int totalWeight = 0;
        long maxCurrent = Long.MIN_VALUE;
        long now = System.currentTimeMillis();
        Invoker<T> selectedInvoker = null;
        WeightedRoundRobin selectedWRR = null;
        for (Invoker<T> invoker : invokers) {
            String identifyString = invoker.getUrl().toIdentityString();
            WeightedRoundRobin weightedRoundRobin = map.get(identifyString);
            int weight = getWeight(invoker, invocation);
            if (weight < 0) {
                weight = 0;
            }
            if (weightedRoundRobin == null) {
                weightedRoundRobin = new WeightedRoundRobin();
                weightedRoundRobin.setWeight(weight);
                map.putIfAbsent(identifyString, weightedRoundRobin);
                weightedRoundRobin = map.get(identifyString);
            }
            if (weight != weightedRoundRobin.getWeight()) {
                //weight changed
                weightedRoundRobin.setWeight(weight);
            }
            long cur = weightedRoundRobin.increaseCurrent();
            weightedRoundRobin.setLastUpdate(now);
            if (cur > maxCurrent) {
                maxCurrent = cur;
                selectedInvoker = invoker;
                selectedWRR = weightedRoundRobin;
            }
            totalWeight += weight;
        }
        if (!updateLock.get() && invokers.size() != map.size()) {
            if (updateLock.compareAndSet(false, true)) {
                try {
                    // copy -> modify -> update reference
                    ConcurrentMap<String, WeightedRoundRobin> newMap = new ConcurrentHashMap<String, WeightedRoundRobin>();
                    newMap.putAll(map);
                    Iterator<Entry<String, WeightedRoundRobin>> it = newMap.entrySet().iterator();
                    while (it.hasNext()) {
                        Entry<String, WeightedRoundRobin> item = it.next();
                        if (now - item.getValue().getLastUpdate() > RECYCLE_PERIOD) {
                            it.remove();
                        }
                    }
                    methodWeightMap.put(key, newMap);
                } finally {
                    updateLock.set(false);
                }
            }
        }
        if (selectedInvoker != null) {
            selectedWRR.sel(totalWeight);
            return selectedInvoker;
        }
        // should not happen here
        return invokers.get(0);
    }

}
