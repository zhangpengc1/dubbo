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
package com.alibaba.dubbo.remoting.telnet.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.telnet.TelnetHandler;
import com.alibaba.dubbo.remoting.transport.ChannelHandlerAdapter;

/**
 * 完成Telnet指令转发的核心实现类
 *
 * 它的实现非常简单，首先将用户输入的指令识别成commandC比如invoke>Is和status),
 * 然后将剩余的内容解析成message,message会交给命令实现者去处理
 */
public class TelnetHandlerAdapter extends ChannelHandlerAdapter implements TelnetHandler {

    private final ExtensionLoader<TelnetHandler> extensionLoader = ExtensionLoader.getExtensionLoader(TelnetHandler.class);

    /**
     * Telnet转发解析
     *
     * 理解编解码后，可以更好地理解上层的实现和原理，
     * 在①中提取Telnet一行消息的首个字符串作为命令，如果命令行有空格，则将后面的内容作为字符串，
     * 再通过②提取并存储到message中。在③中判断并加载是否有对应的扩展点，
     * 如果存在对应的Telnet扩展点，则会通过④加载具体的扩展点并调用其telnet方法，
     * 最后连同返回结果并追加消息结束符(在⑤中处理)返回给调用方。
     *
     * @param channel
     * @param message
     * @return
     * @throws RemotingException
     */
    @Override
    public String telnet(Channel channel, String message) throws RemotingException {
        String prompt = channel.getUrl().getParameterAndDecoded(Constants.PROMPT_KEY, Constants.DEFAULT_PROMPT);
        boolean noprompt = message.contains("--no-prompt");
        message = message.replace("--no-prompt", "");
        StringBuilder buf = new StringBuilder();
        message = message.trim();
        String command;
        if (message.length() > 0) {
            int i = message.indexOf(' ');
            if (i > 0) {
                // ① 提取执行命令
                command = message.substring(0, i).trim();
                // ②提取命令后的所有字符串
                message = message.substring(i + 1).trim();
            } else {
                command = message;
                message = "";
            }
        } else {
            command = "";
        }

        // ③检查系统是否有命令对应的扩展点
        if (command.length() > 0) {
            if (extensionLoader.hasExtension(command)) {
                if (commandEnabled(channel.getUrl(), command)) {
                    try {
                        String result = extensionLoader.getExtension(command).telnet(channel, message);
                        if (result == null) {
                            return null;
                        }
                        buf.append(result);
                    } catch (Throwable t) {
                        buf.append(t.getMessage());
                    }
                } else {
                    buf.append("Command: ");
                    buf.append(command);
                    buf.append(" disabled");
                }
            } else {
                buf.append("Unsupported command: ");
                buf.append(command);
            }
        }
        if (buf.length() > 0) {
            // ⑤ 在Telnet消息结尾追加回车和换行
            buf.append("\r\n");
        }
        if (prompt != null && prompt.length() > 0 && !noprompt) {
            buf.append(prompt);
        }
        return buf.toString();
    }

    private boolean commandEnabled(URL url, String command) {
        boolean commandEnable = false;
        String supportCommands = url.getParameter(Constants.TELNET);
        if (StringUtils.isEmpty(supportCommands)) {
            commandEnable = true;
        } else {
            String[] commands = Constants.COMMA_SPLIT_PATTERN.split(supportCommands);
            for (String c : commands) {
                if (command.equals(c)) {
                    commandEnable = true;
                    break;
                }
            }
        }
        return commandEnable;
    }

}
