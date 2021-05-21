package com.mattworzala.canvas.compiler.fragment

import com.mattworzala.canvas.compiler.lower.InlineLambdaInfo
import com.mattworzala.canvas.compiler.lower.IrInlineReferenceLocator
import com.mattworzala.canvas.compiler.lower.ModuleLoweringPass
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.jvm.lower.inlineclasses.InlineClassAbi
import org.jetbrains.kotlin.builtins.*
import org.jetbrains.kotlin.builtins.functions.FunctionClassKind
import org.jetbrains.kotlin.builtins.functions.FunctionInvokeDescriptor
import org.jetbrains.kotlin.cfg.index
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.AnonymousFunctionDescriptor
import org.jetbrains.kotlin.descriptors.impl.ValueParameterDescriptorImpl
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.backend.js.utils.OperatorNames
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrTypeParameterImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrValueParameterImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrVariableImpl
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrTypeParameterSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrVariableSymbolImpl
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.types.impl.IrStarProjectionImpl
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.DescriptorFactory
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.source.PsiSourceElement
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.utils.DFS


//todo i want to remove the vast majority of this class
abstract class AbstractFragmentLowering(
    val context: IrPluginContext,
    val symbolRemapper: DeepCopySymbolRemapper,
    val bindingTrace: BindingTrace
) : IrElementTransformerVoid(), ModuleLoweringPass {
    var inlinedFunctions: Set<InlineLambdaInfo> = setOf();

    override fun lower(module: IrModuleFragment) {
        inlinedFunctions = IrInlineReferenceLocator.scan(context, module)
    }

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    protected val typeTranslator =
        TypeTranslator(
            context.symbolTable,
            context.languageVersionSettings,
            context.builtIns
        ).apply {
            constantValueGenerator = ConstantValueGenerator(
                context.moduleDescriptor,
                context.symbolTable
            )
            constantValueGenerator.typeTranslator = this
        }

    protected val builtIns = context.irBuiltIns

    //todo stability stuff

    private val _fragmentIrClass = context.referenceClass(FragmentFqNames.FragmentContext)?.owner
        ?: error("Cannot find the Fragment class in the classpath")

    // this ensures that composer always references up-to-date composer class symbol
    // otherwise, after remapping of symbols in DeepCopyTransformer, it results in duplicated
    // references
    protected val fragmentIrClass: IrClass
        get() = symbolRemapper.getReferencedClass(_fragmentIrClass.symbol).owner

    fun referenceFunction(symbol: IrFunctionSymbol): IrFunctionSymbol {
        return symbolRemapper.getReferencedFunction(symbol)
    }

    fun referenceSimpleFunction(symbol: IrSimpleFunctionSymbol): IrSimpleFunctionSymbol {
        return symbolRemapper.getReferencedSimpleFunction(symbol)
    }

    fun referenceConstructor(symbol: IrConstructorSymbol): IrConstructorSymbol {
        return symbolRemapper.getReferencedConstructor(symbol)
    }

    fun getTopLevelClass(fqName: FqName): IrClassSymbol {
        return context.referenceClass(fqName) ?: error("Class not found in the classpath: $fqName")
    }

    fun getTopLevelFunction(fqName: FqName): IrFunctionSymbol {
        return context.referenceFunctions(fqName).firstOrNull()
            ?: error("Function not found in the classpath: $fqName")
    }

    fun getTopLevelFunctions(fqName: FqName): List<IrSimpleFunctionSymbol> {
        return context.referenceFunctions(fqName).toList()
    }

    fun getInternalFunction(name: String) = getTopLevelFunction(
        FragmentFqNames.internalFqNameFor(name)
    )

    fun getInternalProperty(name: String) = getTopLevelPropertyGetter(
        FragmentFqNames.internalFqNameFor(name)
    )

    fun getInternalClass(name: String) = getTopLevelClass(
        FragmentFqNames.internalFqNameFor(name)
    )

    fun getTopLevelPropertyGetter(fqName: FqName): IrFunctionSymbol {
        val propertySymbol = context.referenceProperties(fqName).firstOrNull()
            ?: error("Property was not found $fqName")
        return symbolRemapper.getReferencedFunction(
            propertySymbol.owner.getter!!.symbol
        )
    }

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    fun KotlinType.toIrType(): IrType = typeTranslator.translateType(this)

    fun IrType.unboxInlineClass() = unboxType() ?: this

    fun IrType.replaceArgumentsWithStarProjections(): IrType =
        when (this) {
            is IrSimpleType -> IrSimpleTypeImpl(
                classifier,
                hasQuestionMark,
                List(arguments.size) { IrStarProjectionImpl },
                annotations,
                abbreviation
            )
            else -> this
        }

    // IR external stubs don't have their value parameters' parent properly mapped to the
    // function itself. This normally isn't a problem because nothing in the IR lowerings ask for
    // the parent of the parameters, but we do. I believe this should be considered a bug in
    // kotlin proper, but this works around it.
    fun IrValueParameter.hasDefaultValueSafe(): Boolean = DFS.ifAny(
        listOf(this),
        { current ->
            (current.parent as? IrSimpleFunction)?.overriddenSymbols?.map { fn ->
                fn.owner.valueParameters[current.index].also { p ->
                    p.parent = fn.owner
                }
            } ?: listOf()
        },
        { current -> current.defaultValue != null }
    )

    // NOTE(lmr): This implementation mimics the kotlin-provided unboxInlineClass method, except
    // this one makes sure to bind the symbol if it is unbound, so is a bit safer to use.
    fun IrType.unboxType(): IrType? {
        val classSymbol = classOrNull ?: return null
        val klass = classSymbol.owner
        if (!klass.isInline) return null

        // TODO: Apply type substitutions
        val underlyingType = InlineClassAbi.getUnderlyingType(klass).unboxInlineClass()
        if (!isNullable()) return underlyingType
        if (underlyingType.isNullable() || underlyingType.isPrimitiveType())
            return null
        return underlyingType.makeNullable()
    }

    protected fun IrExpression.unboxValueIfInline(): IrExpression {
        if (type.isNullable()) return this
        val classSymbol = type.classOrNull ?: return this
        val klass = classSymbol.owner
        if (klass.isInline) {
            val primaryValueParameter = klass
                .primaryConstructor
                ?.valueParameters
                ?.get(0) ?: error("Expected a value parameter")
            val fieldGetter = klass.getPropertyGetter(primaryValueParameter.name.identifier)
                ?: error("Expected a getter")
            return irCall(
                symbol = fieldGetter,
                dispatchReceiver = this
            ).unboxValueIfInline()
        }
        return this
    }

    fun IrAnnotationContainer.hasFragmentAnnotation(): Boolean {
        return annotations.hasAnnotation(FragmentFqNames.Fragment)
    }

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    fun List<IrConstructorCall>.hasAnnotation(fqName: FqName): Boolean =
        any { it.symbol.descriptor.constructedClass.fqNameSafe == fqName }

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    fun IrCall.isInvoke(): Boolean {
        return origin == IrStatementOrigin.INVOKE || symbol.descriptor is FunctionInvokeDescriptor
    }

    fun IrCall.isTransformedComposableCall(): Boolean {
        return context.irTrace[FragmentWritableSlices.IS_COMPOSABLE_CALL, this] ?: false
    }

    fun IrCall.isSyntheticComposableCall(): Boolean {
        return context.irTrace[FragmentWritableSlices.IS_SYNTHETIC_COMPOSABLE_CALL, this] == true
    }

    fun IrCall.isComposableSingletonGetter(): Boolean {
        return context.irTrace[FragmentWritableSlices.IS_COMPOSABLE_SINGLETON, this] == true
    }

    fun IrClass.isComposableSingletonClass(): Boolean {
        return context.irTrace[FragmentWritableSlices.IS_COMPOSABLE_SINGLETON_CLASS, this] == true
    }

    fun IrFunction.isInlinedLambda(): Boolean {
        for (element in inlinedFunctions) {
            if (element.argument.function == this) return true
        }
        return false
    }

    protected val KotlinType.isEnum
        get() =
            (constructor.declarationDescriptor as? ClassDescriptor)?.kind == ClassKind.ENUM_CLASS

    fun KotlinType.isFinal(): Boolean = (constructor.declarationDescriptor as? ClassDescriptor)
        ?.modality == Modality.FINAL

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    fun FunctionDescriptor.allowsFragmentCalls(): Boolean {
        return allowsFragmentCalls(context.bindingContext)
    }

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    fun IrFunctionExpression.allowsFragmentCalls(): Boolean =
        function.descriptor.allowsFragmentCalls(context.bindingContext)

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    private fun IrFunction.createParameterDeclarations() {
        fun ParameterDescriptor.irValueParameter() = IrValueParameterImpl(
            this.startOffset ?: UNDEFINED_OFFSET,
            this.endOffset ?: UNDEFINED_OFFSET,
            IrDeclarationOrigin.DEFINED,
            IrValueParameterSymbolImpl(this),
            this.name,
            this.index(),
            type.toIrType(),
            (this as? ValueParameterDescriptor)?.varargElementType?.toIrType(),
            this.isCrossinline,
            this.isNoinline,
            false,
            false
        ).also {
            it.parent = this@createParameterDeclarations
        }

        fun TypeParameterDescriptor.irTypeParameter() = IrTypeParameterImpl(
            this.startOffset ?: UNDEFINED_OFFSET,
            this.endOffset ?: UNDEFINED_OFFSET,
            IrDeclarationOrigin.DEFINED,
            IrTypeParameterSymbolImpl(this),
            this.name,
            this.index,
            this.isReified,
            this.variance
        ).also {
            it.parent = this@createParameterDeclarations
        }

        dispatchReceiverParameter = descriptor.dispatchReceiverParameter?.irValueParameter()
        extensionReceiverParameter = descriptor.extensionReceiverParameter?.irValueParameter()

        valueParameters = descriptor.valueParameters.map { it.irValueParameter() }

        typeParameters = descriptor.typeParameters.map { it.irTypeParameter() }
    }

    protected fun IrBuilderWithScope.irLambdaExpression(
        descriptor: FunctionDescriptor,
        type: IrType,
        body: IrBlockBodyBuilder.(IrFunction) -> Unit
    ) = irLambdaExpression(this.startOffset, this.endOffset, descriptor, type, body)

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    protected fun IrBuilderWithScope.irLambdaExpression(
        startOffset: Int,
        endOffset: Int,
        descriptor: FunctionDescriptor,
        type: IrType,
        body: IrBlockBodyBuilder.(IrFunction) -> Unit
    ): IrExpression {
        val symbol = IrSimpleFunctionSymbolImpl(descriptor)

        require(type.isFunction()) { "Function references should always have function type" }
        val returnType = (type as IrSimpleTypeImpl).arguments.last() as IrType

        val lambda = IrFunctionImpl(
            startOffset, endOffset,
            IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA,
            symbol,
            name = descriptor.name,
            visibility = descriptor.visibility,
            modality = descriptor.modality,
            returnType = returnType,
            isInline = descriptor.isInline,
            isExternal = descriptor.isExternal,
            isTailrec = descriptor.isTailrec,
            isSuspend = descriptor.isSuspend,
            isOperator = descriptor.isOperator,
            isExpect = descriptor.isExpect,
            isInfix = descriptor.isInfix
        ).also {
            it.parent = scope.getLocalDeclarationParent()
            it.createParameterDeclarations()
            it.body = DeclarationIrBuilder(this@AbstractFragmentLowering.context, symbol)
                .irBlockBody { body(it) }
        }

        return irBlock(
            startOffset = startOffset,
            endOffset = endOffset,
            origin = IrStatementOrigin.LAMBDA,
            resultType = type
        ) {
            +lambda
            +IrFunctionReferenceImpl(
                startOffset = startOffset,
                endOffset = endOffset,
                type = type,
                symbol = symbol,
                typeArgumentsCount = descriptor.typeParametersCount,
                reflectionTarget = null,
                origin = IrStatementOrigin.LAMBDA,
                valueArgumentsCount = symbol.owner.valueParameters.size
            )
        }
    }

    @ObsoleteDescriptorBasedAPI
    protected fun IrBuilderWithScope.createFunctionDescriptor(
        type: IrType,
        owner: DeclarationDescriptor = scope.scopeOwnerSymbol.descriptor
    ): FunctionDescriptor {
        return AnonymousFunctionDescriptor(
            owner,
            Annotations.EMPTY,
            CallableMemberDescriptor.Kind.SYNTHESIZED,
            SourceElement.NO_SOURCE,
            false
        ).apply {
            val kotlinType = type.toKotlinType()
            initialize(
                kotlinType.getReceiverTypeFromFunctionType()?.let {
                    DescriptorFactory.createExtensionReceiverParameterForCallable(
                        this,
                        it,
                        Annotations.EMPTY
                    )
                },
                null,
                emptyList(),
                kotlinType.getValueParameterTypesFromFunctionType().mapIndexed { i, t ->
                    ValueParameterDescriptorImpl(
                        containingDeclaration = this,
                        original = null,
                        index = i,
                        annotations = Annotations.EMPTY,
                        name = t.type.extractParameterNameFromFunctionTypeArgument()
                            ?: Name.identifier("p$i"),
                        outType = t.type,
                        declaresDefaultValue = false,
                        isCrossinline = false,
                        isNoinline = false,
                        varargElementType = null,
                        source = SourceElement.NO_SOURCE
                    )
                },
                kotlinType.getReturnTypeFromFunctionType(),
                Modality.FINAL,
                DescriptorVisibilities.LOCAL,
                null
            )
            isOperator = false
            isInfix = false
            isExternal = false
            isInline = false
            isTailrec = false
            isSuspend = false
            isExpect = false
            isActual = false
        }
    }

    // set the bit at a certain index
    protected fun Int.withBit(index: Int, value: Boolean): Int {
        return if (value) {
            this or (1 shl index)
        } else {
            this and (1 shl index).inv()
        }
    }

    protected operator fun Int.get(index: Int): Boolean {
        return this and (1 shl index) != 0
    }

    // create a bitmask with the following bits
    protected fun bitMask(vararg values: Boolean): Int = values.foldIndexed(0) { i, mask, bit ->
        mask.withBit(i, bit)
    }

    protected fun irGetBit(param: IrDefaultBitMaskValue, index: Int): IrExpression {
        // value and (1 shl index) != 0
        return irNotEqual(
            param.irIsolateBitAtIndex(index),
            irConst(0)
        )
    }

    protected fun irSet(variable: IrValueDeclaration, value: IrExpression): IrExpression {
        return IrSetValueImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            context.irBuiltIns.unitType,
            variable.symbol,
            value = value,
            origin = null
        )
    }

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    protected fun irCall(
        symbol: IrFunctionSymbol,
        origin: IrStatementOrigin? = null,
        dispatchReceiver: IrExpression? = null,
        extensionReceiver: IrExpression? = null,
        vararg args: IrExpression
    ): IrCallImpl {
        return IrCallImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            symbol.owner.returnType,
            symbol as IrSimpleFunctionSymbol,
            symbol.owner.typeParameters.size,
            symbol.owner.valueParameters.size,
            origin
        ).also {
            if (dispatchReceiver != null) it.dispatchReceiver = dispatchReceiver
            if (extensionReceiver != null) it.extensionReceiver = extensionReceiver
            args.forEachIndexed { index, arg ->
                it.putValueArgument(index, arg)
            }
        }
    }

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    protected fun IrType.binaryOperator(name: Name, paramType: IrType): IrFunctionSymbol =
        context.symbols.getBinaryOperator(name, this, paramType)

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    protected fun IrType.unaryOperator(name: Name): IrFunctionSymbol =
        context.symbols.getUnaryOperator(name, this)

    protected fun irAnd(lhs: IrExpression, rhs: IrExpression): IrCallImpl {
        return irCall(
            lhs.type.binaryOperator(OperatorNames.AND, rhs.type),
            null,
            lhs,
            null,
            rhs
        )
    }

    protected fun irInv(lhs: IrExpression): IrCallImpl {
        val int = context.irBuiltIns.intType
        return irCall(
            int.unaryOperator(OperatorNames.INV),
            null,
            lhs
        )
    }

    protected fun irOr(lhs: IrExpression, rhs: IrExpression): IrCallImpl {
        val int = context.irBuiltIns.intType
        return irCall(
            int.binaryOperator(OperatorNames.OR, int),
            null,
            lhs,
            null,
            rhs
        )
    }

    protected fun irBooleanOr(lhs: IrExpression, rhs: IrExpression): IrCallImpl {
        val boolean = context.irBuiltIns.booleanType
        return irCall(
            boolean.binaryOperator(OperatorNames.OR, boolean),
            null,
            lhs,
            null,
            rhs
        )
    }

    protected fun irOrOr(lhs: IrExpression, rhs: IrExpression): IrExpression {
        return IrWhenImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            origin = IrStatementOrigin.OROR,
            type = context.irBuiltIns.booleanType,
            branches = listOf(
                IrBranchImpl(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    condition = lhs,
                    result = irConst(true)
                ),
                IrElseBranchImpl(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    condition = irConst(true),
                    result = rhs
                )
            )
        )
    }

    protected fun irAndAnd(lhs: IrExpression, rhs: IrExpression): IrExpression {
        return IrWhenImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            origin = IrStatementOrigin.ANDAND,
            type = context.irBuiltIns.booleanType,
            branches = listOf(
                IrBranchImpl(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    condition = lhs,
                    result = rhs
                ),
                IrElseBranchImpl(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    condition = irConst(true),
                    result = irConst(false)
                )
            )
        )
    }

    protected fun irXor(lhs: IrExpression, rhs: IrExpression): IrCallImpl {
        val int = context.irBuiltIns.intType
        return irCall(
            int.binaryOperator(OperatorNames.XOR, int),
            null,
            lhs,
            null,
            rhs
        )
    }

    protected fun irGreater(lhs: IrExpression, rhs: IrExpression): IrCallImpl {
        val int = context.irBuiltIns.intType
        val gt = context.irBuiltIns.greaterFunByOperandType[int.classifierOrFail]
        return irCall(
            gt!!,
            IrStatementOrigin.GT,
            null,
            null,
            lhs,
            rhs
        )
    }

    protected fun irReturn(
        target: IrReturnTargetSymbol,
        value: IrExpression,
        type: IrType = value.type
    ): IrExpression {
        return IrReturnImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            type,
            target,
            value
        )
    }

    protected fun irEqual(lhs: IrExpression, rhs: IrExpression): IrExpression {
        return irCall(
            this.context.irBuiltIns.eqeqeqSymbol,
            null,
            null,
            null,
            lhs,
            rhs
        )
    }

    protected fun irNot(value: IrExpression): IrExpression {
        return irCall(
            context.irBuiltIns.booleanNotSymbol,
            dispatchReceiver = value
        )
    }

    protected fun irNotEqual(lhs: IrExpression, rhs: IrExpression): IrExpression {
        return irNot(irEqual(lhs, rhs))
    }

//        context.irIntrinsics.symbols.intAnd
//        context.irIntrinsics.symbols.getBinaryOperator(name, lhs, rhs)
//        context.irBuiltIns.booleanNotSymbol
//        context.irBuiltIns.eqeqeqSymbol
//        context.irBuiltIns.eqeqSymbol
//        context.irBuiltIns.greaterFunByOperandType

    protected fun irConst(value: Int): IrConst<Int> = IrConstImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        context.irBuiltIns.intType,
        IrConstKind.Int,
        value
    )

    protected fun irConst(value: Long): IrConst<Long> = IrConstImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        context.irBuiltIns.longType,
        IrConstKind.Long,
        value
    )

    protected fun irConst(value: String): IrConst<String> = IrConstImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        context.irBuiltIns.stringType,
        IrConstKind.String,
        value
    )

    protected fun irConst(value: Boolean) = IrConstImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        context.irBuiltIns.booleanType,
        IrConstKind.Boolean,
        value
    )

    protected fun irNull() = IrConstImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        context.irBuiltIns.anyNType,
        IrConstKind.Null,
        null
    )

    @ObsoleteDescriptorBasedAPI
    protected fun irForLoop(
        elementType: IrType,
        subject: IrExpression,
        loopBody: (IrValueDeclaration) -> IrExpression
    ): IrStatement {
        val primitiveType = subject.type.getPrimitiveArrayElementType()
        val iteratorSymbol = primitiveType?.let {
            context.symbols.primitiveIteratorsByType[it]
        } ?: context.symbols.iterator
        val unitType = context.irBuiltIns.unitType

        val getIteratorFunction = subject.type.classOrNull!!.owner.functions
            .single { it.name.asString() == "iterator" }

        if (getIteratorFunction is IrConstructor) {
            throw AssertionError("Should be IrConstructorCall: ${getIteratorFunction.descriptor}")
        }

        val iteratorType = iteratorSymbol.typeWith(elementType)
        val nextSymbol = iteratorSymbol.owner.functions
            .single { it.descriptor.name.asString() == "next" }
        val hasNextSymbol = iteratorSymbol.owner.functions
            .single { it.descriptor.name.asString() == "hasNext" }

        val call = IrCallImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            iteratorType,
            getIteratorFunction.symbol,
            getIteratorFunction.symbol.owner.typeParameters.size,
            getIteratorFunction.symbol.owner.valueParameters.size,
            IrStatementOrigin.FOR_LOOP_ITERATOR
        ).also {
            it.dispatchReceiver = subject
        }

        val iteratorVar = irTemporary(
            value = call,
            isVar = false,
            name = "tmp0_iterator",
            irType = iteratorType,
            origin = IrDeclarationOrigin.FOR_LOOP_ITERATOR
        )
        return irBlock(
            type = unitType,
            origin = IrStatementOrigin.FOR_LOOP,
            statements = listOf(
                iteratorVar,
                IrWhileLoopImpl(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    unitType,
                    IrStatementOrigin.FOR_LOOP_INNER_WHILE
                ).apply {
                    val loopVar = irTemporary(
                        value = IrCallImpl(
                            symbol = nextSymbol.symbol,
                            origin = IrStatementOrigin.FOR_LOOP_NEXT,
                            startOffset = UNDEFINED_OFFSET,
                            endOffset = UNDEFINED_OFFSET,
                            typeArgumentsCount = nextSymbol.symbol.owner.typeParameters.size,
                            valueArgumentsCount = nextSymbol.symbol.owner.valueParameters.size,
                            type = elementType
                        ).also {
                            it.dispatchReceiver = irGet(iteratorVar)
                        },
                        origin = IrDeclarationOrigin.FOR_LOOP_VARIABLE,
                        isVar = false,
                        name = "value",
                        irType = elementType
                    )
                    condition = irCall(
                        symbol = hasNextSymbol.symbol,
                        origin = IrStatementOrigin.FOR_LOOP_HAS_NEXT,
                        dispatchReceiver = irGet(iteratorVar)
                    )
                    body = irBlock(
                        type = unitType,
                        origin = IrStatementOrigin.FOR_LOOP_INNER_WHILE,
                        statements = listOf(
                            loopVar,
                            loopBody(loopVar)
                        )
                    )
                }
            )
        )
    }

    @ObsoleteDescriptorBasedAPI
    protected fun irTemporary(
        value: IrExpression,
        name: String,
        irType: IrType = value.type,
        isVar: Boolean = false,
        origin: IrDeclarationOrigin = IrDeclarationOrigin.IR_TEMPORARY_VARIABLE
    ): IrVariableImpl {
        return IrVariableImpl(
            value.startOffset,
            value.endOffset,
            origin,
            IrVariableSymbolImpl(),
            Name.identifier(name),
            irType,
            isVar,
            false,
            false
        ).apply {
            initializer = value
        }
    }

    protected fun irGet(type: IrType, symbol: IrValueSymbol): IrExpression {
        return IrGetValueImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            type,
            symbol
        )
    }

    protected fun irGet(variable: IrValueDeclaration): IrExpression {
        return irGet(variable.type, variable.symbol)
    }

    protected fun irIf(condition: IrExpression, body: IrExpression): IrExpression {
        return IrIfThenElseImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            context.irBuiltIns.unitType,
            origin = IrStatementOrigin.IF
        ).also {
            it.branches.add(
                IrBranchImpl(condition, body)
            )
        }
    }

    protected fun irIfThenElse(
        type: IrType = context.irBuiltIns.unitType,
        condition: IrExpression,
        thenPart: IrExpression,
        elsePart: IrExpression
    ) =
        IrIfThenElseImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, IrStatementOrigin.IF).apply {
            branches.add(IrBranchImpl(startOffset, endOffset, condition, thenPart))
            branches.add(irElseBranch(elsePart))
        }

    protected fun irWhen(
        type: IrType = context.irBuiltIns.unitType,
        origin: IrStatementOrigin? = null,
        branches: List<IrBranch>
    ) = IrWhenImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        type,
        origin,
        branches
    )
    protected fun irBranch(
        condition: IrExpression,
        result: IrExpression
    ): IrBranch {
        return IrBranchImpl(condition, result)
    }

    protected fun irElseBranch(expression: IrExpression) =
        IrElseBranchImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, irConst(true), expression)

    protected fun irBlock(
        type: IrType = context.irBuiltIns.unitType,
        origin: IrStatementOrigin? = null,
        statements: List<IrStatement>
    ): IrExpression {
        return IrBlockImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            type,
            origin,
            statements
        )
    }

    protected fun irComposite(
        type: IrType = context.irBuiltIns.unitType,
        origin: IrStatementOrigin? = null,
        statements: List<IrStatement>
    ): IrExpression {
        return IrCompositeImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            type,
            origin,
            statements
        )
    }

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    protected fun irLambda(function: IrFunction, type: IrType): IrExpression {
        return irBlock(
            type,
            origin = IrStatementOrigin.LAMBDA,
            statements = listOf(
                function,
                IrFunctionReferenceImpl(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    type,
                    function.symbol,
                    function.typeParameters.size,
                    function.valueParameters.size,
                    null,
                    IrStatementOrigin.LAMBDA
                )
            )
        )
    }

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    fun IrExpression.isStatic(): Boolean {
        return when (this) {
            // A constant by definition is static
            is IrConst<*> -> true
            // We want to consider all enum values as static
            is IrGetEnumValue -> true
            // Getting a companion object or top level object can be considered static if the
            // type of that object is Stable. (`Modifier` for instance is a common example)
            is IrGetObjectValue -> false
            is IrConstructorCall -> isStatic()
            is IrCall -> isStatic()
            is IrGetValue -> {
                val owner = symbol.owner
                when (owner) {
                    is IrVariable -> {
                        // If we have an immutable variable whose initializer is also static,
                        // then we can determine that the variable reference is also static.
                        !owner.isVar && owner.initializer?.isStatic() == true
                    }
                    else -> false
                }
            }
            else -> false
        }
    }

    fun IrConstructorCall.isStatic(): Boolean {
        // special case constructors of inline classes as static if their underlying
        // value is static.
        if (type.isInlined()) {
            return getValueArgument(0)?.isStatic() == true
        }
        return false
    }

    @ObsoleteDescriptorBasedAPI
    fun IrCall.isStatic(): Boolean {
        val function = symbol.owner
        val fqName = function.descriptor.fqNameSafe
        // todo: special case, for instance, listOf(...statics...)
        return when (origin) {
            is IrStatementOrigin.GET_PROPERTY -> {
                // If we are in a GET_PROPERTY call, then this should usually resolve to
                // non-null, but in case it doesn't, just return false
                val prop = (function as? IrSimpleFunction)
                    ?.correspondingPropertySymbol?.owner ?: return false

                // if the property is a top level constant, then it is static.
                if (prop.isConst) return true

                val dispatchReceiverIsStatic = dispatchReceiver?.isStatic() != false
                val extensionReceiverIsStatic = extensionReceiver?.isStatic() != false

                // if we see that the property is read-only with a default getter and a
                // stable return type , then reading the property can also be considered
                // static if this is a top level property or the subject is also static.
                if (!prop.isVar &&
                    prop.getter?.origin == IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR &&
                    dispatchReceiverIsStatic && extensionReceiverIsStatic
                ) {
                    return true
                }

                return dispatchReceiverIsStatic && extensionReceiverIsStatic
            }
            is IrStatementOrigin.PLUS,
            is IrStatementOrigin.MUL,
            is IrStatementOrigin.MINUS,
            is IrStatementOrigin.ANDAND,
            is IrStatementOrigin.OROR,
            is IrStatementOrigin.DIV,
            is IrStatementOrigin.EQ,
            is IrStatementOrigin.EQEQ,
            is IrStatementOrigin.EQEQEQ,
            is IrStatementOrigin.GT,
            is IrStatementOrigin.GTEQ,
            is IrStatementOrigin.LT,
            is IrStatementOrigin.LTEQ -> {
                getArguments().all { it.second.isStatic() }
            }
            null -> {
                // getArguments includes the receivers!
                getArguments().all { it.second.isStatic() }
            }
            else -> false
        }
    }

    protected fun dexSafeName(name: Name): Name {
        return if (
            name.isSpecial || name.asString().contains(unsafeSymbolsRegex)
        ) {
            val sanitized = name
                .asString()
                .replace(unsafeSymbolsRegex, "\\$")
            Name.identifier(sanitized)
        } else name
    }
}

private val unsafeSymbolsRegex = "[ <>]".toRegex()

fun IrFunction.composerParam(): IrValueParameter? {
    for (param in valueParameters.asReversed()) {
        if (param.isComposerParam()) return param
        if (!param.name.asString().startsWith('$')) return null
    }
    return null
}

@OptIn(ObsoleteDescriptorBasedAPI::class)
fun IrValueParameter.isComposerParam(): Boolean =
    @Suppress("DEPRECATION")
    (descriptor as? ValueParameterDescriptor)?.isComposerParam() ?: false

fun ValueParameterDescriptor.isComposerParam(): Boolean =
    name == NameConventions.FRAGMENT_PARAMETER &&
            type.constructor.declarationDescriptor?.fqNameSafe == FragmentFqNames.Fragment

fun IrPluginContext.function(arity: Int): IrClassSymbol =
    referenceClass(FqName("kotlin.Function$arity"))!!

object COMPOSE_STATEMENT_ORIGIN : IrStatementOriginImpl("COMPOSE_STATEMENT_ORIGIN")

@ObsoleteDescriptorBasedAPI
val KotlinType.isFunctionOrKFunctionType: Boolean
    get() {
        val kind = constructor.declarationDescriptor?.getFunctionalClassKind()
        return kind == FunctionClassKind.Function || kind == FunctionClassKind.KFunction
    }

@ObsoleteDescriptorBasedAPI
val DeclarationDescriptorWithSource.startOffset: Int? get() =
    (this.source as? PsiSourceElement)?.psi?.startOffset

@ObsoleteDescriptorBasedAPI
val DeclarationDescriptorWithSource.endOffset: Int? get() =
    (this.source as? PsiSourceElement)?.psi?.endOffset

@ObsoleteDescriptorBasedAPI
fun IrAnnotationContainer.hasAnnotationSafe(fqName: FqName): Boolean =
    annotations.any {
        // compiler helper getAnnotation fails during remapping in [ComposableTypeRemapper], so we
        // use this impl
        fqName == it.annotationClass?.descriptor?.fqNameSafe
    }

// workaround for KT-45361
val IrConstructorCall.annotationClass get() =
    if (type.isUnit()) {
        // in js annotation type is always unit, so we use the constructed class
        symbol.owner.constructedClass.symbol
    } else {
        // on jvm we can rely on type, but the owner can be unbound
        type.classOrNull
    }