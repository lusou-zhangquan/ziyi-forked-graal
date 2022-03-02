/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2021, 2021, Alibaba Group Holding Limited. All rights reserved.
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

package com.oracle.graal.pointsto.standalone;

import com.oracle.graal.pointsto.ObjectScanner;
import com.oracle.graal.pointsto.PointsToAnalysis;
import com.oracle.graal.pointsto.api.HostVM;
import com.oracle.graal.pointsto.constraints.UnsupportedFeatures;
import com.oracle.graal.pointsto.meta.AnalysisUniverse;
import com.oracle.graal.pointsto.meta.HostedProviders;
import com.oracle.graal.pointsto.util.AnalysisError;
import com.oracle.svm.util.ReflectionUtil;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.serviceprovider.JavaVersionUtil;

import java.util.concurrent.ForkJoinPool;

public class StandalonePointsToAnalysis extends PointsToAnalysis {

    private final ClassLoader classLoader;

    public StandalonePointsToAnalysis(OptionValues options, AnalysisUniverse universe, HostedProviders providers, HostVM hostVM, ForkJoinPool executorService, Runnable heartbeatCallback) {
        super(options, universe, providers, hostVM, executorService, heartbeatCallback, new UnsupportedFeatures(), true);
        classLoader = ((HotSpotHost) hostVM).getClassLoader();
    }

    @Override
    public void cleanupAfterAnalysis() {
        super.cleanupAfterAnalysis();
        universe.getMethods().forEach(m -> {
            m.setAnalyzedGraph(null);
        });
        universe.getMethods().clear();
        universe.getFields().clear();
    }

    @Override
    protected ObjectScanner createObjectScanner(boolean isParallel) {
        ObjectScanner objectScanner = super.createObjectScanner(isParallel);
        objectScanner.setShouldScanField(field -> isClassLoaderAllowed(field.getDeclaringClass().getJavaClass().getClassLoader()));
        objectScanner.setShouldScanConstant(constant -> isClassLoaderAllowed(metaAccess.lookupJavaType(constant).getJavaClass().getClassLoader()));
        return objectScanner;
    }

    /**
     * We only allow scanning analysis target classes which are loaded by platformClassloader(e.g.
     * the JDK classes) or the classloader dedicated for analysis targets.
     */
    private boolean isClassLoaderAllowed(ClassLoader cl) {
        ClassLoader systemClassLoader = getSystemClassLoader();
        if (systemClassLoader == null) {
            return cl == null || this.classLoader.equals(cl);
        } else {
            return systemClassLoader.equals(cl) || this.classLoader.equals(cl);
        }
    }

    public static ClassLoader getSystemClassLoader() {
        if (JavaVersionUtil.JAVA_SPEC <= 8) {
            return null;
        } else {
            try {
                Class<?> classloadersClass = Class.forName("jdk.internal.loader.ClassLoaders");
                return (ClassLoader) ReflectionUtil.lookupField(classloadersClass, "PLATFORM_LOADER").get(null);
            } catch (ReflectiveOperationException e) {
                throw AnalysisError.shouldNotReachHere(e);
            }
        }
    }
}
