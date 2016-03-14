package com.blackbuild.groovy.configdsl.transform.model

import com.blackbuild.groovy.configdsl.transform.ValidationException
import spock.lang.Ignore

class ValidationSpec extends AbstractDSLSpec {

    def "validation with Groovy Truth"() {
        given:
        createClass('''
            @DSL
            class Foo {
                @Validate
                String validated
            }
        ''')

        when:
        instance = clazz.create {}

        then:
        thrown(ValidationException)
    }

    def "validation is not performed on templates"() {
        given:
        createClass('''
            @DSL
            class Foo {
                @Validate String validated
                String nonValidated
            }
        ''')

        when:
        instance = clazz.createTemplate {
            nonValidated "bla"
        }

        then:
        notThrown(ValidationException)
    }

    def "non annotated fields are not validated"() {
        given:
        createClass('''
            @DSL
            class Foo {
                @Validate
                String validated

                String notvalidated
            }
        ''')

        when:
        instance = clazz.create {
            validated "bla"
        }

        then:
        notThrown(ValidationException)
    }

    def "validation is inherited"() {
        given:
        createClass('''
            package pk

            @DSL
            class Foo {
                @Validate
                String validated
            }

            @DSL
            class Bar extends Foo {
                String child
            }
        ''')

        when:
        instance = create("pk.Bar") {}

        then:
        thrown(ValidationException)
    }

}