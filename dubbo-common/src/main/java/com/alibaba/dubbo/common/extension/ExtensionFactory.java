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

/**
 * ExtensionFactory
 *
 * 工厂接口有多个实现，那么是怎么确定使用哪个工厂实现的呢？我们可以看到
 * AdaptiveExtensionFactory 这个实现类工厂上@Adaptive 注解。因此，AdaptiveExtensionFactory会作为一开始的默认实现
 *
 * 除了 AdaptiveExtensionFactory,还有 SpiExtensionFactory 和 SpringExtensionFactory
 * 两个工厂。也就是说，我们除了可以从Dubbo SPI管理的容器中获取扩展点实例，还可以从Spring
 * 容器中获取。
 *
 * 那么Dubbo和Spring容器之间是如何打通的呢？我们先来看SpringExtensionFactory的
 * 实现，该工厂提供了保存Spring上下文的静态方法，可以把Spring上下文保存到Set集合中。 当调用getExtension获取扩展类时，会遍历Set集合中所有的Spring上下文，先根据名字依次
 * 从每个Spring容器中进行匹配，如果根据名字没匹配到，则根据类型去匹配，如果还没匹配到
 * 则返回nul
 */
@SPI
public interface ExtensionFactory {

    /**
     * Get extension.
     *
     * @param type object type.
     * @param name object name.
     * @return object instance.
     */
    <T> T getExtension(Class<T> type, String name);

}
