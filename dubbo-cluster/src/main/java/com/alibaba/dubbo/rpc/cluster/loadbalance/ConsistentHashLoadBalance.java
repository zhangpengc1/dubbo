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

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * ConsistentHashLoadBalance
 *
 * 一致性Hash,相同参数的请求总是发到同一提供者。当某一台提供者“挂”时，原本发往该提供者的请求，基于虚拟节点，会平摊到其他提
 * 供者，不会引起剧烈变动。默认只对第一个参数“Hash”，如果要修改， 则配置 <dubbo:parameter key="hash.arguments" value="0,1"
 * />o默认使用160份虚拟节点，如果要修改，则配置〈dubbo:parameter
 * key="hash.nodes" value="320" />
 *
 * 一致性Hash负载均衡可以让参数相同的请求每次都路由到相同的机器上。这种负载均衡的
 * 方式可以让请求相对平均，相比直接使用Hash而言，当某些节点下线时，请求会平摊到其他服
 * 务提供者，不会引起剧烈变动。
 *
 * 书中p179有demo图
 *
 * 普通的一致性Hash也有一定的局限性，它的散列不一定均匀，容易造成某些节点压力大。 因此Dubbo框架使用了优化过的Ketama 一致性Hash。这种算法会为每个真实节点再创建多个
 * 虚拟节点，让节点在环形上的分布更加均匀，后续的调用也会随之更加均匀。
 *
 *
 *
 *
 *
 */
public class ConsistentHashLoadBalance extends AbstractLoadBalance {

    private final ConcurrentMap<String, ConsistentHashSelector<?>> selectors = new ConcurrentHashMap<String, ConsistentHashSelector<?>>();

    /**
     * —致性Hash负载均衡
     *
     * @param invokers
     * @param url
     * @param invocation
     * @param <T>
     * @return
     */
    @SuppressWarnings("unchecked")
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // 获得方法名
        String methodName = RpcUtils.getMethodName(invocation);

        // 以接口名+方法名拼接出key
        String key = invokers.get(0).getUrl().getServiceKey() + "." + methodName;

        // 把所有可以调用的Invoker列表进行“Hash”
        int identityHashCode = System.identityHashCode(invokers);

        // 现在Invoker列表的Hash码和之前的不一样，说明Invoker列表已经发生了变化，则重新创建Selector
        // ConsistentHashSelector 逻辑的核心
        ConsistentHashSelector<T> selector = (ConsistentHashSelector<T>) selectors.get(key);
        if (selector == null || selector.identityHashCode != identityHashCode) {
            selectors.put(key, new ConsistentHashSelector<T>(invokers, methodName, identityHashCode));
            selector = (ConsistentHashSelector<T>) selectors.get(key);
        }
        // 通过 selector 选出一个 Invoker
        return selector.select(invocation);
    }

    /**
     * 逻辑的核心
     *
     *
     * @param <T>
     */
    private static final class ConsistentHashSelector<T> {

        private final TreeMap<Long, Invoker<T>> virtualInvokers;

        private final int replicaNumber;

        private final int identityHashCode;

        private final int[] argumentIndex;

        /**
         * ConsistentHashSelector初始化的时候会对节点进行散列，
         * 散列的环形是使用一个TreeMap实现的，所有的真实、虚拟节点都会放入TreeMapo把节点的IP+递增数字做“MD5”，
         * 以此作为节点标识，再对标识做“Hash”得到TreeMap的key,最后把可以调用的节点作为TreeMap的value
         *
         *
         * TreeMap实现一致性Hash：在客户端调用时候，只要对请求的参数也做“MD5”即可。
         * 虽然此时得到的MD5值不一定能对应到TreeMap中的一个key,因为每次的请求参数不同。但是
         * 由于TreeMap是有序的树形结构，所以我们可以调用TreeMap的ceilingEntry方法，用于返
         * 回一个至少大于或等于当前给定key的Entry,从而达到顺时针往前找的效果。如果找不到，则
         * 使用firstEntry返回第一个节点。
         *
         * @param invokers
         * @param methodName
         * @param identityHashCode
         */
        ConsistentHashSelector(List<Invoker<T>> invokers, String methodName, int identityHashCode) {
            this.virtualInvokers = new TreeMap<Long, Invoker<T>>();
            this.identityHashCode = identityHashCode;
            URL url = invokers.get(0).getUrl();
            this.replicaNumber = url.getMethodParameter(methodName, "hash.nodes", 160);
            String[] index = Constants.COMMA_SPLIT_PATTERN.split(url.getMethodParameter(methodName, "hash.arguments", "0"));
            argumentIndex = new int[index.length];
            for (int i = 0; i < index.length; i++) {
                argumentIndex[i] = Integer.parseInt(index[i]);
            }
            // 遍历所有的节点
            for (Invoker<T> invoker : invokers) {
                // 得到每个节点的 ip
                String address = invoker.getUrl().getAddress();
                // repiicaNumber 是生成的虚拟节，默认为160个
                for (int i = 0; i < replicaNumber / 4; i++) {
                    // 以IP+递增数字做MD5,以此作为节点标识
                    byte[] digest = md5(address + i);
                    for (int h = 0; h < 4; h++) {
                        // 对标识做“Hash” 得到 TreeMap 的 key,以Invoker 为 value
                        long m = hash(digest, h);
                        virtualInvokers.put(m, invoker);
                    }
                }
            }
        }

        public Invoker<T> select(Invocation invocation) {
            String key = toKey(invocation.getArguments());
            byte[] digest = md5(key);
            return selectForKey(hash(digest, 0));
        }

        private String toKey(Object[] args) {
            StringBuilder buf = new StringBuilder();
            for (int i : argumentIndex) {
                if (i >= 0 && i < args.length) {
                    buf.append(args[i]);
                }
            }
            return buf.toString();
        }

        private Invoker<T> selectForKey(long hash) {
            Map.Entry<Long, Invoker<T>> entry = virtualInvokers.tailMap(hash, true).firstEntry();
            if (entry == null) {
                entry = virtualInvokers.firstEntry();
            }
            return entry.getValue();
        }

        private long hash(byte[] digest, int number) {
            return (((long) (digest[3 + number * 4] & 0xFF) << 24)
                    | ((long) (digest[2 + number * 4] & 0xFF) << 16)
                    | ((long) (digest[1 + number * 4] & 0xFF) << 8)
                    | (digest[number * 4] & 0xFF))
                    & 0xFFFFFFFFL;
        }

        private byte[] md5(String value) {
            MessageDigest md5;
            try {
                md5 = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            md5.reset();
            byte[] bytes;
            try {
                bytes = value.getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            md5.update(bytes);
            return md5.digest();
        }

    }

}
