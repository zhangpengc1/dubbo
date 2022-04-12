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
package com.alibaba.dubbo.rpc.cluster.router.condition;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Router;
import com.alibaba.dubbo.rpc.cluster.router.AbstractRouter;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ConditionRouter 条件路由
 *
 * 参数规则
 * 条件路由使用的是 condition://协议，URL 形式是”condition:// 0.0.0.0/com.fbo.BarService?
 * category=routers&dynamic=false&rule=n + URL.encode(nhost = 10.20.153.10 => host = 10.20.153.11")。
 * 我们可以看到，最后的路由规则会用URL.encode进行编码。下面来看一下官方文档中对每个参数含义的说明
 *
 *
 * 参数名称 含义
 * condition://  表示路由规则的类型，支持条件路由规则和脚本路由规则， 可扩展，必填
 * 0.0.0.0  表示对所有IP地址生效，如果只想对某个IP的生效，则填入具体IP,必填
 * com.fdo.BarService 表示只对指定服务生效，必填
 * category=routers 表示该数据为动态配置类型，必填
 * dynamic=false 表示该数据为持久数据，当注册方退出时，数据依然保存在注册中心，必填
 * enabled=true 覆盖规则是否生效，可不填，默认生效
 * force=false 当路由结果为空时，是否强制执行，如果不强制执行，则路由结果为空的路由规则将自动失效，可不填，默认为fhlse
 * runtime=false 是否在每次调用时执行路由规则，否则只在提供者地址列表变更时预先执行并缓存结果，调用时直接从缓存中获取路由结果。如果用了参数路由，则必须设为true,需要注意设置会影响调用的性能，可不填，默认为fhlse
 * priority=l 路由规则的优先级，用于排序，优先级越大越靠前执行，可不填，默认为0
 * rule=URL.encode("host = 10.20.153.10 =>host = 10.20.153. lln)表示路由规则的内容，必填
 *
 * 路由规则配置示例
 * method = find* =＞ host = 192.168.1.22
 * 这条配置说明所有调用find开头的方法都会被路由到IP为192.168.1.22的服务节点上。
 *
 * =＞之前的部分为消费者匹配条件，将所有参数和消费者的URL进行对比，当消费者
 * 满足匹配条件时，对该消费者执行后面的过滤规则。
 *
 * =＞之后的部分为提供者地址列表的过滤条件，将所有参数和提供者的URL进行对比,消费者最终只获取过滤后的地址列表。
 * • 如果匹配条件为空，则表示应用于所有消费方，如=＞ host != 192.168.1.22。
 * • 如果过滤条件为空，则表示禁止访问，如host = 192.168.1.22 =＞。
 *
 * 整个规则的表达式支持 protocol等占位符方式，也支持=、！=等条件。值可以支持多个，用逗号分隔，如host = 192.168.1.22,192.168.1.23;如果以“*”号结尾，则说明是通配符，
 * 如host = 192.168.1.*表示匹配192.168.1.网段下所有的IP。
 *
 *
 */
public class ConditionRouter extends AbstractRouter {

    private static final Logger logger = LoggerFactory.getLogger(ConditionRouter.class);
    private static final int DEFAULT_PRIORITY = 2;
    private static Pattern ROUTE_PATTERN = Pattern.compile("([&!=,]*)\\s*([^&!=,\\s]+)");
    private final boolean force;
    private final Map<String, MatchPair> whenCondition;
    private final Map<String, MatchPair> thenCondition;

    /**
     * 1.ConditionRouter构造方法的逻辑
     *
     * (1) 根据URL的键rule获取对应的规则字符串。以=>为界，把规则分成两段，前面部分
     * 为whenRule,即消费者匹配条件；后面部分为thenRule,即提供者地址列表的过滤条件。我们
     * 以代码清单7-8的规则为例，其会被解析为whenRule: method = find*和thenRule: host =
     * 192.168.1.22o
     *
     * (2) 分别解析两个路由规则。调用parseRule方法，通过正则表达式不断循环匹配whenRule
     * 和thenRule字符串。解析的时候，会根据key-value之间的分隔符对key-value做分类(如果A=B,
     * 则分隔符为=)，支持的分隔符形式有：A=B、A&B、A!=B、A,B这4种形式。最终参数都会被
     * 封装成一个个MatchPair对象，放入Map中保存。Map的key是参数值，value是MatchPair
     * 对象。以规则为例，会生成以method为key的when Map,以host为key
     * 的 then Mapo value 则分别是包装了 find*和 192.168.1.22 的 MatchPair 对象。
     *
     * MatchPair对象是用来做什么的呢？这个对象一共有两个作用。
     *
     * 第一个作用是通配符的匹配和占位符的赋值。MatchPair对象是内部类，里面只有一个isMatch方法，用于判断值是否能
     * 匹配得上规则。规则里的$、*等通配符都会在MatchPair对象中进行匹配。其中$支持protocok
     * username> password> host> port> path这几个动态参数的占位符。例如：规则中写了Sprotocol,
     * 则会自动从URL中获取protocol的值，并赋值进去。
     *
     * 第二个作用是缓存规则。MatchPair对象中有两个Set集合，一个用于保存匹配的规则，
     * 如=find*；另一个则用于保存不匹配的规则， 如!=find*o这两个集合在后续路由规则匹配的时候会使用到。
     *
     *
     *
     * @param url
     */
    public ConditionRouter(URL url) {
        this.url = url;
        this.priority = url.getParameter(Constants.PRIORITY_KEY, DEFAULT_PRIORITY);
        this.force = url.getParameter(Constants.FORCE_KEY, false);
        try {
            String rule = url.getParameterAndDecoded(Constants.RULE_KEY);
            if (rule == null || rule.trim().length() == 0) {
                throw new IllegalArgumentException("Illegal route rule!");
            }
            rule = rule.replace("consumer.", "").replace("provider.", "");
            int i = rule.indexOf("=>");
            String whenRule = i < 0 ? null : rule.substring(0, i).trim();
            String thenRule = i < 0 ? rule.trim() : rule.substring(i + 2).trim();
            Map<String, MatchPair> when = StringUtils.isBlank(whenRule) || "true".equals(whenRule) ? new HashMap<String, MatchPair>() : parseRule(whenRule);
            Map<String, MatchPair> then = StringUtils.isBlank(thenRule) || "false".equals(thenRule) ? null : parseRule(thenRule);
            // NOTE: It should be determined on the business level whether the `When condition` can be empty or not.
            this.whenCondition = when;
            this.thenCondition = then;
        } catch (ParseException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static Map<String, MatchPair> parseRule(String rule)
            throws ParseException {
        Map<String, MatchPair> condition = new HashMap<String, MatchPair>();
        if (StringUtils.isBlank(rule)) {
            return condition;
        }
        // Key-Value pair, stores both match and mismatch conditions
        MatchPair pair = null;
        // Multiple values
        Set<String> values = null;
        final Matcher matcher = ROUTE_PATTERN.matcher(rule);
        while (matcher.find()) { // Try to match one by one
            String separator = matcher.group(1);
            String content = matcher.group(2);
            // Start part of the condition expression.
            if (separator == null || separator.length() == 0) {
                pair = new MatchPair();
                condition.put(content, pair);
            }
            // The KV part of the condition expression
            else if ("&".equals(separator)) {
                if (condition.get(content) == null) {
                    pair = new MatchPair();
                    condition.put(content, pair);
                } else {
                    pair = condition.get(content);
                }
            }
            // The Value in the KV part.
            else if ("=".equals(separator)) {
                if (pair == null)
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());

                values = pair.matches;
                values.add(content);
            }
            // The Value in the KV part.
            else if ("!=".equals(separator)) {
                if (pair == null)
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());

                values = pair.mismatches;
                values.add(content);
            }
            // The Value in the KV part, if Value have more than one items.
            else if (",".equals(separator)) { // Should be seperateed by ','
                if (values == null || values.isEmpty())
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                values.add(content);
            } else {
                throw new ParseException("Illegal route rule \"" + rule
                        + "\", The error char '" + separator + "' at index "
                        + matcher.start() + " before \"" + content + "\".", matcher.start());
            }
        }
        return condition;
    }

    /**
     * route方法的实现原理
     * ConditionRouter继承了 Router接口，需要实现接口的route方法。该方法的主要功能是过
     * 滤出符合路由规则的Invoker列表，即做具体的条件匹配判断
     *
     * (1) 校验。如果规则没有启用，则直接返回；如果传入的Invoker列表为空，则直接返回
     * 空；如果没有任何的whenRule匹配，即没有规则匹配，则直接返回传入的Invoker列表；如果
     * whenRule有匹配的，但是thenRule为空，即没有匹配上规则的Invoker,则返回空。
     *
     * (2) 遍历Invoker列表，通过thenRule找出所有符合规则的Invoker加入集合。例如：匹
     * 配规则中的method名称和当前URL中的method是不是相等。
     *
     * (3) 返回结果。如果结果集不为空，则直接返回；如果结果集为空，但是规则配置了
     * force=true,即强制过滤，那么就会返回空结果集；非强制则不过滤，即返回所有Invoker列表。
     *
     *
     *
     *
     *
     *
     * @param invokers
     * @param url        refer url
     * @param invocation
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> List<Invoker<T>> route(List<Invoker<T>> invokers, URL url, Invocation invocation)
            throws RpcException {
        if (invokers == null || invokers.isEmpty()) {
            return invokers;
        }
        try {
            if (!matchWhen(url, invocation)) {
                return invokers;
            }
            List<Invoker<T>> result = new ArrayList<Invoker<T>>();
            if (thenCondition == null) {
                logger.warn("The current consumer in the service blacklist. consumer: " + NetUtils.getLocalHost() + ", service: " + url.getServiceKey());
                return result;
            }
            for (Invoker<T> invoker : invokers) {
                if (matchThen(invoker.getUrl(), url)) {
                    result.add(invoker);
                }
            }
            if (!result.isEmpty()) {
                return result;
            } else if (force) {
                logger.warn("The route result is empty and force execute. consumer: " + NetUtils.getLocalHost() + ", service: " + url.getServiceKey() + ", router: " + url.getParameterAndDecoded(Constants.RULE_KEY));
                return result;
            }
        } catch (Throwable t) {
            logger.error("Failed to execute condition router rule: " + getUrl() + ", invokers: " + invokers + ", cause: " + t.getMessage(), t);
        }
        return invokers;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public int compareTo(Router o) {
        if (o == null || o.getClass() != ConditionRouter.class) {
            return 1;
        }
        ConditionRouter c = (ConditionRouter) o;
        return this.priority == c.priority ? url.toFullString().compareTo(c.url.toFullString()) : (this.priority > c.priority ? 1 : -1);
    }

    boolean matchWhen(URL url, Invocation invocation) {
        return whenCondition == null || whenCondition.isEmpty() || matchCondition(whenCondition, url, null, invocation);
    }

    private boolean matchThen(URL url, URL param) {
        return !(thenCondition == null || thenCondition.isEmpty()) && matchCondition(thenCondition, url, param, null);
    }

    private boolean matchCondition(Map<String, MatchPair> condition, URL url, URL param, Invocation invocation) {
        Map<String, String> sample = url.toMap();
        boolean result = false;
        for (Map.Entry<String, MatchPair> matchPair : condition.entrySet()) {
            String key = matchPair.getKey();
            String sampleValue;
            //get real invoked method name from invocation
            if (invocation != null && (Constants.METHOD_KEY.equals(key) || Constants.METHODS_KEY.equals(key))) {
                sampleValue = invocation.getMethodName();
            } else {
                sampleValue = sample.get(key);
                if (sampleValue == null) {
                    sampleValue = sample.get(Constants.DEFAULT_KEY_PREFIX + key);
                }
            }
            if (sampleValue != null) {
                if (!matchPair.getValue().isMatch(sampleValue, param)) {
                    return false;
                } else {
                    result = true;
                }
            } else {
                //not pass the condition
                if (!matchPair.getValue().matches.isEmpty()) {
                    return false;
                } else {
                    result = true;
                }
            }
        }
        return result;
    }

    private static final class MatchPair {
        final Set<String> matches = new HashSet<String>();
        final Set<String> mismatches = new HashSet<String>();

        private boolean isMatch(String value, URL param) {
            if (!matches.isEmpty() && mismatches.isEmpty()) {
                for (String match : matches) {
                    if (UrlUtils.isMatchGlobPattern(match, value, param)) {
                        return true;
                    }
                }
                return false;
            }

            if (!mismatches.isEmpty() && matches.isEmpty()) {
                for (String mismatch : mismatches) {
                    if (UrlUtils.isMatchGlobPattern(mismatch, value, param)) {
                        return false;
                    }
                }
                return true;
            }

            if (!matches.isEmpty() && !mismatches.isEmpty()) {
                //when both mismatches and matches contain the same value, then using mismatches first
                for (String mismatch : mismatches) {
                    if (UrlUtils.isMatchGlobPattern(mismatch, value, param)) {
                        return false;
                    }
                }
                for (String match : matches) {
                    if (UrlUtils.isMatchGlobPattern(match, value, param)) {
                        return true;
                    }
                }
                return false;
            }
            return false;
        }
    }
}
