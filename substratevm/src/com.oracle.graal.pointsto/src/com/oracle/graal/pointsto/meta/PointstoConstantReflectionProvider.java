/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2021, 2021, Alibaba Group Holding Limited. All rights reserved.
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

package com.oracle.graal.pointsto.meta;

import com.oracle.graal.pointsto.standalone.HotSpotHost;
import com.oracle.graal.pointsto.util.AnalysisError;
import jdk.vm.ci.hotspot.HotSpotConstantReflectionProvider;
import jdk.vm.ci.hotspot.HotSpotJVMCIRuntime;
import jdk.vm.ci.hotspot.HotSpotObjectConstant;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;
import org.graalvm.compiler.serviceprovider.GraalUnsafeAccess;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.Function;

public class PointstoConstantReflectionProvider extends HotSpotConstantReflectionProvider {
    private final AnalysisUniverse universe;

    public PointstoConstantReflectionProvider(AnalysisUniverse universe, HotSpotJVMCIRuntime runtime) {
        super(runtime);
        this.universe = universe;
    }

    @Override
    public final JavaConstant readFieldValue(ResolvedJavaField field, JavaConstant receiver) {
        AnalysisField analysisField = (AnalysisField) field;
        JavaConstant ret = universe.lookup(super.readFieldValue(analysisField.wrapped, universe.toHosted(receiver)));
        if (ret == null) {
            ret = readUninitializedStaticValue(analysisField, value -> universe.getSnippetReflection().forObject(value));
        }
        return ret;
    }

    /**
     * The correctness of this method is verified by {@link com.oracle.graal.pointsto.test.ClassEqualityTest}
     * @param type
     * @return
     */
    @Override
    public JavaConstant asJavaClass(ResolvedJavaType type) {
        AnalysisType t = (AnalysisType) type;
        t.registerAsReachable();
        return super.asJavaClass(t.getWrapped());
    }

    @Override
    public final Constant asObjectHub(ResolvedJavaType type) {
        /*
         * Substrate VM does not distinguish between the hub and the Class, they are both
         * represented by the DynamicHub.
         */
        return asJavaClass(type);
    }

    @Override
    public ResolvedJavaType asJavaType(Constant constant) {
        if (constant instanceof HotSpotObjectConstant) {
            HotSpotObjectConstant hotSpotObjectConstant = (HotSpotObjectConstant) constant;
            Object obj = hotSpotObjectConstant.asObject(hotSpotObjectConstant.getType());
            if (obj instanceof Class) {
                return getHostVM().lookupType((Class<?>) obj);
            }
        }
        return null;
    }

    private HotSpotHost getHostVM() {
        return (HotSpotHost) universe.hostVM();
    }

    /*
     * Static fields of classes that are initialized at run time have the default (uninitialized)
     * value in the image heap. But there is one important exception:
     *
     * Fields that are static final and either primitive or of type String are initialized using the
     * ConstantValue attribute of the class file, not using a class initializer. While we have class
     * initializers available at run time, we no longer have the class files. So we need to preserve
     * the values from the ConstantValue attribute in a different form. The easiest way is to just
     * have these values as the default value of the static field in the image heap.
     *
     * Unfortunately, JVMCI does not allow us to access the default value: since the class is still
     * uninitialized in the image generator, the JVMCI methods to read the field do not return a
     * value. But the Java HotSpot VM actually already has the fields initialized to the values
     * defined in the ConstantValue attributes. So reading the field via Unsafe actually produces
     * the correct value that we want.
     *
     * Another complication are classes that are re-initialized at run time, i.e., initialized both
     * during image generation and at run time. We must not return a value for a field that is
     * initialized by a class initializer (that could be an arbitrary and wrong value from the image
     * generator). Fortunately, the ConstantValue attribute is only used for static final fields of
     * primitive types or the String type. By limiting the Unsafe read to these narrow cases, it is
     * pretty likely (although not guaranteed) that we are not returning an unintended value for a
     * class that is re-initialized at run time.
     */
    public static JavaConstant readUninitializedStaticValue(AnalysisField field, Function<Object, JavaConstant> function) {
        JavaKind kind = field.getJavaKind();

        boolean canHaveConstantValueAttribute = kind.isPrimitive() || field.getType().getName().equals("Ljava/lang/String;");
        if (!canHaveConstantValueAttribute || !field.isFinal()) {
            return JavaConstant.defaultForKind(kind);
        }

        assert Modifier.isStatic(field.getModifiers());

        /* On HotSpot the base of a static field is the Class object. */
        Object base = field.getDeclaringClass().getJavaClass();
        long offset = field.wrapped.getOffset();

        /*
         * We cannot rely on the reflectionField because it can be null if there is some incomplete
         * classpath issue or the field is either missing or hidden from reflection. However we can
         * still use it to double check our assumptions.
         */
        Field reflectionField = field.getJavaField();
        if (reflectionField != null) {
            assert kind == JavaKind.fromJavaClass(reflectionField.getType());

            Object reflectionFieldBase = GraalUnsafeAccess.getUnsafe().staticFieldBase(reflectionField);
            long reflectionFieldOffset = GraalUnsafeAccess.getUnsafe().staticFieldOffset(reflectionField);

            AnalysisError.guarantee(reflectionFieldBase == base && reflectionFieldOffset == offset);
        }

        switch (kind) {
            case Boolean:
                return JavaConstant.forBoolean(GraalUnsafeAccess.getUnsafe().getBoolean(base, offset));
            case Byte:
                return JavaConstant.forByte(GraalUnsafeAccess.getUnsafe().getByte(base, offset));
            case Char:
                return JavaConstant.forChar(GraalUnsafeAccess.getUnsafe().getChar(base, offset));
            case Short:
                return JavaConstant.forShort(GraalUnsafeAccess.getUnsafe().getShort(base, offset));
            case Int:
                return JavaConstant.forInt(GraalUnsafeAccess.getUnsafe().getInt(base, offset));
            case Long:
                return JavaConstant.forLong(GraalUnsafeAccess.getUnsafe().getLong(base, offset));
            case Float:
                return JavaConstant.forFloat(GraalUnsafeAccess.getUnsafe().getFloat(base, offset));
            case Double:
                return JavaConstant.forDouble(GraalUnsafeAccess.getUnsafe().getDouble(base, offset));
            case Object:
                Object value = GraalUnsafeAccess.getUnsafe().getObject(base, offset);
                assert value == null || value instanceof String : "String is currently the only specified object type for the ConstantValue class file attribute";
                return function.apply(value);
            default:
                throw AnalysisError.shouldNotReachHere();
        }
    }
}
