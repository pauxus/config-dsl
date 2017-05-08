/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2015-2017 Stephan Pauxberger
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
package com.blackbuild.groovy.configdsl.transform

import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ImportCustomizer
import spock.lang.Specification

import java.lang.reflect.Method

class AbstractDSLSpec extends Specification {

    ClassLoader oldLoader
    GroovyClassLoader loader
    def instance
    Class<?> clazz
    Class<?> rwClazz
    CompilerConfiguration compilerConfiguration

    def setup() {
        oldLoader = Thread.currentThread().contextClassLoader
        def importCustomizer = new ImportCustomizer()
        importCustomizer.addStarImports(
                "com.blackbuild.groovy.configdsl.transform"
        )

        compilerConfiguration = new CompilerConfiguration()
        compilerConfiguration.addCompilationCustomizers(importCustomizer)
        loader = new GroovyClassLoader(Thread.currentThread().getContextClassLoader(), compilerConfiguration)
        Thread.currentThread().contextClassLoader = loader
    }

    def cleanup() {
        Thread.currentThread().contextClassLoader = oldLoader
    }

    def createInstance(String code) {
        createClass(code)
        instance = clazz.newInstance()
    }

    def newInstanceOf(String className) {
        return getClass(className).newInstance()
    }

    def createClass(String code) {
        clazz = loader.parseClass(code)
        rwClazz = getRwClass()
    }

    def createSecondaryClass(String code) {
        return loader.parseClass(code)
    }

    def create(String classname, Closure closure) {
        getClass(classname).create(closure)
    }

    def create(String classname, String key, Closure closure) {
        getClass(classname).create(key, closure)
    }

    def Class<?> getClass(String classname) {
        loader.loadClass(classname)
    }


    protected boolean isDeprecated(Method method) {
        method.getAnnotation(Deprecated) != null
    }

    protected List<Method> allMethodsNamed(String name) {
        clazz.methods.findAll { it.name == name }
    }

    Class getRwClass(String name) {
        getClass(name + '$_RW')
    }

    Class getRwClass() {
        getRwClass(clazz.name)
    }
}
