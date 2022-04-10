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

import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;

import java.util.List;

/**
 * When invoke fails, log the error message and ignore this error by returning an empty RpcResult.
 * Usually used to write audit logs and other operations
 *
 * <a href="http://en.wikipedia.org/wiki/Fail-safe">Fail-safe</a>
 *
 * Failsafe调用时如果出现异常，则会直接忽略。
 *
 * 实现也非常简单，步骤如下：
 * 1.校验传入的参数。校验从AbstractClusterlnvoker传入的Invoker列表是否为空。
 * 2.负载均衡。调用select方法做负载均衡，得到要调用的节点。
 * 3.远程调用。在try代码块中调用invoker#invoke方法做远程调用，“catch”到任何异
 * 常都直接“吞掉”，返回一个空的结果集。
 *
 */
public class FailsafeClusterInvoker<T> extends AbstractClusterInvoker<T> {
    private static final Logger logger = LoggerFactory.getLogger(FailsafeClusterInvoker.class);

    public FailsafeClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    @Override
    public Result doInvoke(Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        try {
            // ①校验传入的参数
            checkInvokers(invokers, invocation);
            // ②做负载均衡
            Invoker<T> invoker = select(loadbalance, invocation, invokers, null);
            // 进行远程调用，调用成功则直接返回
            return invoker.invoke(invocation);
        } catch (Throwable e) {
            logger.error("Failsafe ignore exception: " + e.getMessage(), e);
            // 捕获到异常，直接返回一个空的结果集
            return new RpcResult(); // ignore
        }
    }
}
