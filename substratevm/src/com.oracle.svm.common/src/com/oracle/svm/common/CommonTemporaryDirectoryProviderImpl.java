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

package com.oracle.svm.common;

import com.oracle.svm.common.option.CommonOptions;
import org.graalvm.compiler.options.OptionValues;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

public abstract class CommonTemporaryDirectoryProviderImpl implements TemporaryBuildDirectoryProvider, AutoCloseable {
    private Path tempDirectory;
    private boolean deleteTempDirectory;

    public abstract OptionValues optionValues();

    public abstract String getTempDirPrefix();

    public abstract RuntimeException throwException(Exception cause);

    @Override
    public synchronized Path getTemporaryBuildDirectory() {
        if (tempDirectory == null) {
            try {
                String tempName = CommonOptions.TempDirectory.getValue(optionValues());
                if (tempName == null || tempName.isEmpty()) {
                    tempDirectory = Files.createTempDirectory(getTempDirPrefix());
                    deleteTempDirectory = true;
                } else {
                    tempDirectory = FileSystems.getDefault().getPath(tempName).resolve("SVM-" + System.currentTimeMillis());
                    assert !Files.exists(tempDirectory);
                    Files.createDirectories(tempDirectory);
                }
            } catch (IOException ex) {
                throw throwException(ex);
            }
        }
        return tempDirectory.toAbsolutePath();
    }

    @Override
    public void close() {
        if (deleteTempDirectory) {
            deleteAll(getTemporaryBuildDirectory());
        }
    }

    private void deleteAll(Path path) {
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
            throw throwException(ex);
        }
    }
}
