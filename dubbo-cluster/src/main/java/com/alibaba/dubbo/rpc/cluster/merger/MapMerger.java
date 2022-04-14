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

import com.alibaba.dubbo.rpc.cluster.Merger;

import java.util.HashMap;
import java.util.Map;

public class MapMerger implements Merger<Map<?, ?>> {

    /**
     * 整个实现的思路就是，在Merge中新建了一个Map,把返回的多个Map合并成一个。
     *
     * @param items
     * @return
     */
    @Override
    public Map<?, ?> merge(Map<?, ?>... items) {
        // 如果结果集为空，则直接返回null
        if (items.length == 0) {
            return null;
        }

        // 如果结果集不为空则新建一个Map,遍历返回的结果集并放入新的Map
        Map<Object, Object> result = new HashMap<Object, Object>();
        for (Map<?, ?> item : items) {
            if (item != null) {
                result.putAll(item);
            }
        }
        return result;
    }

}
