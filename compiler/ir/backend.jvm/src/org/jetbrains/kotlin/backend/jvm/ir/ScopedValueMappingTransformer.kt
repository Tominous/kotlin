/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.ir

import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrSetVariable
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrSetVariableImpl
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.IrVariableSymbol
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid

/**
 * Replaces value parameters in GetValue/SetVariable based on a hierarchical mapping between
 * value parameters.
 *
 * This is useful if you copy (part of) a function body to a new function. In this case,
 * we want to update the value parameters in the scope of the new function, without updating
 * the parameters in the scope of the existing function. Using this class allows us to avoid
 * the quadratic overhead of nested calls to, e.g., deepCopyWithSymbols.
 */
open class ScopedValueMappingTransformer : IrElementTransformerVoid() {
    protected val valueMap = ScopedValueDeclarationMap()

    protected inline fun <T> scoped(block: () -> T): T {
        valueMap.push()
        return try {
            block()
        } finally {
            valueMap.pop()
        }
    }

    protected fun addMapping(symbol: IrValueSymbol, declaration: IrValueDeclaration) {
        valueMap[symbol] = declaration
    }

    protected fun addMappings(mappings: Map<out IrValueSymbol, IrValueDeclaration>) =
        valueMap.addAll(mappings)

    override fun visitGetValue(expression: IrGetValue): IrExpression {
        valueMap[expression.symbol]?.let {
            return IrGetValueImpl(
                expression.startOffset, expression.endOffset,
                it.type, it.symbol, expression.origin
            )
        }
        return super.visitGetValue(expression)
    }

    override fun visitSetVariable(expression: IrSetVariable): IrExpression {
        valueMap[expression.symbol]?.let {
            return IrSetVariableImpl(
                expression.startOffset, expression.endOffset,
                it.type, it.symbol as IrVariableSymbol, expression.origin
            ).apply {
                value = expression.value.transform(this@ScopedValueMappingTransformer, null)
            }
        }
        return super.visitSetVariable(expression)
    }
}

class ScopedValueDeclarationMap {
    private val scopeStack = mutableListOf<MutableMap<IrValueSymbol, IrValueDeclaration>>(mutableMapOf())

    operator fun get(symbol: IrValueSymbol): IrValueDeclaration? {
        for (index in scopeStack.indices.reversed()) {
            scopeStack[index][symbol]?.let { return it }
        }
        return null
    }

    operator fun set(symbol: IrValueSymbol, value: IrValueDeclaration) {
        require(scopeStack.isNotEmpty())
        scopeStack.last()[symbol] = value
    }

    fun addAll(mappings: Map<out IrValueSymbol, IrValueDeclaration>) {
        require(scopeStack.isNotEmpty())
        scopeStack.last().putAll(mappings)
    }

    fun push() = scopeStack.add(mutableMapOf())
    fun pop() = scopeStack.pop()
}
