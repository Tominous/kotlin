/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.backend.common.ir.copyTypeParametersFrom
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irBlockBody
import org.jetbrains.kotlin.backend.common.phaser.makeIrFilePhase
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.backend.jvm.JvmLoweredDeclarationOrigin
import org.jetbrains.kotlin.backend.jvm.ir.ScopedValueMappingTransformer
import org.jetbrains.kotlin.backend.jvm.lower.inlineclasses.*
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.buildConstructor
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionReferenceImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

val jvmInlineClassPhase = makeIrFilePhase(
    ::JvmInlineClassLowering,
    name = "Inline Classes",
    description = "Lower inline classes"
)

/**
 * Adds new constructors, box, and unbox functions to inline classes as well as replacement
 * functions and bridges to avoid clashes between overloaded function. Changes calls with
 * known types to call the replacement functions.
 *
 * We do not unfold inline class types here. Instead, the type mapper will lower inline class
 * types to the types of their underlying field.
 */
private class JvmInlineClassLowering(private val context: JvmBackendContext) : FileLoweringPass, ScopedValueMappingTransformer() {
    private val manager = InlineClassManager()

    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid()
    }

    override fun visitClass(declaration: IrClass): IrStatement {
        scoped {
            // The arguments to the primary constructor are in scope in the initializers of IrFields.
            declaration.constructors.singleOrNull { it.isPrimary }?.let { primaryConstructor ->
                manager.getReplacementFunction(primaryConstructor)?.let { replacement ->
                    addMappings(replacement.valueParameterMap)
                }
            }

            declaration.transformDeclarationsFlat { memberDeclaration ->
                when (memberDeclaration) {
                    is IrFunction ->
                        transformFunctionFlat(memberDeclaration)
                    else -> {
                        memberDeclaration.accept(this, null)
                        listOf(memberDeclaration)
                    }
                }
            }

            if (declaration.isInline) {
                val irConstructor = declaration.constructors.single { it.isPrimary }
                declaration.declarations.removeIf {
                    (it is IrConstructor && it.isPrimary) || (it is IrFunction && it.isInlineClassFieldGetter)
                }
                buildPrimaryInlineClassConstructor(declaration, irConstructor)
                buildBoxFunction(declaration)
                buildUnboxFunction(declaration)
            }
        }

        return declaration
    }

    private fun transformFunctionFlat(function: IrFunction): List<IrDeclaration> {
        val replacement = manager.getReplacementFunction(function)
        if (replacement == null) {
            if (function.isPrimaryInlineClassConstructor)
                return listOf(function)

            scoped {
                function.transformChildrenVoid()
            }

            return listOf(function)
        }

        return when (function) {
            is IrSimpleFunction -> transformSimpleFunctionFlat(function, replacement)
            is IrConstructor -> transformConstructorFlat(function, replacement)
            else -> throw IllegalStateException()
        }
    }

    private fun transformSimpleFunctionFlat(function: IrSimpleFunction, replacement: IrReplacementFunction): List<IrDeclaration> {
        val worker = replacement.function
        scoped {
            addMappings(replacement.valueParameterMap)
            worker.valueParameters.forEach { it.transformChildrenVoid() }
            worker.body = function.body?.transform(this, null)?.patchDeclarationParents(worker)
        }

        // Don't create a wrapper for functions which are only used in an unboxed context
        if (function.overriddenSymbols.isEmpty() || worker.dispatchReceiverParameter != null)
            return listOf(worker)

        // Replace the function body with a wrapper
        scoped {
            context.createIrBuilder(function.symbol).run {
                val fullParameterList =
                    listOfNotNull(function.dispatchReceiverParameter, function.extensionReceiverParameter) + function.valueParameters

                val call = irCall(worker).apply {
                    function.typeParameters.forEach { putTypeArgument(it.index, it.defaultType) }
                    for (parameter in fullParameterList) {
                        val newParameter = replacement.valueParameterMap[parameter.symbol] ?: continue
                        putArgument(newParameter, irGet(parameter))
                    }
                }

                function.body = irExprBody(call)
            }
        }

        return listOf(worker, function)
    }

    // Secondary constructors for boxed types get translated to static functions returning
    // unboxed arguments. We remove the original constructor.
    private fun transformConstructorFlat(constructor: IrConstructor, replacement: IrReplacementFunction): List<IrDeclaration> {
        val worker = replacement.function

        scoped {
            addMappings(replacement.valueParameterMap)
            worker.valueParameters.forEach { it.transformChildrenVoid() }
            worker.body = context.createIrBuilder(worker.symbol).irBlockBody(worker) {
                val thisVar = irTemporaryVarDeclaration(
                    worker.returnType, nameHint = "\$this", isMutable = false
                )
                addMapping(constructor.constructedClass.thisReceiver!!.symbol, thisVar)

                constructor.body?.contents?.forEach { statement ->
                    +statement
                        .transform(object : IrElementTransformerVoid() {
                            // Don't recurse under nested class declarations
                            override fun visitClass(declaration: IrClass): IrStatement {
                                return declaration
                            }

                            // Capture the result of a delegating constructor call in a temporary variable "thisVar".
                            //
                            // Within the constructor we replace references to "this" with references to "thisVar".
                            // This is safe, since the delegating constructor call precedes all references to "this".
                            override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall): IrExpression {
                                expression.transformChildrenVoid()
                                return irSetVar(thisVar.symbol, expression)
                            }

                            // A constructor body has type unit and may contain explicit return statements.
                            // These early returns may have side-effects however, so we still have to evaluate
                            // the return expression. Afterwards we return "thisVar".
                            // For example, the following is a valid inline class declaration.
                            //
                            //     inline class Foo(val x: String) {
                            //       constructor(y: Int) : this(y.toString()) {
                            //         if (y == 0) return throw java.lang.IllegalArgumentException()
                            //         if (y == 1) return
                            //         return Unit
                            //       }
                            //     }
                            override fun visitReturn(expression: IrReturn): IrExpression {
                                expression.transformChildrenVoid()
                                if (expression.returnTargetSymbol != constructor.symbol)
                                    return expression

                                return irReturn(irBlock(expression.startOffset, expression.endOffset) {
                                    +expression.value
                                    +irGet(thisVar)
                                })
                            }
                        }, null)
                        .transform(this@JvmInlineClassLowering, null)
                        .patchDeclarationParents(worker)
                }

                +irReturn(irGet(thisVar))
            }
        }

        return listOf(worker)
    }

    private fun typedArgumentList(function: IrFunction, expression: IrMemberAccessExpression) =
        listOfNotNull(
            function.dispatchReceiverParameter?.let { it to expression.dispatchReceiver },
            function.extensionReceiverParameter?.let { it to expression.extensionReceiver }
        ) + function.valueParameters.map { it to expression.getValueArgument(it.index) }

    private fun IrMemberAccessExpression.buildReplacement(
        originalFunction: IrFunction,
        original: IrMemberAccessExpression,
        replacement: IrReplacementFunction
    ) {
        copyTypeArgumentsFrom(original)
        for ((parameter, argument) in typedArgumentList(originalFunction, original)) {
            if (argument == null) continue
            val newParameter = replacement.valueParameterMap[parameter.symbol] ?: continue
            putArgument(
                replacement.function,
                newParameter,
                argument.transform(this@JvmInlineClassLowering, null)
            )
        }
    }

    private fun buildReplacementCall(
        originalFunction: IrFunction,
        original: IrFunctionAccessExpression,
        replacement: IrReplacementFunction
    ) = context.createIrBuilder(original.symbol)
        .irCall(replacement.function)
        .apply { buildReplacement(originalFunction, original, replacement) }

    override fun visitFunctionReference(expression: IrFunctionReference): IrExpression {
        val replacement = manager.getReplacementFunction(expression.symbol.owner)
            ?: return super.visitFunctionReference(expression)
        val function = replacement.function

        return IrFunctionReferenceImpl(
            expression.startOffset, expression.endOffset, expression.type,
            function.symbol, function.descriptor,
            function.typeParameters.size, function.valueParameters.size,
            expression.origin
        ).apply {
            buildReplacement(expression.symbol.owner, expression, replacement)
        }
    }

    override fun visitFunctionAccess(expression: IrFunctionAccessExpression): IrExpression {
        val function = expression.symbol.owner
        val replacement = manager.getReplacementFunction(function)
            ?: return super.visitFunctionAccess(expression)
        return buildReplacementCall(function, expression, replacement)
    }

    private fun coerceInlineClasses(argument: IrExpression, from: IrType, to: IrType) =
        IrCallImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, to, context.ir.symbols.unsafeCoerceIntrinsicSymbol).apply {
            putTypeArgument(0, from)
            putTypeArgument(1, to)
            putValueArgument(0, argument)
        }

    private fun visitPrimaryInlineClassConstructorCall(expression: IrMemberAccessExpression, constructor: IrConstructor): IrExpression {
        val parameter = constructor.valueParameters.single()
        val arg = expression.getValueArgument(0) ?: parameter.defaultValue!!.expression.deepCopyWithSymbols()
        return coerceInlineClasses(arg.transform(this, null), parameter.type, constructor.returnType)
    }

    override fun visitConstructorCall(expression: IrConstructorCall): IrExpression {
        val constructor = expression.symbol.owner
        if (constructor.isPrimaryInlineClassConstructor)
            return visitPrimaryInlineClassConstructorCall(expression, constructor)
        return super.visitConstructorCall(expression)
    }

    override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall): IrExpression {
        val constructor = expression.symbol.owner
        if (constructor.isPrimaryInlineClassConstructor)
            return visitPrimaryInlineClassConstructorCall(expression, constructor)
        return super.visitDelegatingConstructorCall(expression)
    }

    override fun visitCall(expression: IrCall): IrExpression {
        val function = expression.symbol.owner
        if (!function.isInlineClassFieldGetter)
            return super.visitCall(expression)
        val arg = expression.dispatchReceiver!!.transform(this, null)
        return coerceInlineClasses(arg, function.dispatchReceiverParameter!!.type, expression.type)
    }

    override fun visitGetField(expression: IrGetField): IrExpression {
        val field = expression.symbol.owner
        val parent = field.parent
        if (parent is IrClass && parent.isInline && field.name == parent.inlineClassFieldName) {
            val receiver = expression.receiver!!.transform(this, null)
            return coerceInlineClasses(receiver, receiver.type, field.type)
        }
        return super.visitGetField(expression)
    }

    override fun visitReturn(expression: IrReturn): IrExpression {
        expression.returnTargetSymbol.owner.safeAs<IrFunction>()?.let { target ->
            manager.getReplacementFunction(target)?.let {
                return context.createIrBuilder(it.function.symbol).irReturn(
                    expression.value.transform(this, null)
                )
            }
        }
        return super.visitReturn(expression)
    }

    private fun visitStatementContainer(container: IrStatementContainer) {
        container.statements.transformFlat { statement ->
            if (statement is IrFunction)
                transformFunctionFlat(statement)
            else
                listOf(statement.transform(this, null))
        }
    }

    override fun visitContainerExpression(expression: IrContainerExpression): IrExpression {
        visitStatementContainer(expression)
        return expression
    }

    override fun visitBlockBody(body: IrBlockBody): IrBody {
        visitStatementContainer(body)
        return body
    }

    override fun visitLocalDelegatedProperty(declaration: IrLocalDelegatedProperty): IrStatement {
        declaration.delegate.transformChildrenVoid()
        declaration.getter = transformFunctionFlat(declaration.getter)[0] as IrFunction
        declaration.setter?.let { setter ->
            declaration.setter = transformFunctionFlat(setter)[0] as IrFunction
        }
        return declaration
    }

    private fun buildPrimaryInlineClassConstructor(irClass: IrClass, irConstructor: IrConstructor) {
        irClass.declarations += buildConstructor {
            updateFrom(irConstructor)
            visibility = Visibilities.PRIVATE
            origin = JvmLoweredDeclarationOrigin.SYNTHETIC_INLINE_CLASS_MEMBER
            returnType = irConstructor.returnType
        }.apply {
            parent = irClass
            copyTypeParametersFrom(irConstructor)
            valueParameters += irConstructor.valueParameters.map { p ->
                p.copyTo(this)
            }
            body = context.createIrBuilder(this.symbol).irBlockBody(this) {
                +irDelegatingConstructorCall(context.irBuiltIns.anyClass.owner.constructors.single())
                +irSetField(
                    irGet(irClass.thisReceiver!!),
                    getInlineClassBackingField(irClass),
                    irGet(this@apply.valueParameters[0])
                )
            }
        }
    }

    private fun buildBoxFunction(irClass: IrClass) {
        val function = manager.getBoxFunction(irClass)
        val builder = context.createIrBuilder(function.symbol)
        val constructor = irClass.constructors.find { it.isPrimary }!!

        function.body = builder.irBlockBody(function) {
            val valueToBox = function.valueParameters[0]
            +irReturn(irCall(constructor.symbol).apply {
                function.typeParameters.forEach { putTypeArgument(it.index, it.defaultType) }
                putValueArgument(0, irGet(valueToBox))
            })
        }

        irClass.declarations += function
    }

    private fun buildUnboxFunction(irClass: IrClass) {
        val function = manager.getUnboxFunction(irClass)
        val builder = context.createIrBuilder(function.symbol)
        val field = getInlineClassBackingField(irClass)

        function.body = builder.irBlockBody {
            val thisVal = irGet(function.dispatchReceiverParameter!!)
            +irReturn(irGetField(thisVal, field))
        }

        irClass.declarations += function
    }
}

private val IrBody.contents: List<IrStatement>
    get() = when (this) {
        is IrBlockBody -> statements
        is IrExpressionBody -> listOf(expression)
        is IrSyntheticBody -> error("Synthetic body contains no statements $this")
        else -> throw IllegalStateException()
    }
