package com.blackbuild.groovy.configdsl.transform;

import com.blackbuild.groovy.configdsl.transform.ast.DSLASTTransformation;
import org.codehaus.groovy.transform.GroovyASTTransformationClass;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
@Inherited // This is currently not used, see https://issues.apache.org/jira/browse/GROOVY-6765
@GroovyASTTransformationClass(classes={DSLASTTransformation.class})
public @interface DSL {
}
