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
package com.alibaba.dubbo.rpc.protocol.dubbo.telnet;

import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.common.utils.CollectionUtils;
import com.alibaba.dubbo.common.utils.PojoUtils;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.telnet.TelnetHandler;
import com.alibaba.dubbo.remoting.telnet.support.Help;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.protocol.dubbo.DubboProtocol;
import com.alibaba.fastjson.JSON;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * InvokeTelnetHandler
 */
@Activate
@Help(parameter = "[service.]method(args)", summary = "Invoke the service method.", detail = "Invoke the service method.")
public class InvokeTelnetHandler implements TelnetHandler {
    static final String INVOKE_MESSAGE_KEY = "telnet.invoke.method.message";

    static final String INVOKE_METHOD_LIST_KEY = "telnet.invoke.method.list";


    /**
     * Telnet本地方法调用
     *
     * 当本地没有客户端，想测试服务端提供的方法时，可以使用Telnet登录到远程服务器(Telnet IP port),
     * 根据invoke指令执行方法调用来获得结果。当用户输入invoke指令时， 会被转发到对应的Handlero
     * 在①中提取方法调用信息(去除参数信息)，在②中会提取调用括号内的信息作为参数值。
     * 在③中提取方法调用的接口信息，在④中提取接口调用的方法名称。
     * 在⑤中会将传递的JSON参数值转换成fastjson对象,然后在⑥中根据接口名称、方法和参数值查找对应的方法和Invoker对象。
     * 在真正方法调用前，需要通过⑦把fastjson对象转换成Java对象，在⑧中触发方法调用并返回结果值。
     *
     * @param channel
     * @param message
     * @return
     */
    @Override
    public String telnet(Channel channel, String message) {
        if (message == null || message.length() == 0) {
            return "Please input method name, eg: \r\ninvoke xxxMethod(1234, \"abcd\", {\"prop\" : \"value\"})\r\ninvoke XxxService.xxxMethod(1234, \"abcd\", {\"prop\" : \"value\"})\r\ninvoke com.xxx.XxxService.xxxMethod(1234, \"abcd\", {\"prop\" : \"value\"})";
        }
        StringBuilder buf = new StringBuilder();
        String service = (String) channel.getAttribute(ChangeTelnetHandler.SERVICE_KEY);
        if (service != null && service.length() > 0) {
            buf.append("Use default service " + service + ".\r\n");
        }
        int i = message.indexOf("(");
        if (i < 0 || !message.endsWith(")")) {
            return "Invalid parameters, format: service.method(args)";
        }
        // ①提取调用方法(由接口名.方法名组成)
        String method = message.substring(0, i).trim();
        // ② 提取调用方法参数值
        String args = message.substring(i + 1, message.length() - 1).trim();
        i = method.lastIndexOf(".");
        if (i >= 0) {
            // ③ 提取方法前面的接口
            service = method.substring(0, i).trim();
            // ④ 提取方法名称
            method = method.substring(i + 1).trim();
        }
        List<Object> list;
        try {
            // ⑤ 将参数JSON串转换成JSON对象
            list = JSON.parseArray("[" + args + "]", Object.class);
        } catch (Throwable t) {
            return "Invalid json argument, cause: " + t.getMessage();
        }
        Invoker<?> invoker = null;
        Method invokeMethod = null;
        Collection<Exporter<?>> exporters = DubboProtocol.getDubboProtocol().getExporters();
        if (isInvokedSelectCommand(channel)) {
            invokeMethod = (Method) channel.getAttribute(SelectTelnetHandler.SELECT_METHOD_KEY);
            for (Exporter<?> exporter : exporters) {
                if (invokeMethod.getDeclaringClass().getName().equals(exporter.getInvoker().getInterface().getName())) {
                    invoker = exporter.getInvoker();
                    break;
                }
            }
        } else {
            if ((StringUtils.isBlank(service))) {
                if (exporters.size() != 1) {
                    //no default service we should not continue
                    return "Failed to find service !";
                }
            }
            for (Exporter<?> exporter : exporters) {
                if (StringUtils.isBlank(service)
                        || service.equals(exporter.getInvoker().getInterface().getSimpleName())
                        || service.equals(exporter.getInvoker().getInterface().getName())
                        || service.equals(exporter.getInvoker().getUrl().getPath())) {
                    invoker = exporter.getInvoker();
                    List<Method> methodList = findSameSignatureMethod(exporter.getInvoker().getInterface(), method, list);
                    if (CollectionUtils.isNotEmpty(methodList)) {
                        if (methodList.size() == 1) {
                            invokeMethod = methodList.get(0);
                        } else {
                            // ⑥ 接口名、方法、参数值和类型作为检索方法的条件
                            List<Method> matchMethods = findMatchMethods(methodList, list);
                            if (CollectionUtils.isNotEmpty(matchMethods)) {
                                if (matchMethods.size() == 1) {
                                    invokeMethod = matchMethods.get(0);
                                } else { //exist overridden method
                                    channel.setAttribute(INVOKE_METHOD_LIST_KEY, matchMethods);
                                    channel.setAttribute(INVOKE_MESSAGE_KEY, message);
                                    printSelectMessage(buf, matchMethods);
                                    return buf.toString();
                                }
                            }
                        }
                    }
                    break;
                }
            }
        }

        if (invoker != null) {
            if (invokeMethod != null) {
                try {
                    // ⑦ 将3SON参数值转换成Java对象值
                    Object[] array = PojoUtils.realize(list.toArray(), invokeMethod.getParameterTypes(), invokeMethod.getGenericParameterTypes());
                    RpcContext.getContext().setLocalAddress(channel.getLocalAddress()).setRemoteAddress(channel.getRemoteAddress());
                    long start = System.currentTimeMillis();
                    // ⑧ 根据查找到的Invoker、构造Rpclnvocation进行方法调用
                    Object result = invoker.invoke(new RpcInvocation(invokeMethod, array)).recreate();
                    long end = System.currentTimeMillis();
                    buf.append(JSON.toJSONString(result));
                    buf.append("\r\nelapsed: ");
                    buf.append(end - start);
                    buf.append(" ms.");
                } catch (Throwable t) {
                    return "Failed to invoke method " + invokeMethod.getName() + ", cause: " + StringUtils.toString(t);
                }
            } else {
                buf.append("No such method " + method + " in service " + service);
            }
        } else {
            buf.append("No such service " + service);
        }
        return buf.toString();
    }

    private List<Method> findSameSignatureMethod(Class clazz, String lookupMethodName, List<Object> args) {
        List<Method> sameSignatureMethods = new ArrayList<Method>();
        Method[] declaredMethods = clazz.getDeclaredMethods();
        for (Method method : declaredMethods) {
            if (method.getName().equals(lookupMethodName) && method.getParameterTypes().length == args.size()) {
                sameSignatureMethods.add(method);
            }
        }
        return sameSignatureMethods;
    }

    private List<Method> findMatchMethods(List<Method> methods, List<Object> args) {
        List<Method> matchMethod = new ArrayList<Method>();
        for (Method method : methods) {
            if (isMatch(method, args)) {
                matchMethod.add(method);
            }
        }
        return matchMethod;
    }

    private static boolean isMatch(Method method, List<Object> args) {
        Class<?>[] types = method.getParameterTypes();
        if (types.length != args.size()) {
            return false;
        }
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            Object arg = args.get(i);

            if (arg == null) {
                if (type.isPrimitive()) {
                    return false;
                }

                // if the type is not primitive, we choose to believe what the invoker want is a null value
                continue;
            }

            if (ReflectUtils.isPrimitive(arg.getClass())) {
                // allow string arg to enum type, @see PojoUtils.realize0()
                if (arg instanceof String && type.isEnum()) {
                    continue;
                }

                if (!ReflectUtils.isPrimitive(type)) {
                    return false;
                }

                if (!ReflectUtils.isCompatible(type, arg)) {
                    return false;
                }
            } else if (arg instanceof Map) {
                String name = (String) ((Map<?, ?>) arg).get("class");
                if (StringUtils.isNotEmpty(name)) {
                    Class<?> cls = ReflectUtils.forName(name);
                    if (!type.isAssignableFrom(cls)) {
                        return false;
                    }
                } else {
                    return true;
                }
            } else if (arg instanceof Collection) {
                if (!type.isArray() && !type.isAssignableFrom(arg.getClass())) {
                    return false;
                }
            } else {
                if (!type.isAssignableFrom(arg.getClass())) {
                    return false;
                }
            }
        }
        return true;
    }


    private void printSelectMessage(StringBuilder buf, List<Method> methods) {
        buf.append("Methods:\r\n");
        for (int i = 0; i < methods.size(); i++) {
            Method method = methods.get(i);
            buf.append(i + 1).append(". ").append(method.getName()).append("(");
            Class<?>[] parameterTypes = method.getParameterTypes();
            for (int n = 0; n < parameterTypes.length; n++) {
                buf.append(parameterTypes[n].getSimpleName());
                if (n != parameterTypes.length - 1) {
                    buf.append(",");
                }
            }
            buf.append(")\r\n");
        }
        buf.append("Please use the select command to select the method you want to invoke. eg: select 1");
    }

    private boolean isInvokedSelectCommand(Channel channel) {
        if (channel.hasAttribute(SelectTelnetHandler.SELECT_KEY)) {
            channel.removeAttribute(SelectTelnetHandler.SELECT_KEY);
            return true;
        }
        return false;
    }
}
