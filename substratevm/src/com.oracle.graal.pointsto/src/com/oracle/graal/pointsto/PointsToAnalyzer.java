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

package com.oracle.graal.pointsto;

import com.oracle.graal.pointsto.api.PointstoOptions;
import com.oracle.graal.pointsto.heap.HeapSnapshotVerifier;
import com.oracle.graal.pointsto.heap.ImageHeap;
import com.oracle.graal.pointsto.heap.StandaloneImageHeapScanner;
import com.oracle.graal.pointsto.infrastructure.SubstitutionProcessor;
import com.oracle.graal.pointsto.meta.AnalysisMetaAccess;
import com.oracle.graal.pointsto.meta.AnalysisMetaAccessExtensionProvider;
import com.oracle.graal.pointsto.meta.AnalysisUniverse;
import com.oracle.graal.pointsto.meta.HostedProviders;
import com.oracle.graal.pointsto.meta.PointsToAnalysisFactory;
import com.oracle.graal.pointsto.meta.PointstoConstantFieldProvider;
import com.oracle.graal.pointsto.meta.PointstoConstantReflectionProvider;
import com.oracle.graal.pointsto.meta.PointstoStampProvider;
import com.oracle.graal.pointsto.phases.NoClassInitializationPlugin;
import com.oracle.graal.pointsto.standalone.HotSpotHost;
import com.oracle.graal.pointsto.standalone.StandalonePointsToAnalysis;
import com.oracle.graal.pointsto.standalone.features.AnalysisFeatureImpl;
import com.oracle.graal.pointsto.standalone.features.AnalysisFeatureManager;
import com.oracle.graal.pointsto.standalone.features.PointstoClassInitializationFeature;
import com.oracle.graal.pointsto.util.AnalysisError;
import com.oracle.graal.pointsto.util.PointsToOptionParser;
import com.oracle.graal.pointsto.util.Timer;
import com.oracle.graal.pointsto.util.TimerCollection;
import com.oracle.svm.util.ModuleSupport;
import com.oracle.svm.util.ReflectionUtil;
import jdk.vm.ci.amd64.AMD64Kind;
import jdk.vm.ci.hotspot.HotSpotJVMCIRuntime;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.runtime.JVMCI;
import org.graalvm.compiler.api.replacements.SnippetReflectionProvider;
import org.graalvm.compiler.api.runtime.GraalJVMCICompiler;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.Indent;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration;
import org.graalvm.compiler.nodes.graphbuilderconf.InvocationPlugins;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.phases.util.Providers;
import org.graalvm.compiler.printer.GraalDebugHandlersFactory;
import org.graalvm.compiler.runtime.RuntimeProvider;
import org.graalvm.compiler.serviceprovider.JavaVersionUtil;
import org.graalvm.compiler.word.WordTypes;
import org.graalvm.nativeimage.hosted.Feature;

import java.io.File;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;

public final class PointsToAnalyzer {

    static {
        ModuleSupport.exportAndOpenAllPackagesToUnnamed("jdk.internal.vm.ci", false);
        ModuleSupport.exportAndOpenAllPackagesToUnnamed("jdk.internal.vm.compiler", false);
        ModuleSupport.exportAndOpenAllPackagesToUnnamed("jdk.internal.vm.compiler.management", true);
        ModuleSupport.exportAndOpenPackageToUnnamed("java.base", "jdk.internal.loader", false);
        if (JavaVersionUtil.JAVA_SPEC >= 15) {
            ModuleSupport.exportAndOpenPackageToUnnamed("java.base", "jdk.internal.misc", false);
        }
        ModuleSupport.exportAndOpenPackageToUnnamed("java.base", "sun.text.spi", false);
        ModuleSupport.exportAndOpenPackageToUnnamed("java.base", "jdk.internal.org.objectweb.asm", false);
        if (JavaVersionUtil.JAVA_SPEC >= 16) {
            ModuleSupport.exportAndOpenPackageToUnnamed("java.base", "sun.reflect.annotation", false);
            ModuleSupport.exportAndOpenPackageToUnnamed("java.base", "sun.security.jca", false);
            ModuleSupport.exportAndOpenPackageToUnnamed("jdk.jdeps", "com.sun.tools.classfile", false);
        }
    }

    private final StandalonePointsToAnalysis bigbang;
    private AnalysisFeatureManager analysisFeatureManager;
    private ClassLoader analysisClassLoader;
    private final DebugContext debugContext;
    private AnalysisFeatureImpl.OnAnalysisExitAccessImpl onAnalysisExitAccess;
    private String analysisName;
    private boolean entrypointsAreSet;
    private boolean mainEntryIsSet;

    @SuppressWarnings("try")
    private PointsToAnalyzer(OptionValues options) {
        analysisFeatureManager = new AnalysisFeatureManager(options);
        String appCP = PointstoOptions.AnalysisTargetAppCP.getValue(options);
        if (appCP == null) {
            AnalysisError.shouldNotReachHere("Must specify analysis target application's classpath with -H:" + PointstoOptions.AnalysisTargetAppCP.getName());
        }
        List<URL> urls = new ArrayList<>();
        for (String cp : appCP.split(File.pathSeparator)) {
            try {
                File file = new File(cp);
                if (file.exists()) {
                    urls.add(file.toURI().toURL());
                }
            } catch (MalformedURLException e) {
                e.printStackTrace();
            }
        }
        analysisClassLoader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
        Providers providers = getGraalCapability(RuntimeProvider.class).getHostBackend().getProviders();
        SnippetReflectionProvider snippetReflection = getGraalCapability(SnippetReflectionProvider.class);
        MetaAccessProvider originalMetaAccess = providers.getMetaAccess();
        debugContext = new DebugContext.Builder(options, new GraalDebugHandlersFactory(snippetReflection)).build();
        ForkJoinPool executor = PointsToAnalysis.createExecutor(debugContext, Math.min(Runtime.getRuntime().availableProcessors(), 32));
        HotSpotHost hotSpotHost = new HotSpotHost(options, analysisClassLoader);
        int wordSize = getWordSize();
        AnalysisPolicy analysisPolicy = PointstoOptions.AllocationSiteSensitiveHeap.getValue(options) ? new BytecodeSensitiveAnalysisPolicy(options)
                        : new DefaultAnalysisPolicy(options);

        JavaKind wordKind = JavaKind.fromWordSize(wordSize);
        AnalysisUniverse aUniverse = new AnalysisUniverse(hotSpotHost, wordKind,
                        analysisPolicy, SubstitutionProcessor.IDENTITY, originalMetaAccess, snippetReflection, snippetReflection, new PointsToAnalysisFactory());
        AnalysisMetaAccess aMetaAccess = new AnalysisMetaAccess(aUniverse, originalMetaAccess);
        aMetaAccess.lookupJavaType(String.class).registerAsReachable();
        PointstoConstantReflectionProvider aConstantReflection = new PointstoConstantReflectionProvider(aUniverse, HotSpotJVMCIRuntime.runtime());
        PointstoConstantFieldProvider aConstantFieldProvider = new PointstoConstantFieldProvider(aMetaAccess);
        WordTypes aWordTypes = new WordTypes(aMetaAccess, wordKind);
        PointstoStampProvider aStampProvider = new PointstoStampProvider(aMetaAccess);
        AnalysisMetaAccessExtensionProvider aMetaAccessExtensionProvider = new AnalysisMetaAccessExtensionProvider();
        HostedProviders aProviders = new HostedProviders(aMetaAccess, null, aConstantReflection, aConstantFieldProvider,
                        providers.getForeignCalls(), providers.getLowerer(), providers.getReplacements(), aStampProvider, snippetReflection, aWordTypes,
                        providers.getPlatformConfigurationProvider(), aMetaAccessExtensionProvider, providers.getLoopsDataProvider());
        analysisName = getAnalysisName(options);
        bigbang = new StandalonePointsToAnalysis(options, aUniverse, aProviders, hotSpotHost, executor, () -> {
            /* do nothing */
        }, new TimerCollection(analysisName));
        aUniverse.setBigBang(bigbang);
        ImageHeap heap = new ImageHeap();
        StandaloneImageHeapScanner heapScanner = new StandaloneImageHeapScanner(heap, aMetaAccess,
                        snippetReflection, aConstantReflection, new AnalysisObjectScanningObserver(bigbang), analysisClassLoader);
        aUniverse.setHeapScanner(heapScanner);
        HeapSnapshotVerifier heapVerifier = new HeapSnapshotVerifier(bigbang, heap, heapScanner);
        aUniverse.setHeapVerifier(heapVerifier);
        /* Register already created types as assignable. */
        aUniverse.getTypes().forEach(t -> {
            t.registerAsAssignable(bigbang);
            if (t.isReachable()) {
                bigbang.onTypeInitialized(t);
            }
        });
        /*
         * System classes and fields are necessary to tell the static analysis that certain things
         * really "exist". The most common reason for that is that there are no instances and
         * allocations of these classes seen during the static analysis. The heap chunks are one
         * good example.
         */
        try (Indent ignored = debugContext.logAndIndent("add initial classes/fields/methods")) {
            bigbang.addRootClass(Object.class, false, false).registerAsInHeap();
            bigbang.addRootClass(String.class, false, false).registerAsInHeap();
            bigbang.addRootClass(String[].class, false, false).registerAsInHeap();
            bigbang.addRootField(String.class, "value").registerAsInHeap();
            bigbang.addRootClass(long[].class, false, false).registerAsInHeap();
            bigbang.addRootClass(byte[].class, false, false).registerAsInHeap();
            bigbang.addRootClass(byte[][].class, false, false).registerAsInHeap();
            bigbang.addRootClass(Object[].class, false, false).registerAsInHeap();

            bigbang.addRootMethod(ReflectionUtil.lookupMethod(Object.class, "getClass"), true);

            for (JavaKind kind : JavaKind.values()) {
                if (kind.isPrimitive() && kind != JavaKind.Void) {
                    bigbang.addRootClass(kind.toJavaClass(), false, true);
                }
            }
            bigbang.getMetaAccess().lookupJavaType(JavaKind.Void.toJavaClass()).registerAsReachable();

            GraphBuilderConfiguration.Plugins plugins = new GraphBuilderConfiguration.Plugins(new InvocationPlugins());
            NoClassInitializationPlugin classInitializationPlugin = new NoClassInitializationPlugin();
            plugins.setClassInitializationPlugin(classInitializationPlugin);
            aProviders.setGraphBuilderPlugins(plugins);
        }
    }

    private String getAnalysisName(OptionValues options) {
        String entryClass = PointstoOptions.AnalysisEntryClass.getValue(options);
        String analysisEntryClassOptionName = PointstoOptions.AnalysisEntryClass.getName();
        String entryPointsFile = PointstoOptions.AnalysisEntryPointFile.getValue(options);
        String entryPointsFileOptionName = PointstoOptions.AnalysisEntryPointFile.getName();
        mainEntryIsSet = entryClass != null && entryClass.length() != 0;
        entrypointsAreSet = entryPointsFile != null && entryPointsFile.length() != 0;
        if (!mainEntryIsSet && !entrypointsAreSet) {
            AnalysisError.shouldNotReachHere(
                            "No analysis entry are specified. Must use -H:" + analysisEntryClassOptionName + " or -H:" + entryPointsFileOptionName + " to specify the analysis entries.");
        }
        if (mainEntryIsSet) {
            return entryClass;
        } else {
            Path entryFilePath = Paths.get(entryPointsFile);
            return entryFilePath.getFileName().toString();
        }
    }

    private static int getWordSize() {
        int wordSize;
        String archModel = System.getProperty("sun.arch.data.model");
        switch (archModel) {
            case "64":
                wordSize = AMD64Kind.QWORD.getSizeInBytes();
                break;
            case "32":
                wordSize = AMD64Kind.DWORD.getSizeInBytes();
                break;
            default:
                throw new RuntimeException("Property sun.arch.data.model should only be 64 or 32, but is " + archModel);

        }
        return wordSize;
    }

    private static <T> T getGraalCapability(Class<T> clazz) {
        GraalJVMCICompiler compiler = (GraalJVMCICompiler) JVMCI.getRuntime().getCompiler();
        return compiler.getGraalRuntime().getCapability(clazz);
    }

    public static PointsToAnalyzer createAnalyzer(OptionValues options) {
        return new PointsToAnalyzer(options);
    }

    public static PointsToAnalyzer createAnalyzer(String[] args) {
        OptionValues options = PointsToOptionParser.getInstance().parse(args);
        return PointsToAnalyzer.createAnalyzer(options);
    }

    @SuppressWarnings("try")
    public int run() {
        registerEntryMethods();
        registerFeatures();
        int exitCode = 0;
        Feature.BeforeAnalysisAccess beforeAnalysisAccess = new AnalysisFeatureImpl.BeforeAnalysisAccessImpl(analysisFeatureManager, analysisClassLoader, bigbang, debugContext);
        analysisFeatureManager.forEachFeature(feature -> feature.beforeAnalysis(beforeAnalysisAccess));
        try (Timer.StopTimer t = bigbang.analysisTimer.start()) {
            AnalysisFeatureImpl.DuringAnalysisAccessImpl config = new AnalysisFeatureImpl.DuringAnalysisAccessImpl(analysisFeatureManager, analysisClassLoader, bigbang, debugContext);
            bigbang.runAnalysis(debugContext, (analysisUniverse) -> {
                bigbang.getHostVM().notifyClassReachabilityListener(analysisUniverse, config);
                analysisFeatureManager.forEachFeature(feature -> feature.duringAnalysis(config));
                return !config.getAndResetRequireAnalysisIteration();
            });
        } catch (Throwable e) {
            reportException(e);
            exitCode = 1;
        }
        onAnalysisExitAccess = new AnalysisFeatureImpl.OnAnalysisExitAccessImpl(analysisFeatureManager, analysisClassLoader, bigbang, debugContext);
        analysisFeatureManager.forEachFeature(feature -> feature.onAnalysisExit(onAnalysisExitAccess));
        bigbang.getUnsupportedFeatures().report(bigbang);
        return exitCode;
    }

    /**
     * Clean up all analysis data. This method is called by user, not by the analysis framework,
     * because user may still use the analysis results after the pointsto analysis.
     */
    public void cleanUp() {
        bigbang.cleanupAfterAnalysis();
    }

    public AnalysisUniverse getResultUniverse() {
        return bigbang.getUniverse();
    }

    public Object getResultFromFeature(Class<? extends Feature> feature) {
        return onAnalysisExitAccess.getResult(feature);
    }

    @SuppressWarnings("unchecked")
    private void registerFeatures() {
        analysisFeatureManager.registerFeaturesFromOptions();
        analysisFeatureManager.registerFeature(PointstoClassInitializationFeature.class);
        // Register DashboardDump feature by default, user can enable the feature by setting
        // -H:+DumpAnalysisReports
        analysisFeatureManager.registerFeature("com.oracle.graal.pointsto.standalone.features.DashboardDumpDelegate$Feature");
    }

    /**
     * Register analysis entry points.
     */
    public void registerEntryMethods() {
        OptionValues options = bigbang.getOptions();
        if (mainEntryIsSet) {
            String entryClass = PointstoOptions.AnalysisEntryClass.getValue(options);
            String optionName = PointstoOptions.AnalysisEntryClass.getName();
            try {
                Class<?> analysisMainClass = Class.forName(entryClass, false, analysisClassLoader);
                Method main = analysisMainClass.getDeclaredMethod("main", String[].class);
                // main method is static, whatever the invokeSpecial is it is ignored.
                bigbang.addRootMethod(main, true);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("Can't find the specified analysis main class " + entryClass, e);
            } catch (NoSuchMethodException e) {
                AnalysisError.shouldNotReachHere("Can't find the main method in the analysis main class " + entryClass + " setting with -H:" + optionName, e);
            }
        }

        if (entrypointsAreSet) {
            String entryPointsFile = PointstoOptions.AnalysisEntryPointFile.getValue(options);
            MethodConfigReader.readMethodFromFile(entryPointsFile, bigbang, analysisClassLoader, m-> bigbang.addRootMethod(m, true));
        }
    }

    /**
     * Need --add-exports=java.base/jdk.internal.module=ALL-UNNAMED in command line.
     *
     * @param args options to run the analyzing
     */
    public static void main(String[] args) {
        PointsToAnalyzer analyzer = createAnalyzer(args);
        analyzer.run();
    }

    protected static void reportException(Throwable e) {
        System.err.print("Exception:");
        e.printStackTrace();
    }
}
