package com.mattworzala.canvas.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.name.FqName

class DebugLogIrGenerationExtension : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val typeAnyNullable = pluginContext.irBuiltIns.anyNType

        val debugLogAnnotation = pluginContext.referenceClass(FqName("DebugLog"))!!
        val funPrintln = pluginContext.referenceFunctions(FqName("kotlin.io.println"))
            .single {
                val parameters = it.owner.valueParameters
                parameters.size == 1 && parameters[0].type == typeAnyNullable
            }

        moduleFragment.transform(DebugLogTransformer(pluginContext, debugLogAnnotation, funPrintln), null)
    }
}