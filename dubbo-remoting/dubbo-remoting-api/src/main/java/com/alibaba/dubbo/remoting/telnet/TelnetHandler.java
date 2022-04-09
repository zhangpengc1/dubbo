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
package com.alibaba.dubbo.remoting.telnet;

import com.alibaba.dubbo.common.extension.SPI;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.RemotingException;

/**
 * TelnetHandler
 *
 * 通过这个扩展点的定义，能够解决扩展更多命令的诉求。message包含处理命令之外的所
 * 有字符串参数，具体如何使用这些参数及这些参数的定义全部交给命令实现者决定。
 *
 *
 */
@SPI
public interface TelnetHandler {

    /**
     * telnet.
     *
     * @param channel
     * @param message
     */
    String telnet(Channel channel, String message) throws RemotingException;

}