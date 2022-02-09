/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted.config;

import org.graalvm.nativeimage.impl.ConfigurationCondition;
import org.graalvm.nativeimage.impl.ReflectionRegistry;

import com.oracle.svm.configure.AbstractReflectionRegistryAdapter;
import com.oracle.svm.configure.ConditionalElement;
import com.oracle.svm.common.type.TypeResult;
import com.oracle.svm.core.hub.ClassLoadingExceptionSupport;
import com.oracle.svm.core.jdk.SealedClassSupport;
import com.oracle.svm.hosted.ImageClassLoader;

import jdk.vm.ci.meta.MetaUtil;

public class ReflectionRegistryAdapter extends AbstractReflectionRegistryAdapter {
    private final ImageClassLoader classLoader;

    public ReflectionRegistryAdapter(ReflectionRegistry registry, ImageClassLoader classLoader) {
        this.registry = registry;
        this.classLoader = classLoader;
    }

    @Override
    public TypeResult<ConfigurationCondition> resolveCondition(String typeName) {
        String canonicalizedName = canonicalizeTypeName(typeName);
        TypeResult<Class<?>> clazz = classLoader.findClass(canonicalizedName);
        return clazz.map(Class::getTypeName)
                        .map(ConfigurationCondition::create);
    }

    @Override
    public TypeResult<ConditionalElement<Class<?>>> resolveType(ConfigurationCondition condition, String typeName, boolean allowPrimitives) {
        String name = canonicalizeTypeName(typeName);
        TypeResult<Class<?>> clazz = classLoader.findClass(name, allowPrimitives);
        if (!clazz.isPresent()) {
            Throwable classLookupException = clazz.getException();
            if (classLookupException instanceof LinkageError || ClassLoadingExceptionSupport.Options.ExitOnUnknownClassLoadingFailure.getValue()) {
                registry.registerClassLookupException(condition, typeName, classLookupException);
            }
        }
        return clazz.map(c -> new ConditionalElement<>(condition, c));
    }

    private static String canonicalizeTypeName(String typeName) {
        String name = typeName;
        if (name.indexOf('[') != -1) {
            /* accept "int[][]", "java.lang.String[]" */
            name = MetaUtil.internalNameToJava(MetaUtil.toInternalName(name), true, true);
        }
        return name;
    }

    @Override
    public void registerPermittedSubclasses(ConditionalElement<Class<?>> type) {
        Class<?>[] classes = SealedClassSupport.singleton().getPermittedSubclasses(type.getElement());
        if (classes != null) {
            registry.register(type.getCondition(), classes);
        }
    }
}
