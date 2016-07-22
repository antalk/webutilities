/*
 * Copyright 2010-2016 Rajendra Patil
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googlecode.webutilities.filters;

import static com.googlecode.webutilities.common.Constants.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.logging.Level;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.javascript.jscomp.*;
import com.google.javascript.jscomp.Compiler;
import com.googlecode.webutilities.common.Constants;
import com.googlecode.webutilities.common.WebUtilitiesResponseWrapper;
import com.googlecode.webutilities.filters.common.AbstractFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Filter that performs minification using Google Closure Compiler
 *
 * @author rpatil
 * @version 1.0
 */
public class ClosureCompilerFilter extends AbstractFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClosureCompilerFilter.class.getName());

    FilterConfig filterConfig;

    SourceFile nullExtern = SourceFile.fromCode("/dev/null", "");

    private static final String PROCESSED_ATTR = YUIMinFilter.class.getName() + ".MINIFIED";

    public void init(FilterConfig config) throws ServletException {
        super.init(config);
        filterConfig = config;
        CompilerOptions compilerOptions = buildCompilerOptionsFromConfig(config);
        LOGGER.debug("Filter initialized with: {}", compilerOptions.toString());
    }

    //init
    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain) throws IOException, ServletException {

        //build compiler options
        //create compiler object
        //compile, get result
        //write result to output stream
        HttpServletRequest rq = (HttpServletRequest) req;

        HttpServletResponse rs = (HttpServletResponse) resp;

        String url = rq.getRequestURI(), lowerUrl = url.toLowerCase();

        LOGGER.debug("Filtering URI: {}", url);

        boolean alreadyProcessed = req.getAttribute(PROCESSED_ATTR) != null;

        if (!alreadyProcessed
                && isURLAccepted(url)
                && isQueryStringAccepted(rq.getQueryString())
                && isUserAgentAccepted(rq.getHeader(Constants.HTTP_USER_AGENT_HEADER))
                && (lowerUrl.endsWith(EXT_JS) || lowerUrl.endsWith(EXT_JSON) || lowerUrl.endsWith(EXT_CSS))) {

            req.setAttribute(PROCESSED_ATTR, Boolean.TRUE);

            WebUtilitiesResponseWrapper wrapper = new WebUtilitiesResponseWrapper(rs);
            //Let the response be generated

            chain.doFilter(req, wrapper);

            wrapper.flushBuffer();

            Writer out = resp.getWriter();
            String mime = wrapper.getContentType();
            if (!isMIMEAccepted(mime)) {
                out.write(wrapper.getContents());
                out.flush();
                LOGGER.trace("Not minifying. Mime {) not allowed.", mime);
                return;
            }

            ByteArrayInputStream is = new ByteArrayInputStream(wrapper.getBytes());

            //work on generated response
            if (lowerUrl.endsWith(EXT_JS) || lowerUrl.endsWith(EXT_JSON) || (wrapper.getContentType() != null && (wrapper.getContentType().equals(MIME_JS) || wrapper.getContentType().equals(MIME_JSON)))) {
                Compiler closureCompiler = new Compiler(new BasicErrorManager() {
                    @Override
                    public void println(CheckLevel checkLevel, JSError jsError) {
                        if (checkLevel.equals(CheckLevel.WARNING)) {
                            LOGGER.warn("Warning. {}", jsError);
                        } else if (checkLevel.equals(CheckLevel.ERROR)) {
                            LOGGER.error("Error. {}", jsError);
                        }
                    }

                    @Override
                    protected void printSummary() {
                        //!TODO implementation
                    }
                });
                LOGGER.trace("Compressing JS/JSON type");
                CompilationLevel level = CompilationLevel.SIMPLE_OPTIMIZATIONS;
                CompilerOptions compilerOptions = buildCompilerOptionsFromConfig(filterConfig);
                level.setOptionsForCompilationLevel(compilerOptions);
                Result result = closureCompiler.compile(nullExtern, SourceFile.fromInputStream("NULL", is), compilerOptions);
                if (result.success) {
                    out.append(closureCompiler.toSource());
                }
            } else {
                LOGGER.trace("Not Compressing anything.");
                out.write(wrapper.getContents());
            }

            out.flush();
        } else {
            LOGGER.trace("Not minifying. URL/UserAgent not allowed.");
            chain.doFilter(req, resp);
        }
    }

    @SuppressWarnings("unchecked")
    private static CompilerOptions buildCompilerOptionsFromConfig(FilterConfig config) {

        CompilerOptions compilerOptions = new CompilerOptions();
        compilerOptions.setCodingConvention(CodingConventions.getDefault());

        Enumeration<String> initParams = config.getInitParameterNames();
        while (initParams.hasMoreElements()) {
            String name = initParams.nextElement().trim();
            String value = config.getInitParameter(name);
            if ("charset".equals(name)) {
                if (value != null && Charset.isSupported(value)) {
                    compilerOptions.setOutputCharset(Charset.forName(value));
                } else {
                    compilerOptions.setOutputCharset(StandardCharsets.UTF_8);
                }
            } else if ("compilationLevel".equals(name)) {
                CompilationLevel compilationLevel = CompilationLevel.valueOf(value);
                compilationLevel.setOptionsForCompilationLevel(compilerOptions);
            } else if ("formatting".equals(name)) {
                if ("PRETTY_PRINT".equals(value)) {
                    compilerOptions.prettyPrint = true;
                } else if ("PRINT_INPUT_DELIMITER".equals(value)) {
                    compilerOptions.printInputDelimiter = true;
                }
            } else if ("loggingLevel".equals(name)) {
                Compiler.setLoggingLevel(Level.parse(value)); //warning or error
            }
        }
        return compilerOptions;
    }

}

