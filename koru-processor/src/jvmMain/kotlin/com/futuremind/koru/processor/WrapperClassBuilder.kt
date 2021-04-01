package com.futuremind.koru.processor

import com.futuremind.koru.SuspendWrapper
import com.futuremind.koru.FlowWrapper
import com.squareup.kotlinpoet.*


class WrapperClassBuilder(
    originalTypeName: ClassName,
    originalTypeSpec: TypeSpec,
    private val newTypeName: String,
    private val originalToGeneratedInterface: OriginalToGeneratedInterface?,
    private val scopeProviderMemberName: MemberName?
) {

    companion object {
        private const val WRAPPED_PROPERTY_NAME = "wrapped"
    }

    private val constructorSpec = FunSpec.constructorBuilder()
        .addParameter(WRAPPED_PROPERTY_NAME, originalTypeName)
        .build()

    private val wrappedClassPropertySpec =
        PropertySpec.builder(WRAPPED_PROPERTY_NAME, originalTypeName)
            .initializer(WRAPPED_PROPERTY_NAME)
            .addModifiers(KModifier.PRIVATE)
            .build()

    private val functions = originalTypeSpec.funSpecs
        .map { originalFuncSpec ->
            originalFuncSpec.toBuilder(name = originalFuncSpec.name)
                .clearBody()
                .addFunctionBody(originalFuncSpec)
                .addReturnStatement(originalFuncSpec.returnType)
                .apply {
                    modifiers.remove(KModifier.SUSPEND)
                    modifiers.remove(KModifier.ABSTRACT) //when we create class, we always wrap into a concrete impl
                    addMissingOverrideModifier(originalFuncSpec)
                }
                .build()
        }

    //if we have an interface generated based on class signature, we need to add the override modifier to its methods explicitly
    private fun FunSpec.Builder.addMissingOverrideModifier(originalFuncSpec: FunSpec) {

        fun FunSpec.hasSameSignature(other: FunSpec) =
            this.name == other.name && this.parameters == other.parameters

        fun TypeSpec.containsFunctionSignature() =
            this.funSpecs.any { it.hasSameSignature(originalFuncSpec) }

        if (originalToGeneratedInterface?.generated?.typeSpec?.containsFunctionSignature() == true) {
            this.modifiers.add(KModifier.OVERRIDE)
        }
    }

    /**
     * 1. Add all original superinterfaces.
     * 2. (optionally) Replace original superinterface if it's a standalone interface generated by @ToNativeInterface
     * 3. (optionally) Add the superinterface autogenerated from current class with @ToNativeInterface
     */
    private val superInterfaces: MutableList<TypeName> = originalTypeSpec.superinterfaces.keys
        .map { interfaceName ->
            when (originalToGeneratedInterface?.originalName == interfaceName) {
                false -> interfaceName
                true -> originalToGeneratedInterface!!.generated.name
            }
        }
        .toMutableList()
        .apply {
            if (originalTypeName == originalToGeneratedInterface?.originalName) {
                add(originalToGeneratedInterface.generated.name)
            }
        }

    private fun FunSpec.Builder.addFunctionBody(originalFunSpec: FunSpec): FunSpec.Builder = when {
        this.isSuspend -> {
            if (scopeProviderMemberName != null) {
                this.addStatement(
                    "return %T(%M) { ${originalFunSpec.asInvocation()} }",
                    SuspendWrapper::class,
                    scopeProviderMemberName
                )
            } else {
                this.addStatement(
                    "return %T(null) { ${originalFunSpec.asInvocation()} }",
                    SuspendWrapper::class
                )
            }
        }
        originalFunSpec.returnType.isFlow -> {
            if (scopeProviderMemberName != null) {
                this.addStatement(
                    "return %T(%M, ${originalFunSpec.asInvocation()})",
                    FlowWrapper::class,
                    scopeProviderMemberName
                )
            } else {
                this.addStatement(
                    "return %T(null, ${originalFunSpec.asInvocation()})",
                    FlowWrapper::class
                )
            }
        }
        else -> this.addStatement("return ${originalFunSpec.asInvocation()}")
    }

    private fun FunSpec.asInvocation(): String {
        val paramsDeclaration = parameters.joinToString(", ") { it.name }
        return "${WRAPPED_PROPERTY_NAME}.${this.name}($paramsDeclaration)"
    }

    fun build(): TypeSpec = TypeSpec
        .classBuilder(newTypeName)
        .addSuperinterfaces(superInterfaces)
        .primaryConstructor(constructorSpec)
        .addProperty(wrappedClassPropertySpec)
        .addFunctions(functions)
        .build()

}
