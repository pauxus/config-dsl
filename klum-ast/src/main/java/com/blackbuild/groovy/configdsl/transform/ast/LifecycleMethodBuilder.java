/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2015-2019 Stephan Pauxberger
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.blackbuild.groovy.configdsl.transform.ast;

import com.blackbuild.klum.common.CommonAstHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.control.SourceUnit;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static com.blackbuild.groovy.configdsl.transform.ast.DslAstHelper.hasAnnotation;
import static com.blackbuild.groovy.configdsl.transform.ast.DslAstHelper.moveMethodFromModelToRWClass;
import static groovyjarjarasm.asm.Opcodes.ACC_PROTECTED;
import static groovyjarjarasm.asm.Opcodes.ACC_PUBLIC;

/**
 * Helper class to create lifecycle methods for a given annotation
 */
class LifecycleMethodBuilder {
    private final ClassNode annotationType;
    private final ClassNode annotatedClass;
    private final SourceUnit sourceUnit;

    LifecycleMethodBuilder(ClassNode annotatedClass, ClassNode annotationType) {
        this.annotationType = annotationType;
        this.annotatedClass = annotatedClass;
        this.sourceUnit = annotatedClass.getModule().getContext();
    }

    void invoke() {
        getAllValidLifecycleMethods(annotatedClass).forEach(this::moveMethodToRwClass);
    }

    private void moveMethodToRwClass(MethodNode method) {
        moveMethodFromModelToRWClass(method);
        int modifiers = method.getModifiers() & ~ACC_PUBLIC | ACC_PROTECTED;
        method.setModifiers(modifiers);
    }

    private List<MethodNode> getAllValidLifecycleMethods(ClassNode level) {
        if (level == null)
            return Collections.emptyList();

        List<MethodNode> lifecycleMethods = level.getMethods().stream().filter(method -> hasAnnotation(method, annotationType)).collect(Collectors.toList());
        lifecycleMethods.forEach(this::assertMethodIsValidLifecyleMethod);
        return lifecycleMethods;
    }

    private void assertMethodIsValidLifecyleMethod(MethodNode method) {
        CommonAstHelper.assertMethodIsParameterless(method, sourceUnit);
        CommonAstHelper.assertMethodIsNotPrivate(method, sourceUnit);
    }

}