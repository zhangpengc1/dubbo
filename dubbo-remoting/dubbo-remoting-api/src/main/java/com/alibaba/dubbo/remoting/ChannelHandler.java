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
package com.alibaba.dubbo.remoting;

import com.alibaba.dubbo.common.extension.SPI;


/**
 * ChannelHandler. (API, Prototype, ThreadSafe)
 *
 *
 * Dubbo 常用 Handler
 *
 * ExchangeHandlerAdapter  用于查找服务方法并调用
 * HeaderExchangeHandler  封装处理Request/Response和Telnet调用能力
 * DecodeHandler  支持在Dubbo线程池中做解码
 * ChannelHandlerDispatcher 封装多Handler广播调用
 * AllChannelHandler 支持Dubbo线程池调用业务方法
 * HeartbeatHandler 支持心跳处理
 * MultiMessageHandler 支持流中多消息报文批处理
 * ConnectionOrderedChannelHandler 单独线程池处理TCP的连接和断开
 * MessageOnlyChannelHandler 仅在线程池处理接收报文，其他事件在I/O线程处理
 * WrappedChannelHandler 基于内存key-value存储封装和共享线程池能力，比如记录线程池等
 * NettyServerHandler 封装Netty服务端事件，处理连接、断开、读取、写入和异常等
 * NettyClientHandler 封装Netty客户端事件，处理连接、断开、读取、写入和异常等
 *
 * Dubbo中提供了大量的Handler去承载特性和扩展，这些Handler最终会和底层通信框架做关联，比如Netty等。
 * 一次完整的RPC调用贯穿了一系列的Handler,如果直接挂载到底层通信框架（Netty ,因为整个链路比较长，则需要触发大量链式查找和事件，不仅低效，而且浪费
 * 资源。
 *
 *
 * --> 入战处理器handler1 --> handler2 --> handler3
 *
 *
 * <-- ChannelPipeline 出站处理器 <-- 出站处理器 <--
 *
 * 如果有一个入站事件被触发，比如连接或数据读取，那么它会从ChannelPipeline头部开始一直传播到Channelpipeline的尾
 * 端。出站的I/O事件将从ChannelPipeline最右边开始，然后向左传播。当然，在ChannelPipeline
 * 传播事件时，它会测试入站是否实现了 ChannellnboundHandler接口，如果没有实现则会自
 * 动跳过，出站时会监测是否实现ChannelOutboundHandler,如果没有实现，那么也会自动跳
 * 过。在Dubbo框架中实现的这两个接口类主要是NettyServerHandler和NettyClientHandler0
 * Dubbo通过装饰者模式层包装Handler,从而不需要将每个Handler都追加到Pipeline中。在
 * NettyServer 和 NettyClient 中最多有 3 个 Handler,分别是编码、解码和 NettyServerHandler
 * 或 NettyClientHandlero
 *
 *
 * @see com.alibaba.dubbo.remoting.Transporter#bind(com.alibaba.dubbo.common.URL, ChannelHandler)
 * @see com.alibaba.dubbo.remoting.Transporter#connect(com.alibaba.dubbo.common.URL, ChannelHandler)
 */

@SPI
public interface ChannelHandler {

    /**
     * on channel connected.
     *
     * Channel已经被创建
     *
     * @param channel channel.
     */
    void connected(Channel channel) throws RemotingException;

    /**
     * on channel disconnected.
     *
     * Channel已经被断开
     *
     * @param channel channel.
     */
    void disconnected(Channel channel) throws RemotingException;

    /**
     * on message sent.
     *
     * 消息被发送
     *
     * @param channel channel.
     * @param message message.
     */
    void sent(Channel channel, Object message) throws RemotingException;

    /**
     * on message received.
     *
     * 消息被接收
     *
     * @param channel channel.
     * @param message message.
     */
    void received(Channel channel, Object message) throws RemotingException;

    /**
     * on exception caught.
     *
     * 捕获到异常
     *
     * @param channel   channel.
     * @param exception exception.
     */
    void caught(Channel channel, Throwable exception) throws RemotingException;

}