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
import com.alibaba.dubbo.rpc.cluster.LoadBalance;

import java.util.List;

/**
 * AbstractLoadBalance
 *
 * 负载均衡抽象类
 *
 * 4种负载均衡算法都继承自同一个抽象类，使用的也是模板模式，抽象父类中己经把通用
 * 的逻辑完成，留了一个抽象的doSelect方法给子类实现。
 *
 *
 * 抽象父类的select方法是进行具体负载均衡逻辑的地方，这里只是做了一些判断并调用需
 * 要子类实现的doSelect方法，
 *
 * AbstractLoadBalance有两个权重相关的方法：calculateWarmupWeight和getWeighto
 *
 *
 */
public abstract class AbstractLoadBalance implements LoadBalance {

    /**
     * 计算具体的权重
     *
     * calculateWarmupWeight的计算逻辑比较简单，由于框架考虑了服务刚启动的时候需要有一
     * 个预热的过程，如果一启动就给予100%的流量，则可能会让服务崩溃因此实现了calculateWarmupWeight方法用于计算预热时候的权重
     *
     * 计算逻辑是：(启动至今时间/给予的预热总时间)X权重。
     *
     * 例如：假设我们设置A服务的权重是5,让它预热10分钟，则第一分钟的
     * 时候，它的权重变为(1/10) X5 = 0.5, 0.5/5 = 0.1,也就是只承担10%的流量；
     * 10分钟后，权重就变为(10/10) X5 = 5,也就是权重变为设置的100%,承担了所有的流量。
     *
     * @param uptime
     * @param warmup
     * @param weight
     * @return
     */
    static int calculateWarmupWeight(int uptime, int warmup, int weight) {
        int ww = (int) ((float) uptime / ((float) warmup / (float) weight));
        return ww < 1 ? 1 : (ww > weight ? weight : ww);
    }

    @Override
    public <T> Invoker<T> select(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        if (invokers == null || invokers.isEmpty())
            return null;
        if (invokers.size() == 1)
            return invokers.get(0);
        return doSelect(invokers, url, invocation);
    }

    protected abstract <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation);

    /**
     * 获取权重
     *
     * @param invoker
     * @param invocation
     * @return
     */
    protected int getWeight(Invoker<?> invoker, Invocation invocation) {
        // 通过 URL 获取当前 Invoker设置的权重
        int weight = invoker.getUrl().getMethodParameter(invocation.getMethodName(), Constants.WEIGHT_KEY, Constants.DEFAULT_WEIGHT);
        if (weight > 0) {
            // 获取启动的时间点
            long timestamp = invoker.getUrl().getParameter(Constants.REMOTE_TIMESTAMP_KEY, 0L);
            if (timestamp > 0L) {
                // 求差值，得到已经预热了多久
                int uptime = (int) (System.currentTimeMillis() - timestamp);
                // 获取设置的总预热时间
                int warmup = invoker.getUrl().getParameter(Constants.WARMUP_KEY, Constants.DEFAULT_WARMUP);
                if (uptime > 0 && uptime < warmup) {
                    // 计算出最后的权重
                    weight = calculateWarmupWeight(uptime, warmup, weight);
                }
            }
        }
        return weight;
    }

}
