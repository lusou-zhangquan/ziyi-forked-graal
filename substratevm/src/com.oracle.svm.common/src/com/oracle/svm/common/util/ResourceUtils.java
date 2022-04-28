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

package com.oracle.svm.common.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.ServiceLoader;

public class ResourceUtils {
    /** Copy of private field {@code ServiceLoader.PREFIX}. */
    public static final String SERVICE_LOCATION_PREFIX = "META-INF/services/";

    public static String getServiceResourceLocation(String serviceClassName) {
        return SERVICE_LOCATION_PREFIX + serviceClassName;
    }

    /**
     * Parse a service configuration file. This code is inspired by the private implementation
     * methods of {@link ServiceLoader}.
     */
    public static Collection<String> parseServiceResource(URL resourceURL) throws IOException {
        Collection<String> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resourceURL.openStream(), "utf-8"))) {
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }

                int commentIndex = line.indexOf('#');
                if (commentIndex >= 0) {
                    line = line.substring(0, commentIndex);
                }
                line = line.trim();
                if (line.length() != 0) {
                    /*
                     * We do not need to do further sanity checks on the class name. If the name is
                     * illegal, then we will not be able to load the class and report an error.
                     */
                    result.add(line);
                }
            }
        }
        return result;
    }

    /**
     * @return locale for given tag or null for invalid ones
     */
    public static Locale parseLocaleFromTag(String tag) {
        try {
            return new Locale.Builder().setLanguageTag(tag).build();
        } catch (IllformedLocaleException ex) {
            /*- Custom made locales consisting of at most three parts separated by '-' are also supported */
            String[] parts = tag.split("-");
            switch (parts.length) {
                case 1:
                    return new Locale(parts[0]);
                case 2:
                    return new Locale(parts[0], parts[1]);
                case 3:
                    return new Locale(parts[0], parts[1], parts[2]);
                default:
                    return null;
            }
        }
    }
}
