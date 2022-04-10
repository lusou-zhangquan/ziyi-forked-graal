package com.oracle.graal.pointsto.test;

import java.nio.file.Files;

import org.junit.Test;

public class PointstoServiceProviderTest {
    public static void main(String[] args) {
        Files.exists(null);
    }

    @Test
    public void testDisableServiceLoader() {
        PointstoAnalyzerTester tester = new PointstoAnalyzerTester();
        String testJar = this.getClass().getProtectionDomain().getCodeSource().getLocation().getPath();
        tester.setAnalysisArguments("-H:AnalysisEntryClass=com.oracle.graal.pointsto.test.PointstoServiceProviderTest",
                        "-H:-AnalysisRegisterServices",
                        "-H:AnalysisTargetAppCP=" + testJar);
        tester.setExpectedReachableTypes("java.nio.file.Files",
                        "java.nio.file.spi.FileSystemProvider");
        tester.setExpectedUnreachableTypes("jdk.internal.jrtfs.JrtFileSystemProvider");
        tester.runAnalysisAndAssert();
    }

    @Test
    public void testEnableServiceLoader() {
        PointstoAnalyzerTester tester = new PointstoAnalyzerTester();
        String testJar = this.getClass().getProtectionDomain().getCodeSource().getLocation().getPath();
        tester.setAnalysisArguments("-H:AnalysisEntryClass=com.oracle.graal.pointsto.test.PointstoServiceProviderTest",
                        "-H:AnalysisTargetAppCP=" + testJar);
        tester.setExpectedReachableTypes("java.nio.file.Files",
                        "java.nio.file.spi.FileSystemProvider",
                        "jdk.internal.jrtfs.JrtFileSystemProvider");
        tester.runAnalysisAndAssert();
    }
}
