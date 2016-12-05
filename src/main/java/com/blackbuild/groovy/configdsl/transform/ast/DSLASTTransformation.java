package com.blackbuild.groovy.configdsl.transform.ast;

import com.blackbuild.groovy.configdsl.transform.*;
import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyShell;
import groovy.transform.EqualsAndHashCode;
import groovy.transform.ToString;
import groovy.util.DelegatingScript;
import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.stmt.*;
import org.codehaus.groovy.ast.tools.GeneralUtils;
import org.codehaus.groovy.ast.tools.GenericsUtils;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.codehaus.groovy.transform.AbstractASTTransformation;
import org.codehaus.groovy.transform.GroovyASTTransformation;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;

import static com.blackbuild.groovy.configdsl.transform.ast.ASTHelper.getAnnotation;
import static org.codehaus.groovy.ast.ClassHelper.*;
import static org.codehaus.groovy.ast.expr.MethodCallExpression.NO_ARGUMENTS;
import static org.codehaus.groovy.ast.tools.GeneralUtils.*;
import static org.codehaus.groovy.ast.tools.GenericsUtils.newClass;
import static org.codehaus.groovy.transform.EqualsAndHashCodeASTTransformation.createEquals;
import static org.codehaus.groovy.transform.EqualsAndHashCodeASTTransformation.createHashCode;
import static org.codehaus.groovy.transform.ToStringASTTransformation.createToString;

/**
 * Transformation class for the @DSL annotation.
 *
 * @author Stephan Pauxberger
 */
@SuppressWarnings("WeakerAccess")
@GroovyASTTransformation(phase = CompilePhase.CANONICALIZATION)
public class DSLASTTransformation extends AbstractASTTransformation {

    static final ClassNode[] NO_EXCEPTIONS = new ClassNode[0];
    static final ClassNode DSL_CONFIG_ANNOTATION = make(DSL.class);
    static final ClassNode DSL_FIELD_ANNOTATION = make(Field.class);
    static final ClassNode VALIDATE_ANNOTATION = make(Validate.class);
    static final ClassNode VALIDATION_ANNOTATION = make(Validation.class);
    static final ClassNode KEY_ANNOTATION = make(Key.class);
    static final ClassNode OWNER_ANNOTATION = make(Owner.class);
    static final ClassNode IGNORE_ANNOTATION = make(Ignore.class);

    static final ClassNode EXCEPTION_TYPE = make(Exception.class);
    static final ClassNode VALIDATION_EXCEPTION_TYPE = make(IllegalStateException.class);
    static final ClassNode ASSERTION_ERROR_TYPE = make(AssertionError.class);

    static final ClassNode EQUALS_HASHCODE_ANNOT = make(EqualsAndHashCode.class);
    static final ClassNode TOSTRING_ANNOT = make(ToString.class);
    static final String TEMPLATE_FIELD_NAME = "$TEMPLATE";
    static final String VALIDATE_METHOD = "validate";
    ClassNode annotatedClass;
    FieldNode keyField;
    FieldNode ownerField;
    AnnotationNode dslAnnotation;

    @Override
    public void visit(ASTNode[] nodes, SourceUnit source) {
        init(nodes, source);

        annotatedClass = (ClassNode) nodes[1];
        keyField = getKeyField(annotatedClass);
        ownerField = getOwnerField(annotatedClass);
        dslAnnotation = (AnnotationNode) nodes[0];

        if (keyField != null)
            createKeyConstructor();

        validateFieldAnnotations();
        assertMembersNamesAreUnique();
        createApplyMethods();
        createTemplateMethods();
        createFactoryMethods();
        createFieldMethods();
        createCanonicalMethods();
        createValidateMethod();
        createDefaultMethods();

        if (annotedClassIsTopOfDSLHierarchy())
            preventOwnerOverride();
    }

    private void createDefaultMethods() {
        new DefaultMethods(this).execute();

    }

    private void createValidateMethod() {
        Validation.Mode mode = getEnumMemberValue(getAnnotation(annotatedClass, VALIDATION_ANNOTATION), "mode", Validation.Mode.class, Validation.Mode.AUTOMATIC);

        annotatedClass.addField("$manualValidation", ACC_PRIVATE, ClassHelper.Boolean_TYPE, new ConstantExpression(mode == Validation.Mode.MANUAL));
        MethodBuilder.createPublicMethod("manualValidation")
                .param(Boolean_TYPE, "validation")
                .assignS(varX("$manualValidation"), varX("validation"))
                .addTo(annotatedClass);

        MethodBuilder methodBuilder = MethodBuilder.createPublicMethod(VALIDATE_METHOD);

        if (ASTHelper.isDSLObject(annotatedClass.getSuperClass())) {
            methodBuilder.statement(callSuperX(VALIDATE_METHOD));
        }

        BlockStatement block = new BlockStatement();
        validateFields(block);
        validateCustomMethods(block);

        TryCatchStatement tryCatchStatement = new TryCatchStatement(block, EmptyStatement.INSTANCE);
        tryCatchStatement.addCatch(new CatchStatement(
                param(ASSERTION_ERROR_TYPE, "e"),
                new ThrowStatement(ctorX(VALIDATION_EXCEPTION_TYPE, args(propX(varX("e"), "message"), varX("e"))))
                )
        );
        tryCatchStatement.addCatch(new CatchStatement(
                param(EXCEPTION_TYPE, "e"),
                new ThrowStatement(ctorX(VALIDATION_EXCEPTION_TYPE, args(propX(varX("e"), "message"), varX("e"))))
                )
        );

        methodBuilder
                .statement(tryCatchStatement)
                .addTo(annotatedClass);
    }

    private void validateCustomMethods(BlockStatement block) {
        if (!annotatedClass.hasMethod("doValidate", Parameter.EMPTY_ARRAY)) return;

        block.addStatement(stmt(callX(varX("this"), "doValidate")));
    }

    private void validateFields(BlockStatement block) {
        Validation.Option mode = getEnumMemberValue(
                getAnnotation(annotatedClass, VALIDATION_ANNOTATION),
                "option",
                Validation.Option.class,
                Validation.Option.IGNORE_UNMARKED);
        for (FieldNode fieldNode : annotatedClass.getFields()) {
            if (shouldFieldBeIgnored(fieldNode)) continue;

            ClosureExpression validationClosure = createGroovyTruthClosureExpression(block.getVariableScope());
            String message = null;

            AnnotationNode validateAnnotation = getAnnotation(fieldNode, VALIDATE_ANNOTATION);
            if (validateAnnotation != null) {
                message = getMemberStringValue(validateAnnotation, "message", "'" + fieldNode.getName() + "' must be set!");
                Expression member = validateAnnotation.getMember("value");
                if (member instanceof ClassExpression) {
                    ClassNode memberType = member.getType();
                    if (memberType.equals(ClassHelper.make(Validate.Ignore.class)))
                        continue;
                    else if (!memberType.equals(ClassHelper.make(Validate.GroovyTruth.class))) {
                        addError("value of Validate must be either Validate.GroovyTruth, Validate.Ignore or a closure.", fieldNode);
                    }
                } else if (member instanceof ClosureExpression){
                    validationClosure = (ClosureExpression) member;
                }
            }

            if (validateAnnotation != null || mode == Validation.Option.VALIDATE_UNMARKED) {
                block.addStatement(new AssertStatement(
                        new BooleanExpression(
                                callX(validationClosure, "call", args(varX(fieldNode.getName())))
                        ), message == null ? ConstantExpression.NULL : new ConstantExpression(message)
                ));
            }
        }
    }

    @NotNull
    private ClosureExpression createGroovyTruthClosureExpression(VariableScope scope) {
        ClosureExpression result = new ClosureExpression(params(param(OBJECT_TYPE, "it")), returnS(varX("it")));
        result.setVariableScope(scope.copy());
        return result;
    }

    private boolean annotedClassIsTopOfDSLHierarchy() {
        return ownerField != null && annotatedClass.getDeclaredField(ownerField.getName()) != null;
    }

    private void validateFieldAnnotations() {
        for (FieldNode fieldNode : annotatedClass.getFields()) {
            if (shouldFieldBeIgnored(fieldNode)) continue;

            AnnotationNode annotation = getAnnotation(fieldNode, DSL_FIELD_ANNOTATION);

            if (annotation == null) continue;

            if (isListOrMap(fieldNode.getType())) return;

            if (annotation.getMember("members") != null) {
                addCompileError(
                        String.format("@Field.members is only valid for List or Map fields, but field %s is of type %s", fieldNode.getName(), fieldNode.getType().getName()),
                        annotation
                );
            }
        }
    }

    private void assertMembersNamesAreUnique() {
        Map<String, FieldNode> allDslCollectionFieldNodesOfHierarchy = new HashMap<String, FieldNode>();

        for (ClassNode level : ASTHelper.getHierarchyOfDSLObjectAncestors(annotatedClass)) {
            for (FieldNode field : level.getFields()) {
                if (!isListOrMap(field.getType())) continue;

                String memberName = getElementNameForCollectionField(field);

                FieldNode conflictingField = allDslCollectionFieldNodesOfHierarchy.get(memberName);

                if (conflictingField != null) {
                    addCompileError(
                            String.format("Member name %s is used more than once: %s:%s and %s:%s", memberName, field.getOwner().getName(), field.getName(), conflictingField.getOwner().getName(), conflictingField.getName()),
                            field
                    );
                    return;
                }

                allDslCollectionFieldNodesOfHierarchy.put(memberName, field);
            }
        }
    }

    private void createTemplateMethods() {
        annotatedClass.addField(TEMPLATE_FIELD_NAME, ACC_STATIC, newClass(annotatedClass), null);

        ClassNode templateClass;

        if (isAbstract(annotatedClass))
            templateClass = createTemplateClass();
        else
            templateClass = annotatedClass;

        MethodBuilder.createPublicMethod("createTemplate")
                .deprecated()
                .returning(newClass(annotatedClass))
                .mod(Opcodes.ACC_STATIC)
                .delegatingClosureParam(annotatedClass)
                .assignS(propX(classX(annotatedClass), "$TEMPLATE"), constX(null))
                .declareVariable("result", keyField != null ? ctorX(templateClass, args(ConstantExpression.NULL)) : ctorX(templateClass))
                .callMethod("result", "copyFromTemplate")
                .callMethod("result", "apply", varX("closure"))
                .assignS(propX(classX(annotatedClass), "$TEMPLATE"), varX("result"))
                .addTo(annotatedClass);

        MethodBuilder.createPublicMethod("copyFromTemplate")
                .deprecated()
                .returning(newClass(annotatedClass))
                .doReturn(callThisX("copyFrom", args(propX(classX(annotatedClass), "$TEMPLATE"))))
                .addTo(annotatedClass);

        MethodBuilder templateApply = MethodBuilder.createPublicMethod("copyFrom")
                .returning(newClass(annotatedClass))
                 // highest ancestor is needed because otherwise wrong methods are called if only parent has a template
                 // see DefaultValuesSpec."template for parent class affects child instances"()
                .param(newClass(ASTHelper.getHighestAncestorDSLObject(annotatedClass)), "template");

        ClassNode parentClass = annotatedClass.getSuperClass();

        if (ASTHelper.isDSLObject(parentClass)) {
            templateApply.statement(ifS(
                            notX(isInstanceOfX(varX("template"), annotatedClass)),
                            returnS(callSuperX("copyFrom", args(propX(classX(parentClass), "$TEMPLATE"))))
                    )
            );

            templateApply.statement(callSuperX("copyFrom", args("template")));
        } else {
            templateApply.statement(ifS(notX(isInstanceOfX(varX("template"), annotatedClass)), returnS(varX("this"))));
        }

        for (FieldNode fieldNode : annotatedClass.getFields()) {
            if (shouldFieldBeIgnored(fieldNode)) continue;

            if (isListOrMap(fieldNode.getType()))
                templateApply.statement(
                        ifS(
                                propX(varX("template"), fieldNode.getName()),
                                assignS(propX(varX("this"), fieldNode.getName()), callX(propX(varX("template"), fieldNode.getName()), "clone"))
                        )
                );
            else
                templateApply.statement(
                        ifS(
                                propX(varX("template"), fieldNode.getName()),
                                assignS(propX(varX("this"), fieldNode.getName()), propX(varX("template"), fieldNode.getName()))
                        )
                );
        }

        templateApply
                .doReturn("this")
                .addTo(annotatedClass);
    }

    private ClassNode createTemplateClass() {

        InnerClassNode contextClass = new InnerClassNode(
                annotatedClass,
                annotatedClass.getName() + "$Template",
                ACC_STATIC,
                newClass(annotatedClass));

        contextClass.addField(TEMPLATE_FIELD_NAME, ACC_STATIC, newClass(contextClass), null);

        if (keyField != null) {
            contextClass.addConstructor(
                    0,
                    params(param(keyField.getType(), "key")),
                    NO_EXCEPTIONS,
                    block(
                            ctorSuperS(args(constX(null)))
                    )
            );
        }

        List<MethodNode> abstractMethods = annotatedClass.getAbstractMethods();
        if (abstractMethods != null) {
            for (MethodNode abstractMethod : abstractMethods) {
                implementAbstractMethod(contextClass, abstractMethod);
            }
        }

        annotatedClass.getModule().addClass(contextClass);

        return contextClass;
    }

    private void implementAbstractMethod(ClassNode target, MethodNode abstractMethod) {
        target.addMethod(
                abstractMethod.getName(),
                abstractMethod.getModifiers() ^ ACC_ABSTRACT,
                abstractMethod.getReturnType(),
                cloneParams(abstractMethod.getParameters()),
                abstractMethod.getExceptions(),
                block()
        );
    }

    private void preventOwnerOverride() {

        MethodBuilder.createPublicMethod(setterName(ownerField))
                .param(OBJECT_TYPE, "value")
                .statement(
                        ifS(
                                andX(
                                        isInstanceOfX(varX("value"), ownerField.getType()),
                                        notX(propX(varX("this"), ownerField.getName()))),
                                assignX(propX(varX("this"), ownerField.getName()), varX("value"))
                        )
                )
                .addTo(annotatedClass);
    }

    private void createKeyConstructor() {
        annotatedClass.addConstructor(
                ACC_PUBLIC,
                params(param(STRING_TYPE, "key")),
                NO_EXCEPTIONS,
                block(
                        ASTHelper.isDSLObject(annotatedClass.getSuperClass()) ? ctorSuperS(args("key")) : ctorSuperS(),
                        assignS(propX(varX("this"), keyField.getName()), varX("key"))
                )
        );
    }

    private void createCanonicalMethods() {
        if (!hasAnnotation(annotatedClass, EQUALS_HASHCODE_ANNOT)) {
            createHashCode(annotatedClass, false, false, true, null, null);
            createEquals(annotatedClass, false, true, true, null, null);
        }
        if (!hasAnnotation(annotatedClass, TOSTRING_ANNOT)) {
            if (ownerField == null)
                createToString(annotatedClass, false, false, null, null, false);
            else
                createToString(annotatedClass, false, false, Collections.singletonList(ownerField.getName()), null, false);
        }
    }

    private void createFieldMethods() {
        for (FieldNode fieldNode : annotatedClass.getFields())
            createMethodsForSingleField(fieldNode);
    }

    private void createMethodsForSingleField(FieldNode fieldNode) {
        if (shouldFieldBeIgnored(fieldNode)) return;

        if (hasAnnotation(fieldNode.getType(), DSL_CONFIG_ANNOTATION)) {
            createSingleDSLObjectClosureMethod(fieldNode);
            createSingleFieldSetterMethod(fieldNode);
        } else if (isMap(fieldNode.getType()))
            createMapMethod(fieldNode);
        else if (isList(fieldNode.getType()))
            createListMethod(fieldNode);
        else
            createSingleFieldSetterMethod(fieldNode);
    }

    @SuppressWarnings("RedundantIfStatement")
    private boolean shouldFieldBeIgnored(FieldNode fieldNode) {
        if (fieldNode == keyField) return true;
        if (fieldNode == ownerField) return true;
        if (getAnnotation(fieldNode, IGNORE_ANNOTATION) != null) return true;
        if (fieldNode.isFinal()) return true;
        if (fieldNode.getName().startsWith("$")) return true;
        if ((fieldNode.getModifiers() & ACC_TRANSIENT) != 0) return true;
        return false;
    }

    private boolean isListOrMap(ClassNode type) {
        return isList(type) || isMap(type);
    }

    private boolean isList(ClassNode type) {
        return type.equals(ClassHelper.LIST_TYPE) || type.implementsInterface(ClassHelper.LIST_TYPE);
    }

    private boolean isMap(ClassNode type) {
        return type.equals(ClassHelper.MAP_TYPE) || type.implementsInterface(ClassHelper.MAP_TYPE);
    }

    private void createSingleFieldSetterMethod(FieldNode fieldNode) {
        MethodBuilder.createPublicMethod(fieldNode.getName())
                .param(fieldNode.getType(), "value")
                .assignToProperty(fieldNode.getName(), varX("value"))
                .addTo(annotatedClass);

        if (fieldNode.getType().equals(ClassHelper.boolean_TYPE)) {
            MethodBuilder.createPublicMethod(fieldNode.getName())
                    .callThis(fieldNode.getName(), constX(true))
                    .addTo(annotatedClass);
        }
    }

    private String getElementNameForCollectionField(FieldNode fieldNode) {
        AnnotationNode fieldAnnotation = getAnnotation(fieldNode, DSL_FIELD_ANNOTATION);

        String result = getNullSafeMemberStringValue(fieldAnnotation, "members", null);

        if (result != null && result.length() > 0) return result;

        String collectionMethodName = fieldNode.getName();

        if (collectionMethodName.endsWith("s"))
            return collectionMethodName.substring(0, collectionMethodName.length() - 1);

        return collectionMethodName;
    }

    private String getNullSafeMemberStringValue(AnnotationNode fieldAnnotation, String value, String name) {
        return fieldAnnotation == null ? name : getMemberStringValue(fieldAnnotation, value, name);
    }

    private void createListMethod(FieldNode fieldNode) {
        initializeField(fieldNode, new ListExpression());

        ClassNode elementType = getGenericsTypes(fieldNode)[0].getType();

        if (hasAnnotation(elementType, DSL_CONFIG_ANNOTATION))
            createListOfDSLObjectMethods(fieldNode, elementType);
        else
            createListOfSimpleElementsMethods(fieldNode, elementType);
    }

    private void initializeField(FieldNode fieldNode, Expression init) {
        if (!fieldNode.hasInitialExpression())
            fieldNode.setInitialValueExpression(init);
    }

    private void createListOfSimpleElementsMethods(FieldNode fieldNode, ClassNode elementType) {

        MethodBuilder.createPublicMethod(fieldNode.getName())
                .arrayParam(elementType, "values")
                .statement(callX(propX(varX("this"), fieldNode.getName()), "addAll", varX("values")))
                .addTo(annotatedClass);

        MethodBuilder.createPublicMethod(fieldNode.getName())
                .param(fieldNode.getType(), "values")
                .statement(callX(propX(varX("this"), fieldNode.getName()), "addAll", varX("values")))
                .addTo(annotatedClass);

        MethodBuilder.createPublicMethod(getElementNameForCollectionField(fieldNode))
                .param(elementType, "value")
                .statement(callX(propX(varX("this"), fieldNode.getName()), "add", varX("value")))
                .addTo(annotatedClass);
    }

    @NotNull
    private Statement[] delegateToClosure() {
        return new Statement[]{
                assignS(propX(varX("closure"), "delegate"), varX("context")),
                assignS(
                        propX(varX("closure"), "resolveStrategy"),
                        propX(classX(CLOSURE_TYPE), "DELEGATE_FIRST")
                ),
                stmt(callX(varX("closure"), "call"))
        };
    }

    private void createListOfDSLObjectMethods(FieldNode fieldNode, ClassNode elementType) {
        String methodName = getElementNameForCollectionField(fieldNode);

        FieldNode fieldKey = getKeyField(elementType);
        String targetOwner = getOwnerFieldName(elementType);

        MethodBuilder.createPublicMethod(fieldNode.getName())
                .param(GeneralUtils.param(GenericsUtils.nonGeneric(ClassHelper.CLOSURE_TYPE), "closure"))
                .assignS(propX(varX("closure"), "delegate"), varX("this"))
                .assignS(
                        propX(varX("closure"), "resolveStrategy"),
                        propX(classX(ClassHelper.CLOSURE_TYPE), "DELEGATE_FIRST")
                )
                .callMethod("closure", "call")
                .addTo(annotatedClass);

        if (!isAbstract(elementType)) {
            MethodBuilder.createPublicMethod(methodName)
                    .returning(elementType)
                    .namedParams("values")
                    .optionalStringParam("key", fieldKey)
                    .delegatingClosureParam(elementType)
                    .declareVariable("created", callX(classX(elementType), "newInstance", optionalKeyArg(fieldKey)))
                    .callMethod("created", "copyFromTemplate")
                    .optionalAssignThisToPropertyS("created", targetOwner, targetOwner)
                    .callMethod(fieldNode.getName(), "add", callX(varX("created"), "apply", args("values", "closure")))
                    .callValidationOn("created")
                    .doReturn("created")
                    .addTo(annotatedClass);
            MethodBuilder.createPublicMethod(methodName)
                    .returning(elementType)
                    .optionalStringParam("key", fieldKey)
                    .delegatingClosureParam(elementType)
                    .declareVariable("created", callX(classX(elementType), "newInstance", optionalKeyArg(fieldKey)))
                    .callMethod("created", "copyFromTemplate")
                    .optionalAssignThisToPropertyS("created", targetOwner, targetOwner)
                    .callMethod(fieldNode.getName(), "add", callX(varX("created"), "apply", varX("closure")))
                    .callValidationOn("created")
                    .doReturn("created")
                    .addTo(annotatedClass);
        }

        if (!isFinal(elementType)) {
            MethodBuilder.createPublicMethod(methodName)
                    .returning(elementType)
                    .namedParams("values")
                    .classParam("typeToCreate", elementType)
                    .optionalStringParam("key", fieldKey)
                    .delegatingClosureParam(elementType)
                    .declareVariable("created", callX(varX("typeToCreate"), "newInstance", optionalKeyArg(fieldKey)))
                    .callMethod("created", "copyFromTemplate")
                    .optionalAssignThisToPropertyS("created", targetOwner, targetOwner)
                    .callMethod(fieldNode.getName(), "add", callX(varX("created"), "apply", args("values", "closure")))
                    .callValidationOn("created")
                    .doReturn("created")
                    .addTo(annotatedClass);
            MethodBuilder.createPublicMethod(methodName)
                    .returning(elementType)
                    .classParam("typeToCreate", elementType)
                    .optionalStringParam("key", fieldKey)
                    .delegatingClosureParam(elementType)
                    .declareVariable("created", callX(varX("typeToCreate"), "newInstance", optionalKeyArg(fieldKey)))
                    .callMethod("created", "copyFromTemplate")
                    .optionalAssignThisToPropertyS("created", targetOwner, targetOwner)
                    .callMethod(fieldNode.getName(), "add", callX(varX("created"), "apply", varX("closure")))
                    .callValidationOn("created")
                    .doReturn("created")
                    .addTo(annotatedClass);
        }

        MethodBuilder.createPublicMethod(methodName)
                .param(elementType, "value")
                .callMethod(fieldNode.getName(), "add", varX("value"))
                .optionalAssignThisToPropertyS("value", targetOwner, targetOwner)
                .addTo(annotatedClass);

    }

    private Expression optionalKeyArg(FieldNode fieldKey) {
        return fieldKey != null ? args("key") : NO_ARGUMENTS;
    }

    private String setterName(FieldNode node) {
        char[] name = node.getName().toCharArray();
        name[0] = Character.toUpperCase(name[0]);
        return "set" + new String(name);
    }

    @SuppressWarnings("ConstantConditions")
    private GenericsType[] getGenericsTypes(FieldNode fieldNode) {
        GenericsType[] types = fieldNode.getType().getGenericsTypes();

        if (types == null)
            addCompileError("Lists and Maps need to be assigned an explicit Generic Type", fieldNode);
        return types;
    }

    private void createMapMethod(FieldNode fieldNode) {
        initializeField(fieldNode, new MapExpression());

        ClassNode keyType = getGenericsTypes(fieldNode)[0].getType();
        ClassNode valueType = getGenericsTypes(fieldNode)[1].getType();

        if (hasAnnotation(valueType, DSL_CONFIG_ANNOTATION))
            createMapOfDSLObjectMethods(fieldNode, keyType, valueType);
        else
            createMapOfSimpleElementsMethods(fieldNode, keyType, valueType);
    }

    private void createMapOfSimpleElementsMethods(FieldNode fieldNode, ClassNode keyType, ClassNode valueType) {
        String methodName = fieldNode.getName();

        MethodBuilder.createPublicMethod(methodName)
                .param(fieldNode.getType(), "values")
                .callMethod(propX(varX("this"), fieldNode.getName()), "putAll", varX("values"))
                .addTo(annotatedClass);

        String singleElementMethod = getElementNameForCollectionField(fieldNode);

        MethodBuilder.createPublicMethod(singleElementMethod)
                .param(keyType, "key")
                .param(valueType, "value")
                .callMethod(propX(varX("this"), fieldNode.getName()), "put", args("key", "value"))
                .addTo(annotatedClass);
    }

    private void createMapOfDSLObjectMethods(FieldNode fieldNode, ClassNode keyType, ClassNode elementType) {
        if (getKeyField(elementType) == null) {
            addCompileError(
                    String.format("Value type of map %s (%s) has no key field", fieldNode.getName(), elementType.getName()),
                    fieldNode
            );
            return;
        }

        MethodBuilder.createPublicMethod(fieldNode.getName())
                .param(GeneralUtils.param(GenericsUtils.nonGeneric(ClassHelper.CLOSURE_TYPE), "closure"))
                .assignS(propX(varX("closure"), "delegate"), varX("this"))
                .assignS(
                        propX(varX("closure"), "resolveStrategy"),
                        propX(classX(ClassHelper.CLOSURE_TYPE), "DELEGATE_FIRST")
                )
                .callMethod("closure", "call")
                .addTo(annotatedClass);


        String methodName = getElementNameForCollectionField(fieldNode);
        String targetOwner = getOwnerFieldName(elementType);

        if (!isAbstract(elementType)) {
            MethodBuilder.createPublicMethod(methodName)
                    .returning(elementType)
                    .namedParams("values")
                    .param(keyType, "key")
                    .delegatingClosureParam(elementType)
                    .declareVariable("created", callX(classX(elementType), "newInstance", args("key")))
                    .callMethod("created", "copyFromTemplate")
                    .optionalAssignThisToPropertyS("created", targetOwner, targetOwner)
                    .callMethod(fieldNode.getName(), "put", args(varX("key"), varX("created")))
                    .callMethod("created", "apply", args("values", "closure"))
                    .callValidationOn("created")
                    .doReturn("created")
                    .addTo(annotatedClass);
            MethodBuilder.createPublicMethod(methodName)
                    .returning(elementType)
                    .param(keyType, "key")
                    .delegatingClosureParam(elementType)
                    .declareVariable("created", callX(classX(elementType), "newInstance", args("key")))
                    .callMethod("created", "copyFromTemplate")
                    .optionalAssignThisToPropertyS("created", targetOwner, targetOwner)
                    .callMethod(fieldNode.getName(), "put", args(varX("key"), varX("created")))
                    .callMethod("created", "apply", varX("closure"))
                    .callValidationOn("created")
                    .doReturn("created")
                    .addTo(annotatedClass);
        }

        if (!isFinal(elementType)) {
            MethodBuilder.createPublicMethod(methodName)
                    .returning(elementType)
                    .namedParams("values")
                    .classParam("typeToCreate", elementType)
                    .param(keyType, "key")
                    .delegatingClosureParam(elementType)
                    .declareVariable("created", callX(varX("typeToCreate"), "newInstance", args("key")))
                    .callMethod("created", "copyFromTemplate")
                    .callMethod(fieldNode.getName(), "put", args(varX("key"), varX("created")))
                    .optionalAssignThisToPropertyS("created", targetOwner, targetOwner)
                    .callMethod("created", "apply", args("values", "closure"))
                    .callValidationOn("created")
                    .doReturn("created")
                    .addTo(annotatedClass);
            MethodBuilder.createPublicMethod(methodName)
                    .returning(elementType)
                    .classParam("typeToCreate", elementType)
                    .param(keyType, "key")
                    .delegatingClosureParam(elementType)
                    .declareVariable("created", callX(varX("typeToCreate"), "newInstance", args("key")))
                    .callMethod("created", "copyFromTemplate")
                    .callMethod(fieldNode.getName(), "put", args(varX("key"), varX("created")))
                    .optionalAssignThisToPropertyS("created", targetOwner, targetOwner)
                    .callMethod("created", "apply", varX("closure"))
                    .callValidationOn("created")
                    .doReturn("created")
                    .addTo(annotatedClass);
        }

        //noinspection ConstantConditions
        MethodBuilder.createPublicMethod(methodName)
                .param(elementType, "value")
                .callMethod(fieldNode.getName(), "put", args(propX(varX("value"), getKeyField(elementType).getName()), varX("value")))
                .optionalAssignThisToPropertyS("value", targetOwner, targetOwner)
                .addTo(annotatedClass);
    }

    private void createSingleDSLObjectClosureMethod(FieldNode fieldNode) {
        String methodName = fieldNode.getName();

        ClassNode targetFieldType = fieldNode.getType();
        FieldNode targetTypeKeyField = getKeyField(targetFieldType);
        String targetOwnerFieldName = getOwnerFieldName(targetFieldType);

        if (!isAbstract(targetFieldType)) {
            MethodBuilder.createPublicMethod(methodName)
                    .returning(targetFieldType)
                    .namedParams("values")
                    .optionalStringParam("key", targetTypeKeyField)
                    .delegatingClosureParam(targetFieldType)
                    .declareVariable("created", callX(classX(targetFieldType), "newInstance", optionalKeyArg(targetTypeKeyField)))
                    .callMethod("created", "copyFromTemplate")
                    .optionalAssignThisToPropertyS("created", targetOwnerFieldName, targetOwnerFieldName)
                    .assignToProperty(fieldNode.getName(), callX(varX("created"), "apply", args("values", "closure")))
                    .callValidationOn("created")
                    .doReturn("created")
                    .addTo(annotatedClass);

            MethodBuilder.createPublicMethod(methodName)
                    .returning(targetFieldType)
                    .optionalStringParam("key", targetTypeKeyField)
                    .delegatingClosureParam(targetFieldType)
                    .declareVariable("created", callX(classX(targetFieldType), "newInstance", optionalKeyArg(targetTypeKeyField)))
                    .callMethod("created", "copyFromTemplate")
                    .optionalAssignThisToPropertyS("created", targetOwnerFieldName, targetOwnerFieldName)
                    .assignToProperty(fieldNode.getName(), callX(varX("created"), "apply", varX("closure")))
                    .callValidationOn("created")
                    .doReturn("created")
                    .addTo(annotatedClass);
        }

        if (!isFinal(targetFieldType)) {
            MethodBuilder.createPublicMethod(methodName)
                    .returning(targetFieldType)
                    .namedParams("values")
                    .classParam("typeToCreate", targetFieldType)
                    .optionalStringParam("key", targetTypeKeyField)
                    .delegatingClosureParam(targetFieldType)
                    .declareVariable("created", callX(varX("typeToCreate"), "newInstance", optionalKeyArg(targetTypeKeyField)))
                    .callMethod("created", "copyFromTemplate")
                    .optionalAssignThisToPropertyS("created", targetOwnerFieldName, targetOwnerFieldName)
                    .assignToProperty(fieldNode.getName(), callX(varX("created"), "apply", args("values", "closure")))
                    .callValidationOn("created")
                    .doReturn("created")
                    .addTo(annotatedClass);

            MethodBuilder.createPublicMethod(methodName)
                    .returning(targetFieldType)
                    .classParam("typeToCreate", targetFieldType)
                    .optionalStringParam("key", targetTypeKeyField)
                    .delegatingClosureParam(targetFieldType)
                    .declareVariable("created", callX(varX("typeToCreate"), "newInstance", optionalKeyArg(targetTypeKeyField)))
                    .callMethod("created", "copyFromTemplate")
                    .optionalAssignThisToPropertyS("created", targetOwnerFieldName, targetOwnerFieldName)
                    .assignToProperty(fieldNode.getName(), callX(varX("created"), "apply", varX("closure")))
                    .callValidationOn("created")
                    .doReturn("created")
                    .addTo(annotatedClass);
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean isFinal(ClassNode classNode) {
        return (classNode.getModifiers() & ACC_FINAL) != 0;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean isAbstract(ClassNode classNode) {
        return (classNode.getModifiers() & ACC_ABSTRACT) != 0;
    }

    private void createApplyMethods() {
        MethodBuilder.createPublicMethod("apply")
                .returning(newClass(annotatedClass))
                .namedParams("values")
                .delegatingClosureParam(annotatedClass)
                .applyNamedParams("values")
                .assignS(propX(varX("closure"), "delegate"), varX("this"))
                .assignS(
                        propX(varX("closure"), "resolveStrategy"),
                        propX(classX(ClassHelper.CLOSURE_TYPE), "DELEGATE_FIRST")
                )
                .callMethod("closure", "call")
                .doReturn("this")
                .addTo(annotatedClass);

        MethodBuilder.createPublicMethod("apply")
                .returning(newClass(annotatedClass))
                .delegatingClosureParam(annotatedClass)
                .callThis("apply", args(new MapExpression(), varX("closure")))
                .doReturn("this")
                .addTo(annotatedClass);
    }

    private void createFactoryMethods() {
        MethodBuilder.createPublicMethod("create")
                .returning(newClass(annotatedClass))
                .mod(Opcodes.ACC_STATIC)
                .namedParams("values")
                .optionalStringParam("name", keyField)
                .delegatingClosureParam(annotatedClass)
                .declareVariable("result", keyField != null ? ctorX(annotatedClass, args("name")) : ctorX(annotatedClass))
                .callMethod("result", "copyFromTemplate")
                .callMethod("result", "apply", args("values", "closure"))
                .callValidationOn("result")
                .doReturn("result")
                .addTo(annotatedClass);

        MethodBuilder.createPublicMethod("create")
                .returning(newClass(annotatedClass))
                .mod(Opcodes.ACC_STATIC)
                .optionalStringParam("name", keyField)
                .delegatingClosureParam(annotatedClass)
                .doReturn(callX(annotatedClass, "create",
                        keyField != null ?
                        args(new MapExpression(), varX("name"), varX("closure"))
                        : args(new MapExpression(), varX("closure"))
                ))
                .addTo(annotatedClass);

        MethodBuilder.createPublicMethod("createFromScript")
                .returning(newClass(annotatedClass))
                .deprecated()
                .mod(Opcodes.ACC_STATIC)
                .classParam("configType", ClassHelper.SCRIPT_TYPE)
                .doReturn(callX(callX(varX("configType"), "newInstance"), "run"))
                .addTo(annotatedClass);

        if (keyField != null) {
            MethodBuilder.createPublicMethod("createFrom")
                    .returning(newClass(annotatedClass))
                    .mod(Opcodes.ACC_STATIC)
                    .stringParam("name")
                    .stringParam("text")
                    .declareVariable("simpleName", callX(callX(callX(callX(varX("name"), "tokenize", args(constX("."))), "first"), "tokenize", args(constX("/"))), "last"))
                    .declareVariable("result", callX(annotatedClass, "create", args("simpleName")))
                    .declareVariable("loader", ctorX(ClassHelper.make(GroovyClassLoader.class), args(callX(callX(ClassHelper.make(Thread.class), "currentThread"), "getContextClassLoader"))))
                    .declareVariable("config", ctorX(ClassHelper.make(CompilerConfiguration.class)))
                    .assignS(propX(varX("config"), "scriptBaseClass"), constX(DelegatingScript.class.getName()))
                    .declareVariable("binding", ctorX(ClassHelper.make(Binding.class)))
                    .declareVariable("shell", ctorX(ClassHelper.make(GroovyShell.class), args("loader", "binding", "config")))
                    .declareVariable("script", callX(varX("shell"), "parse", args("text")))
                    .callMethod("script", "setDelegate", args("result"))
                    .callMethod("script", "run")
                    .doReturn("result")
                    .addTo(annotatedClass);

            MethodBuilder.createPublicMethod("createFromSnippet")
                    .deprecated()
                    .returning(newClass(annotatedClass))
                    .mod(Opcodes.ACC_STATIC)
                    .stringParam("name")
                    .stringParam("text")
                    .doReturn(callX(annotatedClass, "createFrom", args("name", "text")))
                    .addTo(annotatedClass);
        } else {
            MethodBuilder.createPublicMethod("createFrom")
                    .returning(newClass(annotatedClass))
                    .mod(Opcodes.ACC_STATIC)
                    .stringParam("text")
                    .declareVariable("result", callX(annotatedClass, "create"))
                    .declareVariable("loader", ctorX(ClassHelper.make(GroovyClassLoader.class), args(callX(callX(ClassHelper.make(Thread.class), "currentThread"), "getContextClassLoader"))))
                    .declareVariable("config", ctorX(ClassHelper.make(CompilerConfiguration.class)))
                    .assignS(propX(varX("config"), "scriptBaseClass"), constX(DelegatingScript.class.getName()))
                    .declareVariable("binding", ctorX(ClassHelper.make(Binding.class)))
                    .declareVariable("shell", ctorX(ClassHelper.make(GroovyShell.class), args("loader", "binding", "config")))
                    .declareVariable("script", callX(varX("shell"), "parse", args("text")))
                    .callMethod("script", "setDelegate", args("result"))
                    .callMethod("script", "run")
                    .doReturn("result")
                    .addTo(annotatedClass);

            MethodBuilder.createPublicMethod("createFromSnippet")
                    .deprecated()
                    .returning(newClass(annotatedClass))
                    .mod(Opcodes.ACC_STATIC)
                    .stringParam("text")
                    .doReturn(callX(annotatedClass, "createFrom", args("text")))
                    .addTo(annotatedClass);
        }

        MethodBuilder.createPublicMethod("createFrom")
                .returning(newClass(annotatedClass))
                .mod(Opcodes.ACC_STATIC)
                .param(make(File.class), "src")
                .doReturn(callX(annotatedClass, "createFromSnippet", args(callX(callX(varX("src"), "toURI"), "toURL"))))
                .addTo(annotatedClass);

        MethodBuilder.createPublicMethod("createFromSnippet")
                .deprecated()
                .returning(newClass(annotatedClass))
                .mod(Opcodes.ACC_STATIC)
                .param(make(File.class), "src")
                .doReturn(callX(annotatedClass, "createFrom", args("src")))
                .addTo(annotatedClass);

        MethodBuilder.createPublicMethod("createFrom")
                .returning(newClass(annotatedClass))
                .mod(Opcodes.ACC_STATIC)
                .param(make(URL.class), "src")
                .declareVariable("text", propX(varX("src"), "text"))
                .doReturn(callX(annotatedClass, "createFromSnippet", keyField != null ? args(propX(varX("src"), "path"), varX("text")) : args("text")))
                .addTo(annotatedClass);

        MethodBuilder.createPublicMethod("createFromSnippet")
                .deprecated()
                .returning(newClass(annotatedClass))
                .mod(Opcodes.ACC_STATIC)
                .param(make(URL.class), "src")
                .doReturn(callX(annotatedClass, "createFrom", args("src")))
                .addTo(annotatedClass);
    }

    private String getQualifiedName(FieldNode node) {
        return node.getOwner().getName() + "." + node.getName();
    }

    private FieldNode getKeyField(ClassNode target) {

        List<FieldNode> annotatedFields = getAnnotatedFieldsOfHierarchy(target, KEY_ANNOTATION);

        if (annotatedFields.isEmpty()) return null;

        if (annotatedFields.size() > 1) {
            addCompileError(
                    String.format(
                            "Found more than one key fields, only one is allowed in hierarchy (%s, %s)",
                            getQualifiedName(annotatedFields.get(0)),
                            getQualifiedName(annotatedFields.get(1))),
                    annotatedFields.get(0)
            );
            return null;
        }

        FieldNode result = annotatedFields.get(0);

        if (!result.getType().equals(ClassHelper.STRING_TYPE)) {
            addCompileError(
                    String.format("Key field '%s' must be of type String, but is '%s' instead", result.getName(), result.getType().getName()),
                    result
            );
            return null;
        }

        ClassNode ancestor = ASTHelper.getHighestAncestorDSLObject(target);

        if (target.equals(ancestor)) return result;

        FieldNode firstKey = getKeyField(ancestor);

        if (firstKey == null) {
            addCompileError(
                    String.format("Inconsistent hierarchy: Toplevel class %s has no key, but child class %s defines '%s'.", ancestor.getName(), target.getName(), result.getName()),
                    result
            );
            return null;
        }

        return result;
    }

    private List<FieldNode> getAnnotatedFieldsOfHierarchy(ClassNode target, ClassNode annotation) {
        List<FieldNode> result = new ArrayList<FieldNode>();

        for (ClassNode level : ASTHelper.getHierarchyOfDSLObjectAncestors(target)) {
            result.addAll(getAnnotatedFieldOfClass(level, annotation));
        }

        return result;
    }

    private List<FieldNode> getAnnotatedFieldOfClass(ClassNode target, ClassNode annotation) {
        List<FieldNode> result = new ArrayList<FieldNode>();

        for (FieldNode fieldNode : target.getFields())
            if (!fieldNode.getAnnotations(annotation).isEmpty())
                result.add(fieldNode);

        return result;
    }

    private FieldNode getOwnerField(ClassNode target) {

        List<FieldNode> annotatedFields = getAnnotatedFieldsOfHierarchy(target, OWNER_ANNOTATION);

        if (annotatedFields.isEmpty()) return null;

        if (annotatedFields.size() > 1) {
            addCompileError(
                    String.format(
                            "Found more than owner key fields, only one is allowed in hierarchy (%s, %s)",
                            getQualifiedName(annotatedFields.get(0)),
                            getQualifiedName(annotatedFields.get(1))),
                    annotatedFields.get(0)
            );
            return null;
        }

        return annotatedFields.get(0);
    }

    private String getOwnerFieldName(ClassNode target) {
        FieldNode ownerFieldOfElement = getOwnerField(target);
        return ownerFieldOfElement != null ? ownerFieldOfElement.getName() : null;
    }

    private void addCompileError(String msg, ASTNode node) {
        SyntaxException se = new SyntaxException(msg, node.getLineNumber(), node.getColumnNumber());
        sourceUnit.getErrorCollector().addFatalError(new SyntaxErrorMessage(se, sourceUnit));
    }

    @SuppressWarnings("unchecked")
    public <T extends Enum> T getEnumMemberValue(AnnotationNode node, String name, Class<T> type, T defaultValue) {
        if (node == null) return defaultValue;

        final PropertyExpression member = (PropertyExpression) node.getMember(name);
        if (member == null)
            return defaultValue;

        if (!type.equals(member.getObjectExpression().getType().getTypeClass()))
            return defaultValue;

        try {
            String value = member.getPropertyAsString();
            Method fromString = type.getMethod("valueOf", String.class);
            return (T) fromString.invoke(null, value);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public void addCompileWarning(String msg, ASTNode node) {
        // TODO Need to convert node into CST node?
        //sourceUnit.getErrorCollector().addWarning(WarningMessage.POSSIBLE_ERRORS, msg, node, sourceUnit);
    }
}