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
package com.alibaba.dubbo.common.extension.factory;

import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.ExtensionFactory;
import com.alibaba.dubbo.common.extension.ExtensionLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AdaptiveExtensionFactory
 *
 * 被AdaptiveExtensionFactory缓存的工厂会通过TreeSet进行排序，SPI排在前面，Spring
 * 排在后面。当调用getExtension方法时，会遍历所有的工厂，先从SPI容器中获取扩展类；如
 * 果没找到，则再从Spring容器中查找。我们可以理解为，AdaptiveExtensionFactory持有了所
 * 有的具体工厂实现，它的getExtension方法中只是遍历了它持有的所有工厂，最终还是调用
 * SPI或Spring工厂实现的getExtension方法。
 */
@Adaptive
public class AdaptiveExtensionFactory implements ExtensionFactory {

    // 用来缓存所有工厂实现，包括 SpiExtensionFactory、SpringExtensionFactory
    private final List<ExtensionFactory> factories;

    public AdaptiveExtensionFactory() {
        // 工厂列表也是通过SPI实现的，因此可以在这里获取所有工厂的扩展点加载器
        ExtensionLoader<ExtensionFactory> loader = ExtensionLoader.getExtensionLoader(ExtensionFactory.class);
        List<ExtensionFactory> list = new ArrayList<ExtensionFactory>();
        // 遍历所有的工厂名称，获取对应的工厂，并保存到factories列表中
        for (String name : loader.getSupportedExtensions()) {
            list.add(loader.getExtension(name));
        }
        factories = Collections.unmodifiableList(list);
    }

    @Override
    public <T> T getExtension(Class<T> type, String name) {
        // 遍历所有工厂进行查找，顺序是SPI一Spring
        for (ExtensionFactory factory : factories) {
            T extension = factory.getExtension(type, name);
            if (extension != null) {
                return extension;
            }
        }
        return null;
    }

}
