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

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

public class AnalysisClassLoader extends URLClassLoader {
    private Path tempDirectory;

    public AnalysisClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    public Class<?> defineClassFromOtherClassLoader(Class<?> clazz) {
        String classFile = "/" + clazz.getName().replace('.', '/') + ".class";
        InputStream clazzStream = clazz.getResourceAsStream(classFile);
        if (clazzStream == null) {
            return null;
        }
        Path directory = getTemporaryBuildDirectory();
        Path outputClassPath = directory.resolve(classFile.substring(1)).normalize();
        try {
            Files.createDirectories(outputClassPath.getParent());

        } catch (IOException e) {
            AnalysisError.shouldNotReachHere(e);
        }
        try (FileOutputStream outputStream = new FileOutputStream(outputClassPath.toFile());) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int numOfRead;
            while ((numOfRead = clazzStream.read(buffer)) != -1) {
                baos.write(buffer, 0, numOfRead);
            }
            outputStream.write(baos.toByteArray());
        } catch (IOException e) {
            AnalysisError.shouldNotReachHere(e);
        }
        try {
            addURL(directory.toFile().toURI().toURL());
        } catch (MalformedURLException e) {
            return null;
        }
        try {
            return loadClass(clazz.getName());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    public void deleteTmpClasses() {
        deleteAll(getTemporaryBuildDirectory());
    }

    private synchronized Path getTemporaryBuildDirectory() {
        if (tempDirectory == null) {
            try {
                tempDirectory = Files.createTempDirectory("Pointsto-");
            } catch (IOException ex) {
                throw AnalysisError.shouldNotReachHere(ex);
            }
        }
        return tempDirectory.toAbsolutePath();
    }

    private static void deleteAll(Path path) {
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            throw AnalysisError.shouldNotReachHere(ex);
        }
    }
}
