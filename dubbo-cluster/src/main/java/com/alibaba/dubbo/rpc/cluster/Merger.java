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
package com.alibaba.dubbo.rpc.cluster;

import com.alibaba.dubbo.common.extension.SPI;

/**
 *
 * 如果我们需要并行调用不同group的服务，并且要把结果集合并起来，则要用到Merger特性。
 *
 * Merger实现了多个服务调用后结果合并的逻辑。虽然业务层可以自行实现这个能力，但
 * Dubbo直接封装到框架中，作为一种扩展点能力，简化了业务开发的复杂度
 *
 * 框架中有一些默认的合并实现。Merger接口上有@，?1注解，没有默认值，属于SPI扩展
 * 点。用户可以基于Merger扩展点接口实现自己的自定义类型合并器。
 *
 * @param <T>
 */
@SPI
public interface Merger<T> {

    T merge(T... items);

}
