/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.resolve.checkers

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassifierDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.calls.checkers.CallChecker
import org.jetbrains.kotlin.resolve.calls.checkers.CallCheckerContext
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.constants.ArrayValue
import org.jetbrains.kotlin.resolve.constants.EnumValue
import org.jetbrains.kotlin.resolve.constants.KClassValue
import org.jetbrains.kotlin.resolve.descriptorUtil.annotationClass
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.utils.SmartSet
import org.jetbrains.kotlin.utils.addIfNotNull

object ExperimentalUsageChecker : CallChecker {
    private val EXPERIMENTAL_FQ_NAME = FqName("kotlin.Experimental")
    private val USE_EXPERIMENTAL_FQ_NAME = FqName("kotlin.UseExperimental")
    private val USE_EXPERIMENTAL_ANNOTATION_CLASS = Name.identifier("markerClass")

    private val LEVEL = Name.identifier("level")
    private val WARNING_LEVEL = Name.identifier("WARNING")
    private val ERROR_LEVEL = Name.identifier("ERROR")

    private val IMPACT = Name.identifier("changesMayBreak")
    private val COMPILATION_IMPACT = Name.identifier("COMPILATION")
    private val LINKAGE_IMPACT = Name.identifier("LINKAGE")
    private val RUNTIME_IMPACT = Name.identifier("RUNTIME")

    private data class Experimentality(val annotationFqName: FqName, val severity: Severity, val impact: List<Impact>) {
        val isCompilationOnly: Boolean get() = impact.all(Impact.COMPILATION::equals)

        enum class Severity { WARNING, ERROR }
        enum class Impact { COMPILATION, LINKAGE_OR_RUNTIME }

        companion object {
            val DEFAULT_SEVERITY = Severity.ERROR
            val DEFAULT_IMPACT = listOf(Impact.COMPILATION, Impact.LINKAGE_OR_RUNTIME)
        }
    }

    override fun check(resolvedCall: ResolvedCall<*>, reportOn: PsiElement, context: CallCheckerContext) {
        checkExperimental(resolvedCall.resultingDescriptor, reportOn, context.trace)
    }

    private fun checkExperimental(descriptor: DeclarationDescriptor, element: PsiElement, trace: BindingTrace) {
        for (experimentality in descriptor.loadExperimentalities()) {
            val (annotationFqName, severity, impact) = experimentality
            val isBodyUsageOfCompilationExperimentality =
                experimentality.isCompilationOnly && element.isBodyUsage()

            val isExperimentalityAccepted =
                    (isBodyUsageOfCompilationExperimentality &&
                     element.hasContainerAnnotatedWithUseExperimental(annotationFqName, trace.bindingContext)) ||
                    element.propagates(annotationFqName, trace.bindingContext)

            if (!isExperimentalityAccepted) {
                val diagnostic = when (severity) {
                    ExperimentalUsageChecker.Experimentality.Severity.WARNING -> Errors.EXPERIMENTAL_API_USAGE
                    ExperimentalUsageChecker.Experimentality.Severity.ERROR -> Errors.EXPERIMENTAL_API_USAGE_ERROR
                }
                trace.report(diagnostic.on(element, annotationFqName, isBodyUsageOfCompilationExperimentality))
            }
        }
    }

    private fun DeclarationDescriptor.loadExperimentalities(): Set<Experimentality> {
        val result = SmartSet.create<Experimentality>()

        for (annotation in annotations) {
            result.addIfNotNull(annotation.loadExperimentalityForMarkerAnnotation())
        }

        val container = containingDeclaration
        if (container is ClassDescriptor && this !is ConstructorDescriptor) {
            for (annotation in container.annotations) {
                result.addIfNotNull(annotation.loadExperimentalityForMarkerAnnotation())
            }
        }

        return result
    }

    private fun AnnotationDescriptor.loadExperimentalityForMarkerAnnotation(): Experimentality? {
        val experimental = annotationClass?.annotations?.findAnnotation(EXPERIMENTAL_FQ_NAME) ?: return null
        val annotationFqName = fqName ?: return null

        val severity = when ((experimental.allValueArguments[LEVEL] as? EnumValue)?.enumEntryName) {
            WARNING_LEVEL -> Experimentality.Severity.WARNING
            ERROR_LEVEL -> Experimentality.Severity.ERROR
            else -> Experimentality.DEFAULT_SEVERITY
        }

        val impact = (experimental.allValueArguments[IMPACT] as? ArrayValue)?.value?.mapNotNull { impact ->
            when ((impact as? EnumValue)?.enumEntryName) {
                COMPILATION_IMPACT -> Experimentality.Impact.COMPILATION
                LINKAGE_IMPACT, RUNTIME_IMPACT -> Experimentality.Impact.LINKAGE_OR_RUNTIME
                else -> null
            }
        } ?: Experimentality.DEFAULT_IMPACT

        return Experimentality(annotationFqName, severity, impact)
    }

    // Returns true if this element appears in the body of some function and is not visible in any non-local declaration signature.
    // If that's the case, one can opt-in to using the corresponding experimental API by annotating this element (or any of its
    // enclosing declarations) with @UseExperimental(X::class), not requiring propagation of the experimental annotation to the call sites.
    // (Note that this is allowed only if X's impact is [COMPILATION].)
    private fun PsiElement.isBodyUsage(): Boolean {
        return anyParentMatches { element, parent ->
            element == (parent as? KtDeclarationWithBody)?.bodyExpression ||
            element == (parent as? KtDeclarationWithInitializer)?.initializer ||
            element == (parent as? KtClassInitializer)?.body ||
            element == (parent as? KtParameter)?.defaultValue ||
            element == (parent as? KtSuperTypeCallEntry)?.valueArgumentList ||
            element == (parent as? KtDelegatedSuperTypeEntry)?.delegateExpression ||
            element == (parent as? KtPropertyDelegate)?.expression
        }
    }

    // Checks whether any of the non-local enclosing declarations is annotated with annotationFqName, effectively requiring
    // propagation for the experimental annotation to the call sites
    private fun PsiElement.propagates(annotationFqName: FqName, bindingContext: BindingContext): Boolean {
        return anyParentMatches { element, _ ->
            if (element is KtDeclaration) {
                val descriptor = bindingContext.get(BindingContext.DECLARATION_TO_DESCRIPTOR, element)
                descriptor != null && !DescriptorUtils.isLocal(descriptor) && descriptor.annotations.hasAnnotation(annotationFqName)
            } else false
        }
    }

    // Checks whether there's an element lexically above the tree, that is annotated with `@UseExperimental(X::class)`
    // where annotationFqName is the FQ name of X
    private fun PsiElement.hasContainerAnnotatedWithUseExperimental(annotationFqName: FqName, bindingContext: BindingContext): Boolean {
        return anyParentMatches { element, _ ->
            element is KtAnnotated && element.annotationEntries.any { entry ->
                bindingContext.get(BindingContext.ANNOTATION, entry)?.isUseExperimental(annotationFqName) == true
            }
        }
    }

    private inline fun PsiElement.anyParentMatches(predicate: (element: PsiElement, parent: PsiElement?) -> Boolean): Boolean {
        var element = this
        while (true) {
            val parent = element.parent
            if (predicate(element, parent)) return true
            element = parent ?: return false
        }
    }

    private fun AnnotationDescriptor.isUseExperimental(annotationFqName: FqName): Boolean {
        if (fqName != USE_EXPERIMENTAL_FQ_NAME) return false

        val annotationClasses = allValueArguments[USE_EXPERIMENTAL_ANNOTATION_CLASS]
        return annotationClasses is ArrayValue && annotationClasses.value.any { annotationClass ->
            (annotationClass as? KClassValue)?.value?.constructor?.declarationDescriptor?.fqNameSafe == annotationFqName
        }
    }

    object ClassifierUsage : ClassifierUsageChecker {
        override fun check(targetDescriptor: ClassifierDescriptor, element: PsiElement, context: ClassifierUsageCheckerContext) {
            checkExperimental(targetDescriptor, element, context.trace)
        }
    }
}
