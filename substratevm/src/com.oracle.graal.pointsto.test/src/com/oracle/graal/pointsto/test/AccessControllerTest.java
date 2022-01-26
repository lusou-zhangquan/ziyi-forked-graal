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

import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.AllPermission;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.security.ProtectionDomain;

public class AccessControllerTest {
    static class D {
        public void run() {
        }
    }

    static class C1 extends D {
    }

    static class C2 extends D {
    }

    static class C3 extends D {
    }

    static class C4 extends D {
    }

    static class C5 extends D {
    }

    static class C6 extends D {
    }

    static class C7 extends D {
    }

    static class C8 extends D {
    }

    static class C9 extends D {
    }

    static class C10 extends D {
    }

    @SuppressWarnings({"rawtypes", "unchecked", "deprecation"})
    public static void main(String[] args) throws PrivilegedActionException {
        AccessControlContext accessControlContext = new AccessControlContext(new ProtectionDomain[0]);
        AllPermission allPermission = new AllPermission();

        AccessController.doPrivileged(new PrivilegedAction() {
            @Override
            public Void run() {
                new C1().run();
                return null;
            }
        });

        AccessController.doPrivileged(new PrivilegedAction() {
            @Override
            public Void run() {
                new C2().run();
                return null;
            }
        }, accessControlContext);

        AccessController.doPrivileged(new PrivilegedAction() {
            @Override
            public Void run() {
                new C3().run();
                return null;
            }
        }, accessControlContext, allPermission);

        AccessController.doPrivileged(new PrivilegedExceptionAction() {
            @Override
            public Void run() {
                new C4().run();
                return null;
            }
        });

        AccessController.doPrivileged(new PrivilegedExceptionAction() {
            @Override
            public Void run() {
                new C5().run();
                return null;
            }
        }, accessControlContext);

        AccessController.doPrivileged(new PrivilegedExceptionAction() {
            @Override
            public Void run() {
                new C6().run();
                return null;
            }
        }, accessControlContext, allPermission);

        AccessController.doPrivilegedWithCombiner(new PrivilegedAction() {
            @Override
            public Void run() {
                new C7().run();
                return null;
            }
        });

        AccessController.doPrivilegedWithCombiner(new PrivilegedExceptionAction() {
            @Override
            public Void run() {
                new C8().run();
                return null;
            }
        });

        AccessController.doPrivilegedWithCombiner(new PrivilegedAction() {
            @Override
            public Void run() {
                new C9().run();
                return null;
            }
        }, accessControlContext, allPermission);

        AccessController.doPrivilegedWithCombiner(new PrivilegedExceptionAction() {
            @Override
            public Void run() {
                new C10().run();
                return null;
            }
        }, accessControlContext, allPermission);
    }

    @Test
    public void testPointstoAnalyzer() {
        PointstoAnalyzerTester tester = new PointstoAnalyzerTester();
        // Analysis target classes are in the same jar with current testing class.
        String testJar = this.getClass().getProtectionDomain().getCodeSource().getLocation().getPath();
        tester.setAnalysisArguments("-H:AnalysisEntryClass=com.oracle.graal.pointsto.test.AccessControllerTest",
                        "-H:AnalysisTargetAppCP=" + testJar);
        tester.setExpectedReachableTypes("com.oracle.graal.pointsto.test.AccessControllerTest$C1",
                        "com.oracle.graal.pointsto.test.AccessControllerTest$C2",
                        "com.oracle.graal.pointsto.test.AccessControllerTest$C3",
                        "com.oracle.graal.pointsto.test.AccessControllerTest$C4",
                        "com.oracle.graal.pointsto.test.AccessControllerTest$C5",
                        "com.oracle.graal.pointsto.test.AccessControllerTest$C6",
                        "com.oracle.graal.pointsto.test.AccessControllerTest$C7",
                        "com.oracle.graal.pointsto.test.AccessControllerTest$C8",
                        "com.oracle.graal.pointsto.test.AccessControllerTest$C9",
                        "com.oracle.graal.pointsto.test.AccessControllerTest$C10");
        tester.runAnalysisAndAssert();
    }
}
