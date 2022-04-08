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
package com.alibaba.dubbo.common.extension;

import com.alibaba.dubbo.common.URL;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Provide helpful information for {@link ExtensionLoader} to inject dependency extension instance.
 *
 * @see ExtensionLoader
 * @see URL
 *
 * 该注解也可以传入value参数，是一个数组
 * Adaptive可以传入多个key值，在初始化Adaptive注解的接口时，会先对传入的URL进行key值匹配，
 * 第一个key没匹配上则匹配第二个，以此类推。直到所有的key匹配完毕，如果还没有匹配到， 则会使用“驼峰规则”匹配，如果也没匹配到，则会抛出IllegalStateException异常。
 *
 * 什么是"驼峰规则”呢？如果包装类（Wrapper 没有用Adaptive指定key值，
 * 则Dubbo会自动把接口名称根据驼峰大小写分开，并用符号连接起来，以此来作为默认实现类的名
 * 称，如 org.apache.dubbo.xxx.YyylnvokerWpappep 中的 YyylnvokerWrapper 会被转换为
 * yyy.invoker.wrappero
 *
 * 最后，为什么有些实现类上会标注©Adaptive呢？放在实现类上，主要是为了直接固定对
 * 应的实现而不需要动态生成代码实现，就像策略模式直接确定实现类。在代码中的实现方式是： ExtensionLoader中会缓存两个与©Adaptive有关的对象，一个缓存在cachedAdaptiveClass中， 即Adaptive具体实现类的Class类型；另外一个缓存在cachedAdaptivelnstance中，即Class
 * 的具体实例化对象。在扩展点初始化时，如果发现实现类有@Adaptive注解，则直接赋值给
 * cachedAdaptiveClass ,后续实例化类的时候，就不会再动态生成代码，直接实例化
 * cachedAdaptiveClass,并把实例缓存到cachedAdaptivelnstance中。如果注解在接口方法上， 则会根据参数，动态获得扩展点的实现，会生成Adaptive类
 *
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Adaptive {
    /**
     * Decide which target extension to be injected. The name of the target extension is decided by the parameter passed
     * in the URL, and the parameter names are given by this method.
     * <p>
     * If the specified parameters are not found from {@link URL}, then the default extension will be used for
     * dependency injection (specified in its interface's {@link SPI}).
     * <p>
     * For examples, given <code>String[] {"key1", "key2"}</code>:
     * <ol>
     * <li>find parameter 'key1' in URL, use its value as the extension's name</li>
     * <li>try 'key2' for extension's name if 'key1' is not found (or its value is empty) in URL</li>
     * <li>use default extension if 'key2' doesn't appear either</li>
     * <li>otherwise, throw {@link IllegalStateException}</li>
     * </ol>
     * If default extension's name is not give on interface's {@link SPI}, then a name is generated from interface's
     * class name with the rule: divide classname from capital char into several parts, and separate the parts with
     * dot '.', for example: for {@code com.alibaba.dubbo.xxx.YyyInvokerWrapper}, its default name is
     * <code>String[] {"yyy.invoker.wrapper"}</code>. This name will be used to search for parameter from URL.
     *
     * @return parameter key names in URL
     */
    String[] value() default {};

}