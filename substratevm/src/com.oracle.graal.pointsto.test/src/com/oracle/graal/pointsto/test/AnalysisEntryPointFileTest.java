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

package com.oracle.graal.pointsto.test;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertNotNull;

/**
 * This test verify reading analysis entry points from file via -H:AnalysisEntryPointFile.
 */
public class AnalysisEntryPointFileTest {
    static class C {
        static {
            doC();
        }

        private static void doC() {
        }
    }

    public static void foo() {
        doFoo();
    }

    private static void doFoo() {
    }

    public static void bar(String s) {
        doBar1();
    }

    private static void doBar1() {
    }

    public static void bar(String s, int i) {
        doBar2();
    }

    private static void doBar2() {
    }

    /*
    Although virtual method can be added in the entrypoints file, it may take no effects if there is no receiver's
    allocation.
     */
    public void foo1() {
        doFoo1();
    }

    private void doFoo1() {
    }


    @Test
    public void test() throws IOException {
        PointstoAnalyzerTester tester = new PointstoAnalyzerTester();
        Path outPutDirectory = tester.createTestTmpDir();
        Path entryFilePath = tester.saveFileFromResource("/resources/entrypoints", outPutDirectory.resolve("entrypoints").normalize());
        assertNotNull("Fail to create entrypoints file.", entryFilePath);
        try {
            //Analysis target classes are in the same jar with current testing class.
            String testJar = this.getClass().getProtectionDomain().getCodeSource().getLocation().getPath();
            tester.setAnalysisArguments("-H:AnalysisEntryPointFile=" + entryFilePath.toString(),
                    "-H:AnalysisTargetAppCP=" + testJar);
            tester.setExpectedReachableMethods("com.oracle.graal.pointsto.test.AnalysisEntryPointFileTest$C.doC()",
                    "com.oracle.graal.pointsto.test.AnalysisEntryPointFileTest.doFoo()",
                    "com.oracle.graal.pointsto.test.AnalysisEntryPointFileTest.doBar1()");
            tester.setExpectedUnreachableMethods("com.oracle.graal.pointsto.test.AnalysisEntryPointFileTest.doFoo1()",
                    "com.oracle.graal.pointsto.test.AnalysisEntryPointFileTest.doBar2()");
            tester.runAnalysisAndAssert();
        } finally {
            tester.deleteTestTmpDir();
        }
    }
}
