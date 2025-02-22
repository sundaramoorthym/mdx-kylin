/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package io.kylin.mdx.insight.server.filter;


import org.apache.commons.lang3.StringUtils;
import io.kylin.mdx.insight.common.util.UUIDUtils;
import io.kylin.mdx.insight.core.utils.HttpLogUtils;
import io.kylin.mdx.web.support.HttpRequestWrapper;
import mondrian.xmla.XmlaRequestContext;
import org.apache.log4j.MDC;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public class MdxLoggingFilter implements Filter {

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        HttpRequestWrapper request = HttpRequestWrapper.wrap((HttpServletRequest) servletRequest);
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        XmlaRequestContext context = XmlaRequestContext.newContext();

        // get or generate query uuid
        if (StringUtils.isNotBlank(request.getHeader("X-Trace-Id"))) {
            context.runningStatistics.queryID = request.getHeader("X-Trace-Id");
        } else {
            context.runningStatistics.queryID = UUIDUtils.randomUUID();
        }
        String qid = "[Query " + context.runningStatistics.queryID + "] ";
        MDC.put("qid", qid);

        // try logging http request and response
        try {
            boolean logging = isLogging(context, request);
            if (logging) {
                HttpLogUtils.log(request, request.getBody());
            }
            chain.doFilter(request, response);
            if (logging) {
                HttpLogUtils.log(response, null);
            }
        } finally {
            MDC.remove("qid");
            context.clear();
        }
    }

    /**
     * 基于如下顺序判断是否记录日志
     * #    请求参数携带 enableDebugMode=true
     * #    请求的用户名符合需要 logging 的集合
     *
     * @param context
     * @param request
     * @return
     */
    private boolean isLogging(XmlaRequestContext context, HttpRequestWrapper request) {
        boolean logging = request.isDebugMode();
//        if (!logging) {
//
//        }
        context.debugMode = logging;
        return logging;
    }

}
