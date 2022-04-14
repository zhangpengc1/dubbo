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

package com.alibaba.dubbo.rpc.cluster.merger;

import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.rpc.cluster.Merger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 通过MergerFactory获得各种具体的Merger实现
 *
 * 如果开启了 Merger特性，并且未指定合并器(Merger的具体实现)，则框架会根据接口的
 * 返回类型自动匹配合并器。我们可以扩展属于自己的合并器，MergerFactory在加载具体实现
 * 的时候，会用ExtensionLoader把所有SPI的实现都加载到缓存中。后续使用时直接从缓存中
 * 读取，如果读不到则会重新全量加载一次SPIo内置的合并我们可以分为四类：Array、Set、List、 Map,实现都比较简单。
 *
 */
public class MergerFactory {

    private static final ConcurrentMap<Class<?>, Merger<?>> mergerCache =
            new ConcurrentHashMap<Class<?>, Merger<?>>();

    public static <T> Merger<T> getMerger(Class<T> returnType) {
        Merger result;
        if (returnType.isArray()) {
            Class type = returnType.getComponentType();
            result = mergerCache.get(type);
            if (result == null) {
                loadMergers();
                result = mergerCache.get(type);
            }
            if (result == null && !type.isPrimitive()) {
                result = ArrayMerger.INSTANCE;
            }
        } else {
            result = mergerCache.get(returnType);
            if (result == null) {
                loadMergers();
                result = mergerCache.get(returnType);
            }
        }
        return result;
    }

    static void loadMergers() {
        Set<String> names = ExtensionLoader.getExtensionLoader(Merger.class)
                .getSupportedExtensions();
        for (String name : names) {
            Merger m = ExtensionLoader.getExtensionLoader(Merger.class).getExtension(name);
            mergerCache.putIfAbsent(ReflectUtils.getGenericClass(m.getClass()), m);
        }
    }

}
