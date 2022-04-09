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
package com.alibaba.dubbo.rpc;

/**
 * Exporter. (API/SPI, Prototype, ThreadSafe)
 *
 * @see com.alibaba.dubbo.rpc.Protocol#export(Invoker)
 * @see com.alibaba.dubbo.rpc.ExporterListener
 * @see com.alibaba.dubbo.rpc.protocol.AbstractExporter
 *
 *
 * Dubbo框架做服务暴露分为两大部分
 *
 * 第一步将持有的服务实例通过代理转换成Invoker
 * 第二步会把Invoker通过具体的协议（比如Dubbo) 转换成Exporter
 *
 * 框架做了这层抽象也大大方便了功能扩展。这里的Invoker可以简单理解成一个真实的服务对象实例，是
 * Dubbo框架实体域，所有模型都会向它靠拢，可向它发起invoke调用。它可能是一个本地的实
 * 现，也可能是一个远程的实现，还可能是一个集群实现。
 */
public interface Exporter<T> {

    /**
     * get invoker.
     *
     * @return invoker
     */
    Invoker<T> getInvoker();

    /**
     * unexport.
     * <p>
     * <code>
     * getInvoker().destroy();
     * </code>
     */
    void unexport();

}