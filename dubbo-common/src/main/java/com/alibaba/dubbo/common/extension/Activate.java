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
 * Activate. This annotation is useful for automatically activate certain extensions with the given criteria,
 * for examples: <code>@Activate</code> can be used to load certain <code>Filter</code> extension when there are
 * multiple implementations.
 * <ol>
 * <li>{@link Activate#group()} specifies group criteria. Framework SPI defines the valid group values.
 * <li>{@link Activate#value()} specifies parameter key in {@link URL} criteria.
 * </ol>
 * SPI provider can call {@link ExtensionLoader#getActivateExtension(URL, String, String)} to find out all activated
 * extensions with the given criteria.
 *
 * @see SPI
 * @see URL
 * @see ExtensionLoader
 *
 * @Activate可以标记在类、接口、枚举类和方法上。主要使用在有多个扩展点实现、需要根
 * 据不同条件被激活的场景中，如Filter需要多个同时激活，因为每个Filter实现的是不同的功能。
 * ©Activate可传入的参数很多。
 *
 *
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Activate {
    /**
     * Activate the current extension when one of the groups matches. The group passed into
     * {@link ExtensionLoader#getActivateExtension(URL, String, String)} will be used for matching.
     *
     * @return group names to match
     * @see ExtensionLoader#getActivateExtension(URL, String, String)
     *
     * URL中的分组如果匹配则激活，则可以设置多个
     */
    String[] group() default {};

    /**
     * Activate the current extension when the specified keys appear in the URL's parameters.
     * <p>
     * For example, given <code>@Activate("cache, validation")</code>, the current extension will be return only when
     * there's either <code>cache</code> or <code>validation</code> key appeared in the URL's parameters.
     * </p>
     *
     * @return URL parameter keys
     * @see ExtensionLoader#getActivateExtension(URL, String)
     * @see ExtensionLoader#getActivateExtension(URL, String, String)
     *
     * 查找URL中如果含有该key值，则会激活
     */
    String[] value() default {};

    /**
     * Relative ordering info, optional
     *
     * 填写扩展点列表，表示哪些扩展点要在本扩展点之前
     * @return extension list which should be put before the current one
     */
    String[] before() default {};

    /**
     * Relative ordering info, optional
     *
     * 同上，表示哪些需要在本扩展点之后
     * @return extension list which should be put after the current one
     */
    String[] after() default {};

    /**
     * Absolute ordering info, optional
     *
     * 整型，直接的排序信息
     * @return absolute ordering info
     */
    int order() default 0;
}