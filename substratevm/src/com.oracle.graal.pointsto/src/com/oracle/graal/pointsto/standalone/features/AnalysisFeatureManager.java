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

import com.oracle.graal.pointsto.standalone.AnalysisSingletonsSupportImpl;
import com.oracle.graal.pointsto.util.AnalysisError;
import com.oracle.svm.util.ReflectionUtil;
import org.graalvm.collections.EconomicMap;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.options.OptionKey;
import org.graalvm.compiler.options.OptionType;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.nativeimage.hosted.Feature;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public class AnalysisFeatureManager {

    public static class Options {
        @Option(help = "Comma separated features used in standalone analysis.", type = OptionType.User)//
        public static final OptionKey<String> AnalysisTimeFeatures = new OptionKey<>(null);
    }

    private final List<Feature> features = new ArrayList<>();
    private final Set<String> featureClassNames = new HashSet<>();
    private final Set<Class<? extends Feature>> featureClasses = new HashSet<>();
    private boolean inited = false;
    private OptionValues options;

    public AnalysisFeatureManager(OptionValues options) {
        this.options = options;
    }

    public void registerFeaturesFromOptions() {
        String featureNames = Options.AnalysisTimeFeatures.getValue(options);
        if (featureNames != null) {
            for (String featureName : featureNames.split(",")) {
                registerFeature(featureName);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public void registerFeature(String featureClassName) {
        if (!featureClassNames.contains(featureClassName)) {
            featureClassNames.add(featureClassName);
        } else {
            return;
        }
        Class<? extends Feature> featureClass = null;
        try {
            featureClass = (Class<? extends Feature>) Class.forName(featureClassName);
        } catch (ClassNotFoundException e) {
            AnalysisError.shouldNotReachHere("Feature class " + featureClassName + " is not found in current classpath.");
        }
        registerFeature(featureClass);
    }

    public void registerFeature(Class<? extends Feature> feature) {
        if (!featureClasses.contains(feature)) {
            featureClasses.add(feature);
        }
    }

    public void forEachFeature(Consumer<Feature> consumer) {
        if (!inited) {
            initAllFeatures();
            inited = true;
        }
        for (Feature feature : features) {
            consumer.accept(feature);
        }
    }

    private void initAllFeatures() {
        // Set options required by original SVM features if there is any
        reflectiveUpdateHostedOptions();

        // Now can initialize all features
        for (Class<? extends Feature> featureClass : featureClasses) {
            Feature feature;
            try {
                Constructor<? extends Feature> constructor;
                if (DelegateFeature.class.isAssignableFrom(featureClass)) {
                    constructor = ReflectionUtil.lookupConstructor(featureClass, OptionValues.class);
                    feature = constructor.newInstance(options);
                } else {
                    constructor = ReflectionUtil.lookupConstructor(featureClass);
                    feature = constructor.newInstance();
                }
                features.add(feature);
            } catch (ReflectiveOperationException e) {
                if (e.getCause() instanceof FeatureNotEnabledException) {
                    // Feature is not enabled, ignore it and continue for the next one.
                } else {
                    AnalysisError.shouldNotReachHere(e);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void reflectiveUpdateHostedOptions() {
        List<HostedOptionValueUpdater.HostedOptionValue> hostedOptionUpdates = HostedOptionValueUpdater.getHostedOptionUpdates();
        if (!hostedOptionUpdates.isEmpty()) {
            EconomicMap<OptionKey<?>, Object> hostedValues = EconomicMap.create(hostedOptionUpdates.size());
            for (HostedOptionValueUpdater.HostedOptionValue hostedOption : hostedOptionUpdates) {
                hostedOption.update(hostedValues);
            }
            try {
                Class<OptionValues> hostedOptionClass = (Class<OptionValues>) Class.forName(HostedOptionValueUpdater.HOSTED_OPTION_VALUES_CLASS);
                OptionValues hostedOptionInstance = hostedOptionClass.getDeclaredConstructor(EconomicMap.class).newInstance(hostedValues);
                AnalysisSingletonsSupportImpl.get().add(hostedOptionClass, hostedOptionInstance);
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                AnalysisError.dependencyNotExist("class HostedOptionValues ", "svm.jar", e);
            } catch (Exception e) {
                AnalysisError.shouldNotReachHere(e);
            }
        }
    }
}
