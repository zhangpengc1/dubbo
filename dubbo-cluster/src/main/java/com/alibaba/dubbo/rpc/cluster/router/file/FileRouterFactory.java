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
package com.alibaba.dubbo.rpc.cluster.router.file;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.utils.IOUtils;
import com.alibaba.dubbo.rpc.cluster.Router;
import com.alibaba.dubbo.rpc.cluster.RouterFactory;
import com.alibaba.dubbo.rpc.cluster.router.script.ScriptRouterFactory;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * 文件路由是把规则写在文件中，文件中写的是自定义的脚本规则，可以是JavaScript，Groovy等，
 * URL中对应的key值填写的是文件的路径。文件路由主要做的就是把文件中的路由脚本读出来，然后调用路由的工厂去匹配对应的脚本路由做解析。
 *
 */
public class FileRouterFactory implements RouterFactory {

    public static final String NAME = "file";

    private RouterFactory routerFactory;

    public void setRouterFactory(RouterFactory routerFactory) {
        this.routerFactory = routerFactory;
    }

    /**
     * 文件路由实现逻辑
     * @param url
     * @return
     */
    @Override
    public Router getRouter(URL url) {
        try {
            // Transform File URL into Script Route URL, and Load
            // file:///d:/path/to/route.js?router=script ==> script:///d:/path/to/route.js?type=js&rule=<file-content>
            // 把类型为file的protocol替换为script类型
            String protocol = url.getParameter(Constants.ROUTER_KEY, ScriptRouterFactory.NAME); // Replace original protocol (maybe 'file') with 'script'
            // 解析文件的后缀名，后续用于匹配的到底是什么脚本，如JS、Groovy等
            String type = null; // Use file suffix to config script type, e.g., js, groovy ...
            String path = url.getPath();
            if (path != null) {
                int i = path.lastIndexOf('.');
                if (i > 0) {
                    type = path.substring(i + 1);
                }
            }
            // 读取文件
            String rule = IOUtils.read(new FileReader(new File(url.getAbsolutePath())));
            // 读取是否是运行时的参数
            boolean runtime = url.getParameter(Constants.RUNTIME_KEY, false);
            // 生成路由工厂可以识别的URL,并把参数添加进去
            URL script = url.setProtocol(protocol).addParameter(Constants.TYPE_KEY, type).addParameter(Constants.RUNTIME_KEY, runtime).addParameterAndEncoded(Constants.RULE_KEY, rule);
            // 再次调用路由的工厂，由于前面配置了 protocol为script类型，这里会使用脚本路由进行解析
            return routerFactory.getRouter(script);
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

}
