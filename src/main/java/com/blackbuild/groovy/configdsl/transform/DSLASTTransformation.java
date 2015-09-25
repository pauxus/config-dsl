package com.blackbuild.groovy.configdsl.transform;

import groovy.transform.EqualsAndHashCode;
import groovy.transform.ToString;
import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.ast.stmt.ThrowStatement;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.codehaus.groovy.transform.AbstractASTTransformation;
import org.codehaus.groovy.transform.GroovyASTTransformation;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;

import java.util.*;

import static com.blackbuild.groovy.configdsl.transform.MethodBuilder.createPublicMethod;
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
@GroovyASTTransformation(phase = CompilePhase.CANONICALIZATION)
public class DSLASTTransformation extends AbstractASTTransformation {

    private static final ClassNode[] NO_EXCEPTIONS = new ClassNode[0];
    private static final ClassNode DSL_CONFIG_ANNOTATION = make(DSL.class);
    private static final ClassNode DSL_FIELD_ANNOTATION = make(Field.class);
    private static final ClassNode KEY_ANNOTATION = make(Key.class);
    private static final ClassNode OWNER_ANNOTATION = make(Owner.class);

    public static final String REUSE_METHOD_NAME = "_reuse";
    public static final String USE_METHOD_NAME = "_use";
    private static final ClassNode EQUALS_HASHCODE_ANNOT = make(EqualsAndHashCode.class);
    private static final ClassNode TOSTRING_ANNOT = make(ToString.class);
    public static final String TEMPLATE_FIELD_NAME = "$TEMPLATE";
    private ClassNode annotatedClass;
    private FieldNode keyField;
    private FieldNode ownerField;

    @Override
    public void visit(ASTNode[] nodes, SourceUnit source) {
        init(nodes, source);

        annotatedClass = (ClassNode) nodes[1];
        keyField = getKeyField(annotatedClass);
        ownerField = getOwnerField(annotatedClass);

        if (keyField != null)
            createKeyConstructor();

        validateFieldAnnotations();
        createApplyMethods();
        createTemplateMethods();
        createFactoryMethods();
        createFieldMethods();
        createCanonicalMethods();

        if (annotedClassIsTopOfDSLHierarchy())
            createGuardingSetter();
    }

    private boolean annotedClassIsTopOfDSLHierarchy() {
        return ownerField != null && annotatedClass.getDeclaredField(ownerField.getName()) != null;
    }

    private void validateFieldAnnotations() {
        for (FieldNode fieldNode : annotatedClass.getFields()) {
            AnnotationNode annotation = getAnnotation(fieldNode, DSL_FIELD_ANNOTATION);

            if (annotation == null) continue;

            if (isListOrMap(fieldNode.getType())) return;

            if (annotation.getMember("members") != null) {
                addCompileError(
                        String.format("@Field.members is only valid for List or Map fields, but field %s is of type %s", fieldNode.getName(), fieldNode.getType().getName()),
                        annotation
                );
            }
            if (annotation.getMember("alternatives") != null) {
                addCompileError(
                        String.format("@Field.alternatives is only valid for List or Map fields, but field %s is of type %s", fieldNode.getName(), fieldNode.getType().getName()),
                        annotation
                );
            }
        }
    }

    private void createTemplateMethods() {
        annotatedClass.addField(TEMPLATE_FIELD_NAME, ACC_STATIC, newClass(annotatedClass), null);

        createPublicMethod("createTemplate")
                .returning(newClass(annotatedClass))
                .mod(Opcodes.ACC_STATIC)
                .delegatingClosureParam(annotatedClass)
                .assignS(propX(classX(annotatedClass), "$TEMPLATE"), callX(
                                classX(annotatedClass),
                                "create",
                                keyField != null ? args(constX(null), varX("closure")) : args("closure")
                        )
                )
                .addTo(annotatedClass);

        MethodBuilder templateApply = createPublicMethod("copyFrom")
                .returning(newClass(annotatedClass))
                 // highest ancestor is needed because otherwise wrong methods are called if only parent has a template
                 // see DefaultValuesSpec."template for parent class affects child instances"()
                .param(newClass(getHighestAncestorDSLObject(annotatedClass)), "template");

        ClassNode parentClass = annotatedClass.getSuperClass();

        if (isDSLObject(parentClass)) {
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
            if (fieldNode == ownerField || fieldNode == keyField) continue;

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
                .statement(returnS(varX("this")))
                .addTo(annotatedClass);
    }

    private void createGuardingSetter() {

        createPublicMethod(setterName(ownerField))
                .param(ownerField.getType(), "value")
                .statement(
                        ifS(
                                notNullX(propX(varX("this"), ownerField.getName())),
                                new ThrowStatement(ctorX(
                                        ClassHelper.make(IllegalStateException.class),
                                        args(constX("Owner must not be overridden.")))
                                )
                        )
                )
                .assignS(propX(varX("this"), ownerField.getName()), varX("value"))
                .addTo(annotatedClass);

    }

    private void createKeyConstructor() {
        annotatedClass.addConstructor(
                ACC_PUBLIC,
                params(param(STRING_TYPE, "key")),
                NO_EXCEPTIONS,
                block(
                        isDSLObject(annotatedClass.getSuperClass()) ? ctorSuperS(args("key")) : ctorSuperS(),
                        assignS(propX(varX("this"), keyField.getName()), varX("key"))
                )
        );
    }

    private boolean isDSLObject(ClassNode classNode) {
        return getAnnotation(classNode, DSL_CONFIG_ANNOTATION) != null;
    }

    private void createCanonicalMethods() {
        if (!hasAnnotation(annotatedClass, EQUALS_HASHCODE_ANNOT)) {
            createHashCode(annotatedClass, false, false, false, null, null);
            createEquals(annotatedClass, false, false, true, null, null);
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
        if (fieldNode == keyField) return;
        if (fieldNode == ownerField) return;

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
        createPublicMethod(fieldNode.getName())
                .param(fieldNode.getType(), "value")
                .assignS(propX(varX("this"), fieldNode.getName()), varX("value"))
                .addTo(annotatedClass);
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

        createPublicMethod(fieldNode.getName())
                .arrayParam(elementType, "values")
                .statement(callX(propX(varX("this"), fieldNode.getName()), "addAll", varX("values")))
                .addTo(annotatedClass);

        createPublicMethod(getElementNameForCollectionField(fieldNode))
                .param(elementType, "value")
                .statement(callX(propX(varX("this"), fieldNode.getName()), "add", varX("value")))
                .addTo(annotatedClass);
    }

    private void createListOfDSLObjectMethods(FieldNode fieldNode, ClassNode elementType) {
        InnerClassNode contextClass = createInnerContextClassForListMembers(fieldNode, elementType);
        createContextClosure(fieldNode, contextClass);
    }

    private void createContextClosure(FieldNode fieldNode, InnerClassNode contextClass) {

        createPublicMethod(fieldNode.getName())
                .delegatingClosureParam(contextClass)
                .declS("context", ctorX(contextClass, varX("this")))
                .statements(delegateToClosure())
                .addTo(annotatedClass);
    }

    @NotNull
    private Statement[] delegateToClosure() {
        return new Statement[]{
                assignS(propX(varX("closure"), "delegate"), varX("context")),
                assignS(
                        propX(varX("closure"), "resolveStrategy"),
                        propX(classX(ClassHelper.CLOSURE_TYPE), "DELEGATE_FIRST")
                ),
                stmt(callX(varX("closure"), "call"))
        };
    }

    private InnerClassNode createInnerContextClassForListMembers(FieldNode fieldNode, ClassNode elementType) {
        InnerClassNode contextClass = createInnerContextClass(fieldNode);

        String methodName = getElementNameForCollectionField(fieldNode);

        FieldNode fieldKey = getKeyField(elementType);
        String targetOwner = getOwnerFieldName(elementType);

        if (!isAbstract(elementType)) {
            createPublicMethod(methodName)
                    .returning(elementType)
                    .optionalStringParam("key", fieldKey)
                    .delegatingClosureParam(elementType)
                    .declS("created", callX(elementType, "create", argsWithOptionalKeyAndClosure(fieldKey)))
                    .callS(getOuterInstanceXforField(fieldNode), "add", varX("created"))
                    .optionalAssignPropertyFromPropertyS("created", targetOwner, "this", "outerInstance", targetOwner)
                    .statement(returnS(varX("created")))
                    .addTo(contextClass);
        }

        if (!isFinal(elementType)) {
            createPublicMethod(methodName)
                    .returning(elementType)
                    .classParam("typeToCreate", elementType)
                    .optionalStringParam("key", fieldKey)
                    .delegatingClosureParam(elementType)
                    .declS("created", callX(varX("typeToCreate"), "newInstance", optionalKeyArg(fieldKey)))
                    .callS(getOuterInstanceXforField(fieldNode), "add", callX(varX("created"), "apply", varX("closure")))
                    .optionalAssignPropertyFromPropertyS("created", targetOwner, "this", "outerInstance", targetOwner)
                    .statement(returnS(varX("created")))
                    .addTo(contextClass);
        }

        List<ClassNode> classesList = getAlternativesList(fieldNode, elementType);
        for (ClassNode implementation : classesList) {
            createPublicMethod(uncapitalizedSimpleClassName(implementation))
                    .optionalStringParam("key", fieldKey)
                    .delegatingClosureParam(elementType)
                    .callS(varX("this"), methodName, argsWithClassOptionalKeyAndClosure(implementation, fieldKey))
                    .addTo(contextClass);
        }

        createPublicMethod(methodName)
                .param(elementType, "value")
                .callS(getOuterInstanceXforField(fieldNode), "add", varX("value"))
                .addTo(contextClass);

        createPublicMethod(USE_METHOD_NAME)
                .param(elementType, "value")
                .callS(getOuterInstanceXforField(fieldNode), "add", varX("value"))
                .optionalAssignPropertyFromPropertyS("value", targetOwner, "this", "outerInstance", targetOwner)
                .addTo(contextClass);

        if (fieldKey != null) {
            addDynamicKeyedCreatorMethod(contextClass, methodName);
        }

        annotatedClass.getModule().addClass(contextClass);

        return contextClass;
    }

    private Expression optionalKeyArg(FieldNode fieldKey) {
        return fieldKey != null ? args("key") : NO_ARGUMENTS;
    }

    private ArgumentListExpression argsWithOptionalKeyAndClosure(FieldNode fieldKey) {
        return fieldKey != null ? args("key", "closure") : args("closure");
    }

    private ArgumentListExpression argsWithClassOptionalKeyAndClosure(ClassNode type, FieldNode fieldKey) {
        return fieldKey != null ? args(classX(type), varX("key"), varX("closure")) : args(classX(type), varX("closure"));
    }

    private void addDynamicKeyedCreatorMethod(InnerClassNode contextClass, String methodName) {
        createPublicMethod("invokeMethod")
                .returning(ClassHelper.OBJECT_TYPE)
                .stringParam("name")
                .objectParam("args")
                .statement(ifElseS(andX(
                                        isOneX(new PropertyExpression(varX("args"), constX("length"), true)),
                                        isInstanceOfX(
                                                indexX(varX("args"), constX(0)),
                                                ClassHelper.CLOSURE_TYPE)
                                ),
                                stmt(callThisX(
                                        methodName,
                                        args(varX("name"), castX(ClassHelper.CLOSURE_TYPE, indexX(varX("args"), constX(0))))
                                )),
                                stmt(callSuperX("invokeMethod", args("name", "args")))
                        )
                )
                .addTo(contextClass);
    }

    private String uncapitalizedSimpleClassName(ClassNode node) {
        char[] name = node.getNameWithoutPackage().toCharArray();
        name[0] = Character.toLowerCase(name[0]);
        return new String(name);
    }

    private String setterName(FieldNode node) {
        char[] name = node.getName().toCharArray();
        name[0] = Character.toUpperCase(name[0]);
        return "set" + new String(name);
    }

    private List<ClassNode> getAlternativesList(AnnotatedNode fieldNode, ClassNode elementType) {
        AnnotationNode annotation = getAnnotation(fieldNode, DSL_FIELD_ANNOTATION);
        if (annotation == null) return Collections.emptyList();

        List<ClassNode> subclasses = getClassList(annotation, "alternatives");

        if (!subclasses.contains(elementType) && !isAbstract(elementType))
            subclasses.add(elementType);

        return subclasses;
    }

    @NotNull
    private Expression getOuterInstanceXforField(FieldNode fieldNode) {
        return propX(propX(varX("this"), "outerInstance"), fieldNode.getName());
    }

    @NotNull
    private InnerClassNode createInnerContextClass(FieldNode fieldNode) {
        InnerClassNode contextClass = new InnerClassNode(
                annotatedClass,
                annotatedClass.getName() + "$" + fieldNode.getName() + "Context",
                ACC_STATIC,
                ClassHelper.OBJECT_TYPE);

        contextClass.addField("outerInstance", 0, newClass(annotatedClass), null);
        contextClass.addConstructor(
                0,
                params(param(newClass(annotatedClass), "outerInstance")),
                NO_EXCEPTIONS,
                block(
                        assignS(
                                propX(varX("this"), "outerInstance"),
                                varX("outerInstance")
                        )
                )
        );
        return contextClass;
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

        createPublicMethod(methodName)
                .param(fieldNode.getType(), "values")
                .callS(propX(varX("this"), fieldNode.getName()), "putAll", varX("values"))
                .addTo(annotatedClass);

        String singleElementMethod = getElementNameForCollectionField(fieldNode);

        createPublicMethod(singleElementMethod)
                .param(keyType, "key")
                .param(valueType, "value")
                .callS(propX(varX("this"), fieldNode.getName()), "put", args("key", "value"))
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

        InnerClassNode contextClass = createInnerContextClassForMapMembers(fieldNode, keyType, elementType);
        createContextClosure(fieldNode, contextClass);
    }

    private InnerClassNode createInnerContextClassForMapMembers(FieldNode fieldNode, ClassNode keyType, ClassNode elementType) {
        InnerClassNode contextClass = createInnerContextClass(fieldNode);

        String methodName = getElementNameForCollectionField(fieldNode);
        String targetOwner = getOwnerFieldName(elementType);

        if (!isAbstract(elementType)) {
            createPublicMethod(methodName)
                    .returning(elementType)
                    .param(keyType, "key")
                    .delegatingClosureParam(elementType)
                    .declS("created", callX(elementType, "create", args("key", "closure")))
                    .callS(getOuterInstanceXforField(fieldNode), "put", args(varX("key"), varX("created")))
                    .optionalAssignPropertyFromPropertyS("created", targetOwner, "this", "outerInstance", targetOwner)
                    .statement(returnS(varX("created")))
                    .addTo(contextClass);
        }

        if (!isFinal(elementType)) {
            createPublicMethod(methodName)
                    .returning(elementType)
                    .classParam("typeToCreate", elementType)
                    .param(keyType, "key")
                    .delegatingClosureParam(elementType)
                    .declS("created", callX(varX("typeToCreate"), "newInstance", args("key")))
                    .callS(varX("created"), "apply", varX("closure"))
                    .callS(getOuterInstanceXforField(fieldNode), "put", args(varX("key"), varX("created")))
                    .optionalAssignPropertyFromPropertyS("created", targetOwner, "this", "outerInstance", targetOwner)
                    .statement(returnS(varX("created")))
                    .addTo(contextClass);
        }

        List<ClassNode> classesList = getAlternativesList(fieldNode, elementType);
        for (ClassNode implementation : classesList) {
            createPublicMethod(uncapitalizedSimpleClassName(implementation))
                    .param(keyType, "key")
                    .delegatingClosureParam(elementType)
                    .callS(varX("this"), methodName, args(classX(implementation), varX("key"), varX("closure")))
                    .addTo(contextClass);
        }

        //noinspection ConstantConditions
        createPublicMethod(methodName)
                .param(elementType, "value")
                .callS(getOuterInstanceXforField(fieldNode), "put",
                        args(propX(varX("value"), getKeyField(elementType).getName()), varX("value"))
                )
                .addTo(contextClass);

        //noinspection ConstantConditions
        createPublicMethod(USE_METHOD_NAME)
                .param(elementType, "value")
                .callS(getOuterInstanceXforField(fieldNode), "put",
                        args(propX(varX("value"), getKeyField(elementType).getName()), varX("value"))
                )
                .optionalAssignPropertyFromPropertyS("value", targetOwner, "this", "outerInstance", targetOwner)
                .addTo(contextClass);

        addDynamicKeyedCreatorMethod(contextClass, methodName);

        annotatedClass.getModule().addClass(contextClass);

        return contextClass;
    }

    private void createSingleDSLObjectClosureMethod(FieldNode fieldNode) {
        String methodName = fieldNode.getName();

        ClassNode fieldType = fieldNode.getType();
        FieldNode keyField = getKeyField(fieldType);
        String ownerFieldName = getOwnerFieldName(fieldType);

        if (!isAbstract(fieldType)) {
            createPublicMethod(methodName)
                    .returning(fieldType)
                    .optionalStringParam("key", keyField)
                    .delegatingClosureParam(fieldType)
                    .declS("created", callX(fieldType, "create", argsWithOptionalKeyAndClosure(keyField)))
                    .assignS(propX(varX("this"), fieldNode.getName()), varX("created"))
                    .optionalAssignThisToPropertyS("created", ownerFieldName, ownerFieldName)
                    .statement(returnS(varX("created")))
                    .addTo(annotatedClass);
        }

        if (!isFinal(fieldType)) {
            createPublicMethod(methodName)
                    .returning(fieldType)
                    .classParam("typeToCreate", fieldType)
                    .optionalStringParam("key", keyField)
                    .delegatingClosureParam(fieldType)
                    .declS("created", callX(varX("typeToCreate"), "newInstance", optionalKeyArg(keyField)))
                    .assignS(propX(varX("this"), fieldNode.getName()), callX(varX("created"), "apply", varX("closure")))
                    .optionalAssignThisToPropertyS("created", ownerFieldName, ownerFieldName)
                    .statement(returnS(varX("created")))
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
        boolean hasExistingApply = hasDeclaredMethod(annotatedClass, "apply", 1);
        if (hasExistingApply && hasDeclaredMethod(annotatedClass, "_apply", 1)) return;

        createPublicMethod(hasExistingApply ? "_apply" : "apply")
                .returning(newClass(annotatedClass))
                .delegatingClosureParam(annotatedClass)
                .assignS(propX(varX("closure"), "delegate"), varX("this"))
                .assignS(
                        propX(varX("closure"), "resolveStrategy"),
                        propX(classX(ClassHelper.CLOSURE_TYPE), "DELEGATE_FIRST")
                )
                .callS(varX("closure"), "call")
                .statement(returnS(varX("this")))
                .addTo(annotatedClass);
    }

    private void createFactoryMethods() {

        if (keyField == null)
            createSimpleFactoryMethod();
        else
            createFactoryMethodWithKeyParameter();
    }

    private void createFactoryMethodWithKeyParameter() {
        boolean hasExistingFactory = hasDeclaredMethod(annotatedClass, "create", 2);
        if (hasExistingFactory && hasDeclaredMethod(annotatedClass, "_create", 2)) return;

        createPublicMethod(hasExistingFactory ? "_create" : "create")
                .returning(newClass(annotatedClass))
                .mod(Opcodes.ACC_STATIC)
                .stringParam("name")
                .delegatingClosureParam(annotatedClass)
                .statement(returnS(callX(
                                callX(
                                        ctorX(annotatedClass, args("name")),
                                        "copyFrom",
                                        propX(classX(annotatedClass), TEMPLATE_FIELD_NAME)
                                ),
                                "apply", varX("closure")
                        )
                ))
                .addTo(annotatedClass);
    }

    private void createSimpleFactoryMethod() {
        boolean hasExistingFactory = hasDeclaredMethod(annotatedClass, "create", 1);
        if (hasExistingFactory && hasDeclaredMethod(annotatedClass, "_create", 1)) return;

        createPublicMethod(hasExistingFactory ? "_create" : "create")
                .returning(newClass(annotatedClass))
                .mod(Opcodes.ACC_STATIC)
                .delegatingClosureParam(annotatedClass)
                .statement(returnS(callX(
                                        callX(
                                                ctorX(annotatedClass),
                                                "copyFrom",
                                                propX(classX(annotatedClass), TEMPLATE_FIELD_NAME)
                                        ),
                                        "apply", varX("closure"))
                        )
                )
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

        ClassNode ancestor = getHighestAncestorDSLObject(target);

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

    private ClassNode getHighestAncestorDSLObject(ClassNode target) {
        return getHierarchyOfDSLObjectAncestors(target).getFirst();
    }

    private List<FieldNode> getAnnotatedFieldsOfHierarchy(ClassNode target, ClassNode annotation) {
        List<FieldNode> result = new ArrayList<FieldNode>();

        for (ClassNode level : getHierarchyOfDSLObjectAncestors(target)) {
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

    private Deque<ClassNode> getHierarchyOfDSLObjectAncestors(ClassNode target) {
        return getHierarchyOfDSLObjectAncestors(new LinkedList<ClassNode>(), target);
    }

    private Deque<ClassNode> getHierarchyOfDSLObjectAncestors(Deque<ClassNode> hierarchy, ClassNode target) {
        if (!isDSLObject(target)) return hierarchy;

        hierarchy.addFirst(target);
        return getHierarchyOfDSLObjectAncestors(hierarchy, target.getSuperClass());
    }

    private AnnotationNode getAnnotation(AnnotatedNode field, ClassNode type) {
        List<AnnotationNode> annotation = field.getAnnotations(type);
        return annotation.isEmpty() ? null : annotation.get(0);
    }

    private void addCompileError(String msg, ASTNode node) {
        SyntaxException se = new SyntaxException(msg, node.getLineNumber(), node.getColumnNumber());
        sourceUnit.getErrorCollector().addFatalError(new SyntaxErrorMessage(se, sourceUnit));
    }

    public void addCompileWarning(String msg, ASTNode node) {
        // TODO Need to convert node into CST node?
        //sourceUnit.getErrorCollector().addWarning(WarningMessage.POSSIBLE_ERRORS, msg, node, sourceUnit);
    }
}
