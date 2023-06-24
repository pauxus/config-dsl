/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2015-2023 Stephan Pauxberger
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
package com.blackbuild.klum.ast.util;

import groovy.lang.Closure;
import groovy.lang.GroovyObject;

import java.util.Map;

@SuppressWarnings("java:S100")
public class KlumUnkeyedFactory<T extends GroovyObject> extends KlumFactory<T> {
    public KlumUnkeyedFactory(Class<T> type) {
        super(DslHelper.requireNotKeyed(type));
    }

    public T One() {
        return With(null, null);
    }

    /**
     * Convenience methods to allow simply replacing 'X.create' with 'X.Create.With' in scripts, without
     * checking for arguments. This means that emtpy create calls like 'X.create()' will correctly work afterwards.
     * @deprecated Use {@link #One()} instead.
     */
    @Deprecated
    public T With() {
        return One();
    }

    public T With(Closure<?> body) {
        return With(null, body);
    }

    public T With(Map<String, Object> values) {
        return With(values, null);
    }

    public T With(Map<String, Object> values, Closure<?> body) {
        return FactoryHelper.create(type, values, null, body);
    }


}
