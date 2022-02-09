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

package com.oracle.graal.pointsto.standalone.reflect;

import com.oracle.svm.configure.AbstractReflectionRegistryAdapter;
import com.oracle.svm.configure.ConditionalElement;
import com.oracle.svm.common.type.TypeResult;
import com.oracle.svm.common.util.ClassUtils;
import jdk.vm.ci.meta.MetaUtil;
import org.graalvm.nativeimage.impl.ConfigurationCondition;
import org.graalvm.nativeimage.impl.ReflectionRegistry;

public class StandaloneReflectionRegistryAdapter extends AbstractReflectionRegistryAdapter {
    private final ClassLoader classLoader;

    public StandaloneReflectionRegistryAdapter(ReflectionRegistry reflectionRegistry, ClassLoader loader) {
        this.registry = reflectionRegistry;
        this.classLoader = loader;
    }

    @Override
    public TypeResult<ConfigurationCondition> resolveCondition(String typeName) {
        TypeResult<Class<?>> clazz;
        try {
            clazz = forName(typeName, true);
        } catch (ClassNotFoundException ex) {
            clazz = TypeResult.forException(typeName, ex);
        }
        return clazz.map(Class::getTypeName).map(ConfigurationCondition::create);
    }

    @Override
    public TypeResult<ConditionalElement<Class<?>>> resolveType(ConfigurationCondition condition, String typeName, boolean allowPrimitives) {
        TypeResult<Class<?>> clazz;
        try {
            clazz = forName(typeName, allowPrimitives);
        } catch (ClassNotFoundException ex) {
            clazz = TypeResult.forException(typeName, ex);
        }
        return clazz.map(c -> new ConditionalElement<>(condition, c));
    }

    private TypeResult<Class<?>> forName(String name, boolean allowPrimitives) throws ClassNotFoundException {
        if (allowPrimitives && name.indexOf('.') == -1) {
            TypeResult<Class<?>> primitiveType = ClassUtils.getPrimitiveTypeByName(name);
            if (primitiveType != null) {
                return primitiveType;
            }
        }
        String className = name;
        if (name.indexOf('[') != -1) {
            /* accept "int[][]", "java.lang.String[]" */
            className = MetaUtil.internalNameToJava(MetaUtil.toInternalName(name), true, true);
        }
        return TypeResult.forClass(Class.forName(className, false, classLoader));
    }

    @Override
    public void registerPermittedSubclasses(ConditionalElement<Class<?>> type) {
    }
}
