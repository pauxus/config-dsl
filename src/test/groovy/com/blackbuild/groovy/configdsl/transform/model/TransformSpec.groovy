package com.blackbuild.groovy.configdsl.transform.model

import org.codehaus.groovy.control.MultipleCompilationErrorsException

import java.lang.reflect.Method

@SuppressWarnings("GroovyAssignabilityCheck")
class TransformSpec extends AbstractDSLSpec {

    def "apply method is created"() {
        when:
        createClass('''
            package pk

            @DSL
            class Foo {
            }
        ''')

        then:
        clazz.metaClass.getMetaMethod("apply", Closure) != null
    }

    def "create _apply method when apply exists"() {
        given:
        createInstance('''
            package pk

            @DSL
            class Foo {
                boolean isCalled
                Foo apply(Closure c) {
                    isCalled = true
                    _apply(c)
                }
            }
        ''')

        when:
        instance.apply {}

        then:
        instance.isCalled == true
    }

    def "if apply and _apply exits, do nothing"() {
        when:
        createInstance('''
            package pk

            @DSL
            class Foo {
                Foo apply(Closure c) { this }
                Foo  _apply(Closure c) { this }
            }
        ''')

        then:
        noExceptionThrown()
    }

    def "factory methods should be created"() {
        given:
        createClass('''
            package pk

            @DSL
            class Foo {
            }
        ''')

        when:
        instance = clazz.create() {}

        then:
        instance.class.name == "pk.Foo"
    }

    def "factory methods with existing factories"() {
        given:
        createClass('''
            package pk

            @DSL
            class Foo {

                boolean called

                def static create(Closure c) {
                    def foo = _create(c)
                    foo.called = true
                    foo
                }
            }
        ''')

        when:
        instance = clazz.create() {}

        then:
        instance.called
    }

    def "factory methods with key"() {
        given:
        createClass('''
            package pk

            @DSL
            class Foo {
                @Key String name
            }
        ''')

        when:
        instance = clazz.create("Dieter") {}

        then:
        instance.name == "Dieter"

        and: "no name() accessor is created"
        instance.class.metaClass.getMetaMethod("name", String) == null
    }

    def "factory methods with key and existing factory"() {
        given:
        createClass('''
            package pk

            @DSL
            class Foo {
                @Key String name
                boolean called

                def static create(String key, Closure c) {
                    def foo = _create(key, c)
                    foo.called = true
                    foo
                }
            }
        ''')

        when:
        instance = clazz.create("Klaus") {}

        then:
        instance.called
    }

    def "factory method and _create methods already exist"() {
        when:
        createClass('''
            package pk

            @DSL
            class Foo {
                def static create(Closure c) {
                    _create(c)
                }
                def static _create(Closure c) {
                    new Foo()
                }
            }
        ''')

        then:
        noExceptionThrown()

        when:
        createClass('''
            package pk

            @DSL
            class Foo {
                String name
                def static create(String key, Closure c) {
                    _create(key, c)
                }
                def static _create(String key, Closure c) {
                    new Foo(key)
                }
            }
        ''')

        then:
        noExceptionThrown()
    }

    def "constructor is created for keyed object"() {
        given:
        createClass('''
            package pk

            @DSL
            class Foo {
                @Key String name
            }
        ''')

        when:
        instance = clazz.newInstance("Klaus")

        then:
        noExceptionThrown()
        instance.name == "Klaus"
    }

    def "key field must be of type String"() {
        when:
        createClass('''
            package pk

            @DSL
            class Foo {
                @Key int name
            }
        ''')

        then:
        thrown(MultipleCompilationErrorsException)
    }

    def "simple member method"() {
        given:
        createInstance('''
            @DSL
            class Foo {
                String value
            }
        ''')

        when:
        instance.value "Dieter"

        then:
        instance.value == "Dieter"
    }

    def "simple member method for reusable config objects"() {
        given:
        createClass('''
            package pk

            @DSL
            class Foo {
                Bar inner
            }

            @DSL
            class Bar {
                String name
            }
        ''')

        when:
        def bar = create("pk.Bar") {
            name = "Dieter"
        }
        instance = create("pk.Foo") {
            inner bar
        }

        then:
        instance.inner.name == "Dieter"
    }

    def "simple member method with renaming annotation"() {
        given:
        createInstance('''
            @DSL
            class Foo {
                @Field("firstname") String name
                String lastname
            }
        ''')

        when:
        instance.firstname "Dieter"

        then:
        instance.name == "Dieter"
    }
    def "test existing method"() {
        given:
        createInstance('''
            @DSL
            class Foo {
                String name, lastname
                def name(String value) {return "run"}
            }
        ''')

        expect: "Original method is called"
        instance.name("Dieter") == "run"
    }

    def "test existing method with renaming"() {
        given:
        createInstance('''
            @DSL
            class Foo {
                @Field("firstname") String name
                String lastname
                def firstname(String value) {return "run"}
            }
        ''')

        expect: "Original method is called"
        instance.firstname("Dieter") == "run"
    }

    def "create inner object via closure"() {
        given:
        createInstance('''
            package pk

            @DSL
            class Foo {
                Bar inner
            }

            @DSL
            class Bar {
                String name
            }
        ''')

        when:
        def inner = instance.inner {
            name "Dieter"
        }

        then:
        instance.inner.name == "Dieter"

        and: "object should be returned by closure"
        inner != null
    }

    def "create inner object via key and closure"() {
        given:
        createInstance('''
            package pk

            @DSL
            class Foo {
                Bar inner
            }

            @DSL
            class Bar {
                @Key String name
                int value
            }
        ''')

        when:
        def inner = instance.inner("Dieter") {
            value 15
        }

        then:
        instance.inner.name == "Dieter"
        instance.inner.value == 15

        and: "object should be returned by closure"
        inner != null
    }

    def "create list of inner objects"() {
        given:
        createInstance('''
            package pk

            @DSL
            class Foo {
                List<Bar> bars
            }

            @DSL
            class Bar {
                String name
            }
        ''')

        when:
        instance.bars {
            bar { name "Dieter" }
            bar { name "Klaus"}
        }

        then:
        instance.bars[0].name == "Dieter"
        instance.bars[1].name == "Klaus"
    }

    @SuppressWarnings("GroovyVariableNotAssigned")
    def "inner list objects closure should return the object"() {
        given:
        createInstance('''
            package pk

            @DSL
            class Foo {
                List<Bar> bars
            }

            @DSL
            class Bar {
                String name
            }
        ''')

        when:
        def bar1
        def bar2
        instance.bars {
            bar1 = bar { name "Dieter" }
            bar2 = bar { name "Klaus"}
        }

        then:
        bar1.name == "Dieter"
        bar2.name == "Klaus"
    }

    def "create list of named inner objects"() {
        given:
        createInstance('''
            package pk

            @DSL
            class Foo {
                List<Bar> bars
            }

            @DSL
            class Bar {
                @Key String name
                String url
            }
        ''')

        when:
        instance.bars {
            bar("Dieter") { url "1" }
            bar("Klaus") { url "2" }
        }

        then:
        instance.bars[0].name == "Dieter"
        instance.bars[0].url == "1"
        instance.bars[1].name == "Klaus"
        instance.bars[1].url == "2"
    }

    @SuppressWarnings("GroovyVariableNotAssigned")
    def "inner list objects closure with named objects should return the created object"() {
        given:
        createInstance('''
            package pk

            @DSL
            class Foo {
                List<Bar> bars
            }

            @DSL
            class Bar {
                @Key String name
                String url
            }
        ''')

        when:
        def bar1
        def bar2
        instance.bars {
            bar1 = bar("Dieter") { url "1" }
            bar2 = bar("Klaus") { url "2" }
        }

        then:
        bar1.name == "Dieter"
        bar2.name == "Klaus"
    }

    def "create list of named inner objects using name method"() {
        given:
        createInstance('''
            package pk

            @DSL
            class Foo {
                List<Bar> bars
            }

            @DSL
            class Bar {
                @Key String name
                String url
            }
        ''')

        when:
        instance.bars {
            "Dieter" { url "1" }
            "Klaus" { url "2" }
        }

        then:
        instance.bars[0].name == "Dieter"
        instance.bars[0].url == "1"
        instance.bars[1].name == "Klaus"
        instance.bars[1].url == "2"
    }

    def "Bug: DSLField without value leads to NPE"() {
        when:
        createInstance('''
            package pk

            @DSL
            class Foo {
                @Field
                String name
            }
        ''')

        then:
        noExceptionThrown()
    }

    def "collections gets initial values"() {
        when:
        createInstance('''
            package pk

            @DSL
            class Foo {
                List<String> values
                Map<String, String> fields
            }
        ''')

        then:
        instance.values != null
        instance.values == []

        and:
        instance.fields != null
        instance.fields == [:]
    }

    def "existing initial values are not overriden"() {
        when:
        createInstance('''
            package pk

            @DSL
            class Foo {
                List<String> values = ['Bla']
                Map<String, String> fields = [bla: "blub"]
            }
        ''')

        then:
        instance.values == ["Bla"]

        and:
        instance.fields == [bla: "blub"]
    }

    def "simple list element"() {
        given:
        createInstance('''
            package pk

            @DSL
            class Foo {
                List<String> values
            }
        ''')

        when:"add using list add"
        instance.values "Dieter", "Klaus"

        then:
        instance.values == ["Dieter", "Klaus"]

        when:"add using list add again"
        instance.values "Heinz"

        then:"second call should add to previous values"
        instance.values == ["Dieter", "Klaus", "Heinz"]

        when:"add using single method"
        instance.value "singleadd"

        then:
        instance.values == ["Dieter", "Klaus", "Heinz", "singleadd"]
    }

    def "simple list element with different element name"() {
        given:
        createInstance('''
            package pk

            @DSL
            class Foo {
                @Field(element="more")
                List<String> values
            }
        ''')

        when:
        instance.values "Dieter", "Klaus"
        instance.more "Heinz"

        then:
        instance.values == ["Dieter", "Klaus", "Heinz"]
    }

    def "with simple list element with singular name, element and group list methods have the same name"() {
        given:
        createInstance('''
            package pk

            @DSL
            class Foo {
                List<String> something
            }
        ''')

        when:
        instance.something "Dieter", "Klaus" // list adder
        instance.something "Heinz" // single added

        then:
        instance.something == ["Dieter", "Klaus", "Heinz"]
    }

    def "List field without generics throws exception"() {
        when:
        createInstance('''
            package pk

            @DSL
            class Foo {
                List values
            }
        ''')

        then:
        thrown(MultipleCompilationErrorsException)
    }

    def "simple map element"() {
        given:
        createInstance('''
            package pk

            @DSL
            class Foo {
                Map<String, String> values
            }
        ''')

        when:
        instance.values name:"Dieter", time:"Klaus", "val bri":"bri"

        then:
        instance.values == [name:"Dieter", time:"Klaus", "val bri":"bri"]

        when:
        instance.values name:"Maier", age:"15"

        then:
        instance.values == [name:"Maier", time:"Klaus", "val bri":"bri", age: "15"]

        when:
        instance.value("height", "14")

        then:
        instance.values == [name:"Maier", time:"Klaus", "val bri":"bri", age: "15", height: "14"]
    }

    def "map of inner objects without keys throws exception"() {
        when:
        createInstance('''
            package pk

            @DSL
            class Foo {
                Map<String, Bar> bars
            }

            @DSL
            class Bar {
                String name
            }
        ''')

        then:
        thrown(MultipleCompilationErrorsException)
    }

    def "create map of inner objects"() {
        given:
        createInstance('''
            package pk

            @DSL
            class Foo {
                Map<String, Bar> bars
            }

            @DSL
            class Bar {
                @Key String name
                String url
            }
        ''')

        when:
        instance.bars {
            bar("Dieter") { url "1" }
            bar("Klaus") { url "2" }
        }

        then:
        instance.bars.Dieter.url == "1"
        instance.bars.Klaus.url == "2"
    }

    @SuppressWarnings("GroovyVariableNotAssigned")
    def "creation of inner objects in map should return the create object"() {
        given:
        createInstance('''
            package pk

            @DSL
            class Foo {
                Map<String, Bar> bars
            }

            @DSL
            class Bar {
                @Key String name
                String url
            }
        ''')

        when:
        def bar1
        def bar2
        instance.bars {
            bar1 = bar("Dieter") { url "1" }
            bar2 = bar("Klaus") { url "2" }
        }

        then:
        bar1.url == "1"
        bar2.url == "2"
    }

    def "reusing of objects in closure"() {
        given:
        createInstance('''
            package pk

            @DSL
            class Foo {
                List<Bar> bars
            }

            @DSL
            class Bar {
                String url
            }
        ''')
        def aBar = create("pk.Bar") {
            url "welt"
        }

        when:
        instance.bars {
            _reuse(aBar)
        }

        then:
        instance.bars[0].url == "welt"

    }

    def "reusing of map objects in closure"() {
        given:
        createInstance('''
            package pk

            @DSL
            class Foo {
                Map<String, Bar> bars
            }

            @DSL
            class Bar {
                @Key String name
                String url
            }
        ''')
        def aBar = create("pk.Bar", "klaus") {
            url "welt"
        }

        when:
        instance.bars {
            _reuse(aBar)
        }

        then:
        instance.bars.klaus.url == "welt"
    }

    def "equals, hashcode and toString methods are created"() {
        when:
        createClass('''
            package pk

            @DSL
            class Foo {
                String name
            }
        ''')

        then:
        clazz.declaredMethods.find { Method method -> method.name == "toString"}
    }

    def "Bug: toString() with owner field throws StackOverflowError"() {
        given:
        createClass('''
            package pk

            @DSL
            class Foo {
                Bar bar
            }

            @DSL
            class Bar {
                @Owner Foo owner
            }
        ''')

        when:
        instance = clazz.create {
            bar {}
        }
        instance.toString()

        then:
        notThrown(StackOverflowError)
    }

    def "error: more than one key"() {
        when:
        createClass('''
            package pk

            @DSL
            class Foo {
                @Key String name
                @Key String name2
            }
        ''')

        then:
        thrown(MultipleCompilationErrorsException)
    }



}
