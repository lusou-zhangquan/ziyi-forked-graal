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

package com.oracle.graal.pointsto.test.jdk11;

import org.junit.Test;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import com.oracle.graal.pointsto.test.PointstoAnalyzerTester;
/**
 * This test verifies the standalone pointsto analysis can continue when MethodHandle argument could not be reduced to
 * at most a single call or single field access.
 */
public class MethodHandleSingleReduceErrorTest {
    private static final VarHandle STATUS;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            STATUS = l.findVarHandle(MethodHandleSingleReduceErrorTest.class, "status", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    private int status;

    public static void main(String[] args) {
        MethodHandleSingleReduceErrorTest t = new MethodHandleSingleReduceErrorTest();
        t.run();
    }

    public void run(){
        STATUS.compareAndSet(this, status, 1);
        doSomething();
    }

    private void doSomething() {
    }


    @Test
    public void test(){
        PointstoAnalyzerTester tester = new PointstoAnalyzerTester();
        //Analysis target classes are in the same jar with current testing class.
        String testJar = this.getClass().getProtectionDomain().getCodeSource().getLocation().getPath();
        tester.setAnalysisArguments("-H:AnalysisEntryClass=com.oracle.graal.pointsto.test.jdk11.MethodHandleSingleReduceErrorTest",
                "-H:AnalysisTargetAppCP=" + testJar);
        tester.setExpectedReachableMethods("com.oracle.graal.pointsto.test.jdk11.MethodHandleSingleReduceErrorTest.run()",
                "com.oracle.graal.pointsto.test.jdk11.MethodHandleSingleReduceErrorTest.doSomething()");
        tester.runAnalysisAndAssert();
    }
}
