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

package com.oracle.graal.pointsto.phases;

import com.oracle.graal.pointsto.constraints.UnsupportedFeatureException;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.meta.AnalysisUniverse;
import com.oracle.svm.util.ReflectionUtil;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import org.graalvm.collections.Pair;
import org.graalvm.collections.UnmodifiableEconomicMap;
import org.graalvm.compiler.core.common.type.ObjectStamp;
import org.graalvm.compiler.core.common.type.PrimitiveStamp;
import org.graalvm.compiler.core.common.type.Stamp;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.core.common.type.StampPair;
import org.graalvm.compiler.core.common.type.TypeReference;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.graph.NodeMap;
import org.graalvm.compiler.java.BytecodeParser;
import org.graalvm.compiler.java.GraphBuilderPhase;
import org.graalvm.compiler.nodes.AbstractBeginNode;
import org.graalvm.compiler.nodes.ArithmeticOperation;
import org.graalvm.compiler.nodes.BeginNode;
import org.graalvm.compiler.nodes.CallTargetNode;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.FixedGuardNode;
import org.graalvm.compiler.nodes.FixedNode;
import org.graalvm.compiler.nodes.FixedWithNextNode;
import org.graalvm.compiler.nodes.InvokeNode;
import org.graalvm.compiler.nodes.LogicNode;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.ParameterNode;
import org.graalvm.compiler.nodes.PhiNode;
import org.graalvm.compiler.nodes.PiNode;
import org.graalvm.compiler.nodes.ReturnNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.calc.FloatingNode;
import org.graalvm.compiler.nodes.calc.IsNullNode;
import org.graalvm.compiler.nodes.extended.AnchoringNode;
import org.graalvm.compiler.nodes.extended.BytecodeExceptionNode;
import org.graalvm.compiler.nodes.graphbuilderconf.ClassInitializationPlugin;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderTool;
import org.graalvm.compiler.nodes.graphbuilderconf.InlineInvokePlugin;
import org.graalvm.compiler.nodes.graphbuilderconf.InvocationPlugin;
import org.graalvm.compiler.nodes.graphbuilderconf.InvocationPlugins;
import org.graalvm.compiler.nodes.graphbuilderconf.NodePlugin;
import org.graalvm.compiler.nodes.graphbuilderconf.ParameterPlugin;
import org.graalvm.compiler.nodes.java.FinalFieldBarrierNode;
import org.graalvm.compiler.nodes.java.InstanceOfNode;
import org.graalvm.compiler.nodes.java.LoadFieldNode;
import org.graalvm.compiler.nodes.java.MethodCallTargetNode;
import org.graalvm.compiler.nodes.java.NewArrayNode;
import org.graalvm.compiler.nodes.java.NewInstanceNode;
import org.graalvm.compiler.nodes.java.StoreFieldNode;
import org.graalvm.compiler.nodes.spi.Replacements;
import org.graalvm.compiler.nodes.type.StampTool;
import org.graalvm.compiler.phases.OptimisticOptimizations;
import org.graalvm.compiler.phases.common.CanonicalizerPhase;
import org.graalvm.compiler.phases.util.Providers;
import org.graalvm.compiler.replacements.MethodHandlePlugin;
import org.graalvm.compiler.serviceprovider.JavaVersionUtil;
import org.graalvm.compiler.word.WordOperationPlugin;
import org.graalvm.compiler.word.WordTypes;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.StreamSupport;

import static org.graalvm.compiler.nodes.graphbuilderconf.InlineInvokePlugin.InlineInfo.createStandardInlineInfo;

public class PointstoMethodHandlePlugin implements NodePlugin {
    protected Providers parsingProviders;
    private final Providers universeProviders;
    private final AnalysisUniverse aUniverse;

    private final ClassInitializationPlugin classInitializationPlugin;

    private final ResolvedJavaType methodHandleType;
    private final Set<String> methodHandleInvokeMethodNames;

    private final Class<?> varHandleClass;
    private final Class<?> varHandleAccessModeClass;
    private final ResolvedJavaType varHandleType;
    private final Field varHandleVFormField;
    private final Method varFormInitMethod;
    private final Method varHandleIsAccessModeSupportedMethod;
    private final Method varHandleAccessModeTypeMethod;
    private final Function<Exception, RuntimeException> shouldNotReachHere;

    public PointstoMethodHandlePlugin(Providers providers, AnalysisUniverse aUniverse, Providers parsingProviders, ClassInitializationPlugin classInitializationPlugin,
                                      Function<Exception, RuntimeException> shouldNotReachHere) {
        this.aUniverse = aUniverse;
        this.universeProviders = providers;
        this.parsingProviders = parsingProviders;
        this.classInitializationPlugin = classInitializationPlugin;
        this.shouldNotReachHere = shouldNotReachHere;
        methodHandleType = universeProviders.getMetaAccess().lookupJavaType(java.lang.invoke.MethodHandle.class);
        methodHandleInvokeMethodNames = new HashSet<>();

        if (JavaVersionUtil.JAVA_SPEC >= 11) {
            methodHandleInvokeMethodNames.addAll(Arrays.asList("invokeExact", "invoke", "invokeBasic", "linkToVirtual", "linkToStatic", "linkToSpecial", "linkToInterface"));
            try {
                varHandleClass = Class.forName("java.lang.invoke.VarHandle");
                varHandleAccessModeClass = Class.forName("java.lang.invoke.VarHandle$AccessMode");
                varHandleType = universeProviders.getMetaAccess().lookupJavaType(varHandleClass);
                varHandleVFormField = ReflectionUtil.lookupField(varHandleClass, "vform");
                Class<?> varFormClass = Class.forName("java.lang.invoke.VarForm");
                varFormInitMethod = ReflectionUtil.lookupMethod(varFormClass, "getMethodType_V", int.class);
                varHandleIsAccessModeSupportedMethod = ReflectionUtil.lookupMethod(varHandleClass, "isAccessModeSupported", varHandleAccessModeClass);
                varHandleAccessModeTypeMethod = ReflectionUtil.lookupMethod(varHandleClass, "accessModeType", varHandleAccessModeClass);
            } catch (ClassNotFoundException ex) {
                throw shouldNotReachHere.apply(ex);
            }
        } else {
            varHandleClass = null;
            varHandleAccessModeClass = null;
            varHandleType = null;
            varHandleVFormField = null;
            varFormInitMethod = null;
            varHandleIsAccessModeSupportedMethod = null;
            varHandleAccessModeTypeMethod = null;
        }
    }

    @Override
    public boolean handleInvoke(GraphBuilderContext b, ResolvedJavaMethod method, ValueNode[] originalArgs) {
        ValueNode receiverForNullCheck = null;
        ValueNode[] args = originalArgs;
        if ((!method.isStatic() || isVarHandleGuards(method)) && args.length > 0 && args[0] instanceof PhiNode) {
            PhiNode phi = (PhiNode) args[0];
            /*
             * For loop phis, not all inputs are available yet since we have not parsed the loop
             * body yet.
             */
            if (!phi.isLoopPhi()) {
                ValueNode filteredReceiver = filterNullPhiInputs(phi);
                if (filteredReceiver != phi && filteredReceiver.isJavaConstant()) {
                    receiverForNullCheck = phi;
                    args = Arrays.copyOf(args, args.length);
                    args[0] = filteredReceiver;
                }
            }
        }

        /*
         * We want to process invokes that have a constant MethodHandle parameter. And we need a
         * direct call, otherwise we do not have a single target method.
         */
        if (b.getInvokeKind().isDirect() && (hasMethodHandleArgument(args) || isVarHandleMethod(b, method, args)) && !ignoreMethod(method)) {
            if (b.bciCanBeDuplicated()) {
                /*
                 * If we capture duplication of the bci, we don't process invoke.
                 */
                return reportUnsupportedFeature(b, method);
            } else {
                if (receiverForNullCheck != null) {
                    b.nullCheckedValue(receiverForNullCheck);
                }
                return processInvokeWithMethodHandle(b, universeProviders.getReplacements(), method, args);
            }

        } else if (methodHandleType.equals(method.getDeclaringClass()) && methodHandleInvokeMethodNames.contains(method.getName())) {
            /*
             * The native methods defined in the class MethodHandle are currently not implemented at
             * all. Normally, we would mark them as @Delete to give the user a good error message.
             * Unfortunately, that does not work for the MethodHandle methods because they are
             * signature polymorphic, i.e., they exist in every possible signature. Therefore, we
             * must only look at the declaring class and the method name here.
             */
            return reportUnsupportedFeature(b, method);
        } else {
            return false;
        }
    }

    private static ValueNode filterNullPhiInputs(PhiNode phi) {
        ValueNode notAlwaysNullPhiInput = null;
        for (ValueNode phiInput : phi.values()) {
            if (StampTool.isPointerAlwaysNull(phiInput)) {
                /* Ignore always null phi inputs. */
            } else if (notAlwaysNullPhiInput != null) {
                /* More than one not-always-null phi inputs. Nothing more to optimize. */
                return phi;
            } else {
                /* First not-always-null phi input. */
                notAlwaysNullPhiInput = phiInput;
            }
        }
        return notAlwaysNullPhiInput;
    }

    private boolean hasMethodHandleArgument(ValueNode[] args) {
        for (ValueNode argument : args) {
            if (argument.isConstant() && argument.getStackKind() == JavaKind.Object && asObject(argument.asJavaConstant()) instanceof MethodHandle) {
                return true;
            }
        }
        return false;
    }

    protected Object asObject(JavaConstant javaConstant) {
        return universeProviders.getSnippetReflection().asObject(MethodHandle.class, javaConstant);
    }

    /**
     * Checks if the method is the intrinsification root for a VarHandle. In the current VarHandle
     * implementation, all guards are in the automatically generated class VarHandleGuards. All
     * methods do have a VarHandle argument, and we expect it to be a compile-time constant.
     * <p>
     * See the documentation in {@link VarHandleFeature} for more information on the overall
     * VarHandle support.
     */
    private boolean isVarHandleMethod(GraphBuilderContext b, ResolvedJavaMethod method, ValueNode[] args) {
        /*
         * We do the check by class name because then we 1) do not need an explicit Java version
         * check (VarHandle was introduced with JDK 9), 2) VarHandleGuards is a non-public class
         * that we cannot reference by class literal, and 3) we do not need to worry about analysis
         * vs. hosted types. If the VarHandle implementation changes, we need to update our whole
         * handling anyway.
         */
        if (isVarHandleGuards(method)) {
            if (args.length < 1 || !args[0].isJavaConstant() || !isVarHandle(args[0])) {
                return reportUnsupportedFeature(b, method);
            }

            try {
                /*
                 * The field VarHandle.vform.methodType_V_table is a @Stable field but initialized
                 * lazily on first access. Therefore, constant folding can happen only after
                 * initialization has happened. We force initialization by invoking the method
                 * VarHandle.vform.getMethodType_V(0).
                 */
                Object varHandle = asObject(args[0].asJavaConstant());
                Object varForm = varHandleVFormField.get(varHandle);
                varFormInitMethod.invoke(varForm, 0);

                /*
                 * The AccessMode used for the access that we are going to intrinsify is hidden in a
                 * AccessDescriptor object that is also passed in as a parameter to the intrinsified
                 * method. Initializing all AccessMode enum values is easier than trying to extract
                 * the actual AccessMode.
                 */
                for (Object accessMode : varHandleAccessModeClass.getEnumConstants()) {
                    /*
                     * Force initialization of the @Stable field VarHandle.vform.memberName_table.
                     * Starting with JDK 17, this field is lazily initialized.
                     */
                    varHandleIsAccessModeSupportedMethod.invoke(varHandle, accessMode);
                    /*
                     * Force initialization of the @Stable field
                     * VarHandle.typesAndInvokers.methodType_table.
                     */
                    varHandleAccessModeTypeMethod.invoke(varHandle, accessMode);
                }
            } catch (ReflectiveOperationException ex) {
                throw shouldNotReachHere.apply(ex);
            }

            return true;
        } else {
            return false;
        }
    }

    private static boolean isVarHandleGuards(ResolvedJavaMethod method) {
        return method.getDeclaringClass().toJavaName(true).equals("java.lang.invoke.VarHandleGuards");
    }

    private boolean isVarHandle(ValueNode arg) {
        return varHandleType.isAssignableFrom(universeProviders.getMetaAccess().lookupJavaType(arg.asJavaConstant()));
    }

    private static final List<Pair<String, List<String>>> IGNORE_FILTER = Arrays.asList(
                    Pair.create("java.lang.invoke.MethodHandles", Arrays.asList("dropArguments", "filterReturnValue", "foldArguments", "insertArguments")),
                    Pair.create("java.lang.invoke.Invokers", Collections.singletonList("spreadInvoker")));

    private static boolean ignoreMethod(ResolvedJavaMethod method) {
        String className = method.getDeclaringClass().toJavaName(true);
        String methodName = method.getName();
        for (Pair<String, List<String>> ignored : IGNORE_FILTER) {
            if (ignored.getLeft().equals(className) && ignored.getRight().contains(methodName)) {
                return true;
            }
        }
        return false;
    }

    protected ResolvedJavaType toOriginal(ResolvedJavaType type) {
        if (type instanceof AnalysisType) {
            return ((AnalysisType) type).getWrapped();
        } else {
            return type;
        }
    }

    protected ResolvedJavaMethod toOriginal(ResolvedJavaMethod method) {
        if (method instanceof AnalysisMethod) {
            return ((AnalysisMethod) method).wrapped;
        } else {
            return method;
        }
    }

    class MethodHandlesParameterPlugin implements ParameterPlugin {
        private final ValueNode[] methodHandleArguments;

        MethodHandlesParameterPlugin(ValueNode[] methodHandleArguments) {
            this.methodHandleArguments = methodHandleArguments;
        }

        @Override
        public FloatingNode interceptParameter(GraphBuilderTool b, int index, StampPair stamp) {
            if (methodHandleArguments[index].isConstant()) {
                /* Convert the constant from our world to the HotSpot world. */
                return ConstantNode.forConstant(toOriginal(methodHandleArguments[index].asJavaConstant()), parsingProviders.getMetaAccess());
            } else {
                /*
                 * Propagate the parameter type from our world to the HotSpot world. We have more
                 * precise type information from the arguments than the parameters of the method
                 * would be.
                 */
                Stamp argStamp = methodHandleArguments[index].stamp(NodeView.DEFAULT);
                ResolvedJavaType argType = StampTool.typeOrNull(argStamp);
                if (argType != null) {
                    // TODO For trustInterfaces = false, we cannot be more specific here
                    // (i.e. we cannot use TypeReference.createExactTrusted here)
                    TypeReference typeref = TypeReference.createWithoutAssumptions(toOriginal(argType));
                    argStamp = StampTool.isPointerNonNull(argStamp) ? StampFactory.objectNonNull(typeref) : StampFactory.object(typeref);
                }
                return new ParameterNode(index, StampPair.createSingle(argStamp));
            }
        }
    }

    class MethodHandlesInlineInvokePlugin implements InlineInvokePlugin {
        @Override
        public InlineInfo shouldInlineInvoke(GraphBuilderContext b, ResolvedJavaMethod method, ValueNode[] args) {
            /* Avoid infinite recursion and excessive graphs with (more or less random) limits. */
            if (b.getDepth() > 20 || b.getGraph().getNodeCount() > 1000) {
                return null;
            }

            String className = method.getDeclaringClass().toJavaName(true);
            if (className.startsWith("java.lang.invoke.VarHandle") && (!className.equals("java.lang.invoke.VarHandle") || method.getName().equals("getMethodHandleUncached"))) {
                /*
                 * Do not inline implementation methods of various VarHandle implementation classes.
                 * They are too complex and cannot be reduced to a single invoke or field access.
                 * There is also no need to inline them, because they are not related to any
                 * MethodHandle mechanism.
                 *
                 * Methods defined in VarHandle itself are fine and not covered by this rule, apart
                 * from well-known methods that are never useful to be inlined. If these methods are
                 * reached, intrinsification will not be possible in any case.
                 */
                return null;
            } else if (className.startsWith("java.lang.invoke") && !className.contains("InvokerBytecodeGenerator")) {
                /*
                 * Inline all helper methods used by method handles. We do not know exactly which
                 * ones they are, but they are all be from the same package.
                 */
                return createStandardInlineInfo(method);
            }
            return null;
        }
    }

    private static void registerInvocationPlugins(InvocationPlugins plugins, Replacements replacements) {
        InvocationPlugins.Registration r = new InvocationPlugins.Registration(plugins, "java.lang.invoke.DirectMethodHandle", replacements);
        r.register1("ensureInitialized", InvocationPlugin.Receiver.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                /*
                 * Method handles for static methods have a guard that initializes the class (if the
                 * class was not yet initialized when the method handle was created). We emit the
                 * class initialization check manually later on when appending nodes to the target
                 * graph.
                 */
                return true;
            }
        });

        r = new InvocationPlugins.Registration(plugins, "java.lang.invoke.Invokers", replacements);
        r.registerOptional1("maybeCustomize", MethodHandle.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode mh) {
                /*
                 * JDK 8 update 60 added an additional customization possibility for method handles.
                 * For all use cases that we care about, that seems to be unnecessary, so we can
                 * just do nothing.
                 */
                return true;
            }
        });

        r = new InvocationPlugins.Registration(plugins, Objects.class, replacements);
        r.register1("requireNonNull", Object.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver unused, ValueNode object) {
                /*
                 * Instead of inlining the method, intrinsify it to a pattern that we can easily
                 * detect when looking at the parsed graph.
                 */
                b.push(JavaKind.Object, b.addNonNullCast(object));
                return true;
            }
        });
    }

    protected void appendWordTypeRewriting(GraphBuilderConfiguration.Plugins graphBuilderPlugins) {
        // In standalone mode, the sinippetReflection in aUniverse is the original one.
        WordOperationPlugin wordOperationPlugin = new WordOperationPlugin(aUniverse.getSnippetReflection(),
                new WordTypes(parsingProviders.getMetaAccess(), universeProviders.getWordTypes().getWordKind()));
        appendWordOpPlugins(graphBuilderPlugins, wordOperationPlugin, new TrustedInterfaceTypePlugin());
    }

    final protected void appendWordOpPlugins(GraphBuilderConfiguration.Plugins graphBuilderPlugins, WordOperationPlugin wordOperationPlugin, TrustedInterfaceTypePlugin trustedInterfaceTypePlugin) {
        graphBuilderPlugins.appendInlineInvokePlugin(wordOperationPlugin);
        graphBuilderPlugins.appendTypePlugin(wordOperationPlugin);
        graphBuilderPlugins.appendTypePlugin(trustedInterfaceTypePlugin);
        graphBuilderPlugins.appendNodePlugin(wordOperationPlugin);
    }

    protected void afterTransplanter(GraphBuilderContext b) {
        // do nothing
    }

    @SuppressWarnings("try")
    protected boolean processInvokeWithMethodHandle(GraphBuilderContext b, Replacements replacements, ResolvedJavaMethod methodHandleMethod, ValueNode[] methodHandleArguments) {
        GraphBuilderConfiguration.Plugins graphBuilderPlugins = new GraphBuilderConfiguration.Plugins(parsingProviders.getReplacements().getGraphBuilderPlugins());

        registerInvocationPlugins(graphBuilderPlugins.getInvocationPlugins(), replacements);

        graphBuilderPlugins.prependParameterPlugin(new MethodHandlesParameterPlugin(methodHandleArguments));
        graphBuilderPlugins.clearInlineInvokePlugins();
        graphBuilderPlugins.prependInlineInvokePlugin(new MethodHandlesInlineInvokePlugin());
        graphBuilderPlugins.prependNodePlugin(new MethodHandlePlugin(parsingProviders.getConstantReflection().getMethodHandleAccess(), false));

        /* We do all the word type rewriting because parameters to the lambda can be word types. */
        appendWordTypeRewriting(graphBuilderPlugins);
        graphBuilderPlugins.setClassInitializationPlugin(new NoClassInitializationPlugin());

        GraphBuilderConfiguration graphBuilderConfig = GraphBuilderConfiguration.getSnippetDefault(graphBuilderPlugins);
        GraphBuilderPhase.Instance graphBuilder = new GraphBuilderPhase.Instance(parsingProviders, graphBuilderConfig, OptimisticOptimizations.NONE, null);

        DebugContext debug = b.getDebug();
        StructuredGraph graph = new StructuredGraph.Builder(b.getOptions(), debug).method(toOriginal(methodHandleMethod)).build();
        try (DebugContext.Scope s = debug.scope("IntrinsifyMethodHandles", graph)) {
            graphBuilder.apply(graph);
            /*
             * The canonicalizer converts unsafe field accesses for get/set method handles back to
             * high-level field load and store nodes.
             */
            CanonicalizerPhase.create().apply(graph, parsingProviders);

            debug.dump(DebugContext.VERBOSE_LEVEL, graph, "Intrinisfication graph before transplant");

            NodeMap<Node> transplanted = new NodeMap<>(graph);
            for (ParameterNode oParam : graph.getNodes(ParameterNode.TYPE)) {
                transplanted.put(oParam, methodHandleArguments[oParam.index()]);
            }

            Transplanter transplanter = new Transplanter(b, methodHandleMethod, transplanted);
            try {
                transplanter.graph(graph);
                afterTransplanter(b);
                return true;
            } catch (AbortTransplantException ex) {
                /*
                 * The method handle cannot be intrinsified. If non-constant method handles are not
                 * supported, the code that throws an error at runtime was already appended, so
                 * nothing more to do. If non-constant method handles are supported, we return false
                 * so that the bytecode parser emit a regular invoke bytecode, i.e., the constant
                 * method handle is treated as if it were non-constant.
                 */
                return ex.handled;
            }
        } catch (Throwable ex) {
            throw debug.handle(ex);
        }
    }

    /**
     * Transplants the graph parsed in the HotSpot universe into the currently parsed method. This
     * requires conversion of all types, methods, fields, and constants.
     *
     * Currently, only linear control flow in the original graph is supported. Nodes in the original
     * graph that have implicit exception edges ({@link InvokeNode}, {@link FixedGuardNode} are
     * converted to nodes with explicit exception edges. So the transplanted graph has a limited
     * amount of control flow for exception handling, but still no control flow merges.
     *
     * All necessary frame states use the same bci from the caller, i.e., all transplanted method
     * calls, field stores, exceptions, ... look as if they are coming from the original invocation
     * site of the method handle. This means the static analysis is not storing any analysis results
     * for these calls, because lookup of analysis results requires a unique bci.
     *
     * During this process, values are not pushed and popped from the frame state as usual. Instead,
     * at most one value is temporarily pushed onto the frame state's stack. During the generation
     * process {@link #tempFrameStackValue} is used to represent the value currently temporarily
     * pushed onto the stack.
     */
    class Transplanter {
        private final BytecodeParser b;
        private final ResolvedJavaMethod methodHandleMethod;
        private final NodeMap<Node> transplanted;
        private JavaKind tempFrameStackValue;

        Transplanter(GraphBuilderContext b, ResolvedJavaMethod methodHandleMethod, NodeMap<Node> transplanted) {
            this.b = (BytecodeParser) b;
            this.methodHandleMethod = methodHandleMethod;
            this.transplanted = transplanted;
            this.tempFrameStackValue = null;
        }

        void graph(StructuredGraph graph) throws AbortTransplantException {
            JavaKind returnResultKind = b.getInvokeReturnType().getJavaKind().getStackKind();
            FixedNode oNode = graph.start().next();
            while (true) {
                if (fixedWithNextNode(oNode)) {
                    oNode = ((FixedWithNextNode) oNode).next();

                } else if (oNode instanceof ReturnNode) {
                    ReturnNode oReturn = (ReturnNode) oNode;
                    /* Push the returned result. */
                    if (returnResultKind != JavaKind.Void) {
                        b.push(returnResultKind, node(oReturn.result()));
                    }
                    /* We are done. */
                    return;

                } else {
                    throw bailout();
                }
            }
        }

        /**
         * @return whether the current frame has enough space for a new value of the given kind to
         *         be pushed to the stack.
         */
        private boolean frameStackHasSpaceForKind(JavaKind javaKind) {
            return b.getFrameStateBuilder().stackSize() + (javaKind.needsTwoSlots() ? 2 : 1) <= b.getMethod().getMaxStackSize();
        }

        /**
         * If space is available, temporarily push {@code value} onto frame's stack.
         */
        private void pushToFrameStack(ValueNode value) {
            JavaKind kind = value.getStackKind();
            /* Pushing new value if there is space. */
            if (frameStackHasSpaceForKind(kind)) {
                b.push(kind, value);
                tempFrameStackValue = kind;
            }
        }

        /*
         * Remove temp value, if present, from stack.
         */
        private void popTempFrameStackValue() {
            if (tempFrameStackValue != null) {
                b.pop(tempFrameStackValue);
                tempFrameStackValue = null;
            }
        }

        private boolean fixedWithNextNode(FixedNode oNode) throws AbortTransplantException {
            if (oNode.getClass() == InvokeNode.class) {
                InvokeNode oInvoke = (InvokeNode) oNode;
                MethodCallTargetNode oCallTarget = (MethodCallTargetNode) oInvoke.callTarget();
                transplantInvoke(oInvoke, lookup(oCallTarget.targetMethod()), oCallTarget.invokeKind(), nodes(oCallTarget.arguments()), oCallTarget.returnKind());
                return true;

            } else if (oNode.getClass() == FixedGuardNode.class) {
                FixedGuardNode oGuard = (FixedGuardNode) oNode;

                BytecodeExceptionNode.BytecodeExceptionKind tExceptionKind;
                ValueNode[] tExceptionArguments;
                if (oGuard.getReason() == DeoptimizationReason.NullCheckException) {
                    tExceptionKind = BytecodeExceptionNode.BytecodeExceptionKind.NULL_POINTER;
                    tExceptionArguments = new ValueNode[0];
                } else if (oGuard.getReason() == DeoptimizationReason.ClassCastException && oGuard.condition().getClass() == InstanceOfNode.class) {
                    /*
                     * Throwing the ClassCastException requires the checked object and the expected
                     * type as arguments, which we can get for the InstanceOfNode.
                     */
                    InstanceOfNode oCondition = (InstanceOfNode) oGuard.condition();
                    tExceptionKind = BytecodeExceptionNode.BytecodeExceptionKind.CLASS_CAST;
                    tExceptionArguments = new ValueNode[]{
                            node(oCondition.getValue()),
                            ConstantNode.forConstant(b.getConstantReflection().asJavaClass(lookup(oCondition.type().getType())), b.getMetaAccess(), b.getGraph())};
                } else {
                    /*
                     * Several other deoptimization reasons could be supported easily, but for now
                     * there is no need for them.
                     */
                    return false;
                }

                AbstractBeginNode tPassingSuccessor = b.emitBytecodeExceptionCheck((LogicNode) node(oGuard.condition()), !oGuard.isNegated(), tExceptionKind, tExceptionArguments);
                /*
                 * Anchor-usages of the guard are redirected to the BeginNode after the explicit
                 * exception check. If the check was eliminated, we add a new temporary BeginNode.
                 */
                transplanted.put(oGuard, tPassingSuccessor != null ? tPassingSuccessor : b.add(new BeginNode()));
                return true;

            } else if (oNode.getClass() == LoadFieldNode.class) {
                LoadFieldNode oLoad = (LoadFieldNode) oNode;
                ResolvedJavaField tTarget = lookup(oLoad.field());
                maybeEmitClassInitialization(b, tTarget.isStatic(), tTarget.getDeclaringClass());
                ValueNode tLoad = b.add(LoadFieldNode.create(null, node(oLoad.object()), tTarget));
                transplanted.put(oLoad, tLoad);
                return true;

            } else if (oNode.getClass() == StoreFieldNode.class) {
                StoreFieldNode oStore = (StoreFieldNode) oNode;
                ResolvedJavaField tTarget = lookup(oStore.field());
                maybeEmitClassInitialization(b, tTarget.isStatic(), tTarget.getDeclaringClass());
                b.add(new StoreFieldNode(node(oStore.object()), tTarget, node(oStore.value())));
                return true;

            } else if (oNode.getClass() == NewInstanceNode.class) {
                NewInstanceNode oNew = (NewInstanceNode) oNode;
                ResolvedJavaType tInstanceClass = lookup(oNew.instanceClass());
                maybeEmitClassInitialization(b, true, tInstanceClass);
                NewInstanceNode tNew = b.add(new NewInstanceNode(tInstanceClass, oNew.fillContents()));
                transplanted.put(oNew, tNew);
                return true;

            } else if (oNode.getClass() == NewArrayNode.class) {
                NewArrayNode oNew = (NewArrayNode) oNode;
                NewArrayNode tNew = b.add(new NewArrayNode(lookup(oNew.elementType()), node(oNew.length()), oNew.fillContents()));
                transplanted.put(oNew, tNew);
                return true;

            } else if (oNode.getClass() == FinalFieldBarrierNode.class) {
                FinalFieldBarrierNode oNew = (FinalFieldBarrierNode) oNode;
                FinalFieldBarrierNode tNew = b.add(new FinalFieldBarrierNode(node(oNew.getValue())));
                transplanted.put(oNew, tNew);
                return true;

            } else {
                return false;
            }
        }

        private ValueNode[] nodes(List<ValueNode> oNodes) throws AbortTransplantException {
            ValueNode[] tNodes = new ValueNode[oNodes.size()];
            for (int i = 0; i < tNodes.length; i++) {
                tNodes[i] = node(oNodes.get(i));
            }
            return tNodes;
        }

        private ValueNode node(Node oNode) throws AbortTransplantException {
            if (oNode == null) {
                return null;
            }
            Node tNode = transplanted.get(oNode);
            if (tNode != null) {
                return (ValueNode) tNode;
            }

            if (oNode.getClass() == ConstantNode.class) {
                ConstantNode oConstant = (ConstantNode) oNode;
                tNode = ConstantNode.forConstant(constant(oConstant.getValue()), universeProviders.getMetaAccess());

            } else if (oNode.getClass() == PiNode.class) {
                PiNode oPi = (PiNode) oNode;
                tNode = new PiNode(node(oPi.object()), stamp(oPi.piStamp()), node(oPi.getGuard().asNode()));

            } else if (oNode.getClass() == InstanceOfNode.class) {
                InstanceOfNode oInstanceOf = (InstanceOfNode) oNode;
                tNode = InstanceOfNode.createHelper(stamp(oInstanceOf.getCheckedStamp()),
                        node(oInstanceOf.getValue()),
                        oInstanceOf.profile(),
                        (AnchoringNode) node((ValueNode) oInstanceOf.getAnchor()));

            } else if (oNode.getClass() == IsNullNode.class) {
                IsNullNode oIsNull = (IsNullNode) oNode;
                tNode = IsNullNode.create(node(oIsNull.getValue()));

            } else if (oNode instanceof ArithmeticOperation) {
                /*
                 * We consider all arithmetic operations as safe for transplant, since they do not
                 * have side effects and also do not reference any types or other elements that we
                 * would need to modify.
                 */
                for (Node input : oNode.inputs()) {
                    /*
                     * Make sure all input nodes are transplanted first, and registered in the
                     * transplanted map.
                     */
                    node(input);
                }
                List<Node> oNodes = Collections.singletonList(oNode);
                UnmodifiableEconomicMap<Node, Node> tNodes = b.getGraph().addDuplicates(oNodes, oNode.graph(), 1, transplanted);
                /*
                 * The following assertion looks strange, but NodeMap.size() is not implemented so
                 * we need to iterate the map to get the size.
                 */
                assert StreamSupport.stream(tNodes.getKeys().spliterator(), false).count() == 1;
                tNode = tNodes.get(oNode);

            } else {
                throw bailout();
            }

            tNode = b.add((ValueNode) tNode);
            assert tNode.verify();
            transplanted.put(oNode, tNode);
            return (ValueNode) tNode;
        }

        private void transplantInvoke(FixedWithNextNode oNode, ResolvedJavaMethod tTargetMethod, CallTargetNode.InvokeKind invokeKind, ValueNode[] arguments, JavaKind invokeResultKind) {
            maybeEmitClassInitialization(b, invokeKind == CallTargetNode.InvokeKind.Static, tTargetMethod.getDeclaringClass());

            if (invokeResultKind == JavaKind.Void) {
                /*
                 * Invokedynamics can be parsed into a NewInstanceNode & InvokeNode combo. In this
                 * situation, it is necessary to push the NewInstanceNode onto the stack so that it
                 * is included in the stateDuring FrameState of the InvokeNode.
                 */
                Node pred = oNode.predecessor();
                if (pred.getClass() == NewInstanceNode.class && transplanted.containsKey(pred)) {
                    Node tNew = transplanted.get(pred);
                    pushToFrameStack((ValueNode) tNew);
                }
            }

            b.handleReplacedInvoke(invokeKind, tTargetMethod, arguments, false);

            if (invokeResultKind != JavaKind.Void) {
                /*
                 * The invoke was pushed by handleReplacedInvoke, pop it again. Note that the popped
                 * value is not necessarily an Invoke, because inlining during parsing and
                 * intrinsification can happen.
                 */
                transplanted.put(oNode, b.pop(invokeResultKind));
            } else {
                popTempFrameStackValue();
            }
        }

        @SuppressWarnings("unchecked")
        private <T extends Stamp> T stamp(T oStamp) throws AbortTransplantException {
            Stamp result;
            if (((Stamp)oStamp).getClass() == ObjectStamp.class) {
                ObjectStamp oObjectStamp = (ObjectStamp) oStamp;
                result = new ObjectStamp(lookup(oObjectStamp.type()), oObjectStamp.isExactType(), oObjectStamp.nonNull(), oObjectStamp.alwaysNull(), oObjectStamp.isAlwaysArray());
            } else if (oStamp instanceof PrimitiveStamp) {
                result = oStamp;
            } else {
                throw bailout();
            }
            assert oStamp.getClass() == result.getClass();
            return (T) result;
        }

        private JavaConstant constant(Constant oConstant) throws AbortTransplantException {
            JavaConstant tConstant;
            if (oConstant == JavaConstant.NULL_POINTER) {
                return JavaConstant.NULL_POINTER;
            } else if (oConstant instanceof JavaConstant) {
                tConstant = lookup((JavaConstant) oConstant);
            } else {
                throw bailout();
            }

            if (tConstant.getJavaKind() == JavaKind.Object) {
                /*
                 * The object replacer are not invoked when parsing in the HotSpot universe, so we
                 * also need to do call the replacer here.
                 */
                Object oldObject = aUniverse.getSnippetReflection().asObject(Object.class, tConstant);
                Object newObject = aUniverse.replaceObject(oldObject);
                if (newObject != oldObject) {
                    return aUniverse.getSnippetReflection().forObject(newObject);
                }
            }
            return tConstant;
        }

        private RuntimeException bailout() throws AbortTransplantException {
            boolean handled = reportUnsupportedFeature(b, methodHandleMethod);
            /*
             * We need to get out of recursive transplant methods. Easier to use an exception than
             * to explicitly check every method invocation for a possible abort.
             */
            throw new AbortTransplantException(handled);
        }
    }

    @SuppressWarnings("serial")
    static class AbortTransplantException extends Exception {
        private final boolean handled;

        AbortTransplantException(boolean handled) {
            this.handled = handled;
        }
    }

    protected boolean reportUnsupportedFeature(GraphBuilderContext b, ResolvedJavaMethod methodHandleMethod) {
        //If it is the standalone pointsto analysis, just ignore the unsupported case and continue to analyze the rest.
        if (!getClass().getSuperclass().equals(PointstoMethodHandlePlugin.class)) {
            return false;
        }
        //If it is the analysis executed during native image building time, throw UnsupportedFeatureException
        else {
            String message = "Invoke with MethodHandle argument could not be reduced to at most a single call or single field access. " +
                    "The method handle must be a compile time constant, e.g., be loaded from a `static final` field. " +
                    "Method that contains the method handle invocation: " + methodHandleMethod.format("%H.%n(%p)");

            return reportUnsupportedFeature(b, message);
        }
    }

    protected boolean reportUnsupportedFeature(GraphBuilderContext b, String message) {
        throw new UnsupportedFeatureException(message);
    }

    private void maybeEmitClassInitialization(GraphBuilderContext b, boolean isStatic, ResolvedJavaType declaringClass) {
        if (isStatic) {
            /*
             * We know that this code only runs during bytecode parsing, so the casts to
             * BytecodeParser are safe. We want to avoid putting additional rarely used methods into
             * GraphBuilderContext.
             */
            classInitializationPlugin.apply(b, declaringClass, () -> ((BytecodeParser) b).getFrameStateBuilder().create(b.bci(), (BytecodeParser) b.getNonIntrinsicAncestor(), false, null, null));
        }
    }

    protected ResolvedJavaMethod lookup(ResolvedJavaMethod method) {
        return aUniverse.lookup(method);
    }

    protected ResolvedJavaField lookup(ResolvedJavaField field) {
        aUniverse.lookup(field.getDeclaringClass()).registerAsReachable();
        return aUniverse.lookup(field);
    }

    protected ResolvedJavaType lookup(ResolvedJavaType type) {
        return aUniverse.lookup(type);
    }

    protected ResolvedJavaType optionalLookup(ResolvedJavaType type) {
        return aUniverse.optionalLookup(type);
    }

    private JavaConstant lookup(JavaConstant constant) {
        return aUniverse.lookup(constant);
    }

    private JavaConstant toOriginal(JavaConstant constant) {
        return aUniverse.toHosted(constant);
    }
}
