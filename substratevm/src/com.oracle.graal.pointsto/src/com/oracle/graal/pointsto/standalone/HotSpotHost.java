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

package com.oracle.graal.pointsto.standalone;
import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.api.SharedHostVM;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.meta.HostedProviders;
import com.oracle.graal.pointsto.plugins.PointsToGraphBuilderPhase;
import com.oracle.graal.pointsto.util.AnalysisError;
import org.graalvm.compiler.java.GraphBuilderPhase;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration;
import org.graalvm.compiler.nodes.graphbuilderconf.IntrinsicContext;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.phases.OptimisticOptimizations;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BiConsumer;

public class HotSpotHost extends SharedHostVM {
    private final List<BiConsumer<AnalysisMethod, StructuredGraph>> methodAfterParsingHooks = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<AnalysisType, Class<?>> typeToClass = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Class<?>, AnalysisType> classToType = new ConcurrentHashMap<>();

    public HotSpotHost(OptionValues options, ClassLoader classLoader, ForkJoinPool executor) {
        super(options, classLoader,executor);
    }

    @Override
    public void registerType(AnalysisType analysisType) {
        Class<?> clazz = analysisType.getJavaClass();
        Object existing = typeToClass.put(analysisType, clazz);
        assert existing == null;
        existing = classToType.put(clazz, analysisType);
        assert existing == null;
    }

    public AnalysisType lookupType(Class<?> clazz) {
        assert clazz != null : "Class must not be null";
        return classToType.get(clazz);
    }

    @Override
    public void methodAfterParsingHook(BigBang bb, AnalysisMethod method, StructuredGraph graph) {
        if (graph != null) {
            graph.setGuardsStage(StructuredGraph.GuardsStage.FIXED_DEOPTS);

            for (BiConsumer<AnalysisMethod, StructuredGraph> methodAfterParsingHook : methodAfterParsingHooks) {
                methodAfterParsingHook.accept(method, graph);
            }
        }
    }

    @Override
    public boolean isInitialized(AnalysisType type) {
        return type.getWrapped().isInitialized();
    }

    @Override
    public void initializeType(AnalysisType type) {
        if (!type.isReachable()) {
            AnalysisError.shouldNotReachHere("Registering and initializing a type that was not yet marked as reachable: " + type);
        }
        //There is no eager class initialization nor delayed class initialization in standalone analysis, so we don't need
        //do any actual class initialization work here.
    }

    @Override
    public GraphBuilderPhase.Instance createGraphBuilderPhase(HostedProviders providers, GraphBuilderConfiguration graphBuilderConfig, OptimisticOptimizations optimisticOpts,
                                                              IntrinsicContext initialIntrinsicContext) {
        return new PointsToGraphBuilderPhase(providers, graphBuilderConfig, optimisticOpts, initialIntrinsicContext);
    }
}
