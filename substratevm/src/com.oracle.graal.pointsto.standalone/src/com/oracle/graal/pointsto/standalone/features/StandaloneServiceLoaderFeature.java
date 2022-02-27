/*
 * Copyright (c) 2022, 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2022, 2022, Alibaba Group Holding Limited. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.oracle.graal.pointsto.standalone.features;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.options.OptionKey;
import org.graalvm.compiler.options.OptionType;
import org.graalvm.nativeimage.hosted.Feature;

import com.oracle.graal.pointsto.constraints.UnsupportedFeatureException;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.standalone.features.StandaloneAnalysisFeatureImpl.BeforeAnalysisAccessImpl;
import com.oracle.graal.pointsto.standalone.features.StandaloneAnalysisFeatureImpl.DuringAnalysisAccessImpl;
import com.oracle.graal.pointsto.util.AnalysisError;
import com.oracle.svm.common.util.ResourceUtils;

public class StandaloneServiceLoaderFeature implements Feature {
    public static class Options {
        // @formatter:off
        @Option(help = "Register services in standalone pointsto analysis", type = OptionType.User)
        public static final OptionKey<Boolean> AnalysisRegisterServices = new OptionKey<>(true);
        // @formatter:on
    }

    /**
     * Set of types that are already processed (if they are a service interface) or are already
     * known to be not a service interface.
     */
    private final Map<AnalysisType, Boolean> processedTypes = new ConcurrentHashMap<>();

    /**
     * Known services and their providers declared using modules.
     */
    private Map<String, List<String>> serviceProviders;

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess a) {
        BeforeAnalysisAccessImpl access = (BeforeAnalysisAccessImpl) a;
        if (!Options.AnalysisRegisterServices.getValue(access.getBigBang().getOptions())) {
            return;
        }
        serviceProviders = ResourceUtils.lookupServiceProvidersFromModule(access.getApplicationModulePath());
    }

    @SuppressWarnings("try")
    @Override
    public void duringAnalysis(DuringAnalysisAccess a) {
        DuringAnalysisAccessImpl access = (DuringAnalysisAccessImpl) a;
        if (!Options.AnalysisRegisterServices.getValue(access.getBigBang().getOptions())) {
            return;
        }
        access.getUniverse().getTypes().forEach(type -> handleType(type, access));
    }

    @SuppressWarnings("try")
    private boolean handleType(AnalysisType type, DuringAnalysisAccessImpl access) {
        if (!type.isAbstract() || !type.isReachable() || type.isArray() || processedTypes.putIfAbsent(type, Boolean.TRUE) != null) {
            return false;
        }

        String serviceClassName = type.toClassName();
        String serviceResourceLocation = ResourceUtils.getServiceResourceLocation(serviceClassName);

        Set<String> implementationClassNames = new TreeSet<>();

        /*
         * We do not know if the type is actually a service interface. The easiest way to find that
         * out is to look up the resources that ServiceLoader would access. If no resources exist,
         * then it is not a service interface.
         */
        Enumeration<URL> resourceURLs;
        try {
            resourceURLs = access.getApplicationClassLoader().getResources(serviceResourceLocation);
        } catch (IOException ex) {
            throw AnalysisError.interruptAnalysis("Error loading service implementation resources for service " + serviceClassName, ex);
        }
        while (resourceURLs.hasMoreElements()) {
            URL resourceURL = resourceURLs.nextElement();
            try {
                implementationClassNames.addAll(ResourceUtils.parseServiceResource(resourceURL));
            } catch (IOException ex) {
                throw AnalysisError.interruptAnalysis("Error loading service implementations for service " +
                                serviceClassName + " from URL " + resourceURL, ex);
            }
        }

        List<String> providers = serviceProviders.get(serviceClassName);
        if (providers != null) {
            implementationClassNames.addAll(providers);
        }

        if (implementationClassNames.size() == 0) {
            return false;
        }

        for (String implementationClassName : implementationClassNames) {
            if (implementationClassName.startsWith("org.graalvm.compiler") && implementationClassName.contains("hotspot")) {
                continue;
            }

            Class<?> implementationClass = access.findClassByName(implementationClassName);
            if (implementationClass == null) {
                continue;
            }
            try {
                access.getMetaAccess().lookupJavaType(implementationClass);
            } catch (UnsupportedFeatureException ex) {
                continue;
            }
            access.registerAsInHeap(implementationClass);
            try {
                Constructor<?> nullaryConstructor = implementationClass.getDeclaredConstructor();
                if (nullaryConstructor != null) {
                    access.registerAsInvoked(nullaryConstructor, true);
                }
            } catch (ReflectiveOperationException | NoClassDefFoundError ex) {
                continue;
            }
        }

        /* Ensure that the static analysis runs again for the new implementation classes. */
        access.requireAnalysisIteration();
        return true;
    }
}
