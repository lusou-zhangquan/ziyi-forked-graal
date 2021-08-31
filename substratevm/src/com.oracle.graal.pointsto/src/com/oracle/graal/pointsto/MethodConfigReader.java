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

package com.oracle.graal.pointsto;

import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.util.AnalysisError;
import org.graalvm.compiler.debug.MethodFilter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * This class reads the configuration file that complies to the following rules:
 * <ol>
 *     <li>The configuration file is a plain text file.</li>
 *     <li>Each line represents one method.</li>
 *     <li>The method is described in the format defined by {@link MethodFilter}</li>
 * </ol>
 */
public class MethodConfigReader {

    /**
     * Read methods from the specified file. For each parsed method, execute the specified action.
     *
     * @param file                the configuration file to read.
     * @param bigbang
     * @param classLoader         analysis classloader
     * @param actionForEachMethod the action to take for each resolved method.
     */
    public static void readMethodFromFile(String file, BigBang bigbang, ClassLoader classLoader, Consumer<AnalysisMethod> actionForEachMethod) {
        List<String> methodNameList = new ArrayList<>();
        Path entryFilePath = Paths.get(file);
        File entryFile = entryFilePath.toFile();
        try (FileInputStream fis = new FileInputStream(entryFile);
             BufferedReader br = new BufferedReader(new InputStreamReader(fis));) {
            String line;
            while ((line = br.readLine()) != null) {
                methodNameList.add(line);
            }
        } catch (IOException e) {
            AnalysisError.shouldNotReachHere(e);
        }

        for (String method : methodNameList) {
            if (method.length() == 0) {
                continue;
            }
            workWithMethod(method, bigbang, classLoader, actionForEachMethod);
        }
    }

    private static void workWithMethod(String method, BigBang bigbang, ClassLoader classLoader, Consumer<AnalysisMethod> actionForEachMethod) {
        try {
            int pos = method.indexOf('(');
            int dotAfterClassNamePos;
            if (pos == -1) {
                dotAfterClassNamePos = method.lastIndexOf('.');
            } else {
                dotAfterClassNamePos = method.lastIndexOf('.', pos);
            }
            if (dotAfterClassNamePos == -1) {
                AnalysisError.shouldNotReachHere("The the given method's name " + method + " doesn't contain the declaring class name.");
            }
            String className = method.substring(0, dotAfterClassNamePos);
            Class<?> c = Class.forName(className, false, classLoader);
            AnalysisType t = bigbang.getMetaAccess().lookupJavaType(c);

            List<AnalysisMethod> methodCandidates = new ArrayList<>();
            for (AnalysisMethod m : t.getDeclaredMethods()) {
                methodCandidates.add(m);
            }
            methodCandidates.add(t.getClassInitializer());
            AnalysisMethod found = null;
            MethodFilter filter = MethodFilter.parse(method);
            for (AnalysisMethod methodCandidate : methodCandidates) {
                if (filter.matches(methodCandidate)) {
                    found = methodCandidate;
                    break;
                }
            }
            if (found != null) {
                actionForEachMethod.accept(found);
            } else {
                System.out.println("Warning: The method " + method + " doesn't exist. It shall not be added as analysis root method.");
            }
        } catch (ClassNotFoundException e) {
            AnalysisError.shouldNotReachHere(e);
        }
    }
}
