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

import com.oracle.graal.pointsto.util.AnalysisError;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class StandaloneAnalysisClassLoader extends URLClassLoader {
    private List<String> analysisClassPath;
    private List<String> analysisModulePath;

    public StandaloneAnalysisClassLoader(List<String> classPath, List<String> modulePath, ClassLoader parent) {
        super(pathToUrl(classPath, modulePath), parent);
        analysisClassPath = classPath;
        analysisModulePath = modulePath;
    }

    public List<String> getClassPath() {
        return analysisClassPath;
    }

    public List<String> getModulePath() {
        return analysisModulePath;
    }

    public Class<?> defineClassFromOtherClassLoader(Class<?> clazz) {
        String classFile = "/" + clazz.getName().replace('.', '/') + ".class";
        InputStream clazzStream = clazz.getResourceAsStream(classFile);
        Class<?> newlyDefinedClass = null;
        if (clazzStream != null) {
            try {
                byte[] classBytes = clazzStream.readAllBytes();
                newlyDefinedClass = defineClass(clazz.getName(), classBytes);
            } catch (IOException e) {
                AnalysisError.shouldNotReachHere(e);
            }
        }
        return newlyDefinedClass;
    }

    private static URL[] pathToUrl(List<String> classPath, List<String> modulePath) {
        List<URL> urls = new ArrayList<>();
        Stream.concat(classPath.stream(), modulePath.stream())
                        .forEach(cp -> {
                            try {
                                urls.add(new File(cp).toURI().toURL());
                            } catch (MalformedURLException e) {
                                e.printStackTrace();
                            }
                        });
        return urls.toArray(new URL[0]);
    }

    public Class<?> defineClass(String name, byte[] data) {
        return defineClass(name, data, 0, data.length);
    }
}
