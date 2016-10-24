package com.appunite;


import com.google.auto.common.MoreElements;
import com.google.auto.common.MoreTypes;
import com.google.auto.service.AutoService;
import com.google.auto.value.extension.AutoValueExtension;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

import static javax.lang.model.element.Modifier.FINAL;

@AutoService(AutoValueExtension.class)
public class AutoValueBaseAdapterItemExtension extends AutoValueExtension {

    private static final class Property {
        final String methodName;
        final String humanName;
        final ExecutableElement element;
        final TypeName type;
        final ImmutableSet<String> annotations;
        String payloadName;

        Property(String humanName, ExecutableElement element) {
            this.methodName = element.getSimpleName().toString();
            this.humanName = humanName;
            this.element = element;
            type = TypeName.get(element.getReturnType());
            annotations = buildAnnotations(element);

            final AdapterPayload payload = element.getAnnotation(AdapterPayload.class);
            if (payload != null) {
                payloadName = payload.value();
            }
        }

        private ImmutableSet<String> buildAnnotations(ExecutableElement element) {
            final ImmutableSet.Builder<String> builder = ImmutableSet.builder();
            for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
                builder.add(annotation.getAnnotationType().asElement().getSimpleName().toString());
            }
            return builder.build();
        }
    }

    @Override
    public boolean applicable(Context context) {
        final TypeMirror autoValueClass = context.autoValueClass().asType();
        final ExecutableElement matches = findMatches(context);
        if (matches != null) {
            context.processingEnvironment().getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Manual implementation of Detectable#matches(T item) found when processing "
                            + autoValueClass.toString() + ". Remove this so auto-value-base-adapter-item can automatically generate the "
                            + "implementation for you.", matches);
        }
        final ExecutableElement same = findSame(context);
        if (same != null) {
            context.processingEnvironment().getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Manual implementation of Detectable#same(T item) found when processing "
                            + autoValueClass.toString() + ". Remove this so auto-value-base-adapter-item can automatically generate the "
                            + "implementation for you.", same);
        }

        final TypeMirror detectable = context.processingEnvironment().getElementUtils()
                .getTypeElement("com.appunite.rx.android.adapter.BaseAdapterItem").asType();
        return detectable != null && context.processingEnvironment().getTypeUtils().isAssignable(autoValueClass, detectable);
    }

    @Override
    public Set<ExecutableElement> consumeMethods(Context context) {
        final ImmutableSet.Builder<ExecutableElement> methods = new ImmutableSet.Builder<>();
        for (ExecutableElement element : context.abstractMethods()) {
            switch (element.getSimpleName().toString()) {
                case "matches":
                    methods.add(element);
                    break;
                case "same":
                    methods.add(element);
                    break;
                case "adapterId":
                    methods.add(element);
                    break;
                case "changePayload":
                    methods.add(element);
                    break;
            }
        }
        return methods.build();
    }

    @Override
    public String generateClass(Context context, String className, String classToExtend, boolean isFinal) {
        final ProcessingEnvironment env = context.processingEnvironment();
        final ImmutableList<Property> properties = readProperties(context.properties());
        validateProperties(env, properties);

        final TypeName type = ClassName.get(context.packageName(), className);

        TypeSpec.Builder subclass = TypeSpec.classBuilder(className)
                .addModifiers(FINAL)
                .addMethod(generateConstructor(properties))
                .addMethod(generateMatches(properties, type))
                .addMethod(generateSame());

        for (ExecutableElement element : context.abstractMethods()) {
            if (element.getSimpleName().toString().equals("adapterId")) {
                subclass.addMethod(generateAdapterId(properties));
            }
            if (element.getSimpleName().toString().equals("changePayload")) {
                subclass.addMethod(generateChangePayload(properties, type));
            }
        }

        final ClassName superClass = ClassName.get(context.packageName(), classToExtend);
        final List<? extends TypeParameterElement> tpes = context.autoValueClass().getTypeParameters();
        if (tpes.isEmpty()) {
            subclass.superclass(superClass);
        } else {
            final TypeName[] superTypeVariables = new TypeName[tpes.size()];
            for (int i = 0, tpesSize = tpes.size(); i < tpesSize; i++) {
                final TypeParameterElement tpe = tpes.get(i);
                subclass.addTypeVariable(TypeVariableName.get(tpe));
                superTypeVariables[i] = TypeVariableName.get(tpe.getSimpleName().toString());
            }
            subclass.superclass(ParameterizedTypeName.get(superClass, superTypeVariables));
        }

        final JavaFile javaFile = JavaFile.builder(context.packageName(), subclass.build()).build();
        return javaFile.toString();
    }

    private void validateProperties(ProcessingEnvironment env, List<Property> properties) {
        boolean containsAdapterId = false;
        for (Property property : properties) {
            if (property.annotations.contains("AdapterId")) {
                if (!containsAdapterId) {
                    containsAdapterId = true;
                    final TypeMirror type = property.element.getReturnType();
                    if (!TypeName.get(type).equals(ClassName.get("java.lang", "String"))) {
                        env.getMessager().printMessage(Diagnostic.Kind.ERROR, "AdapterId can only be type of String ", property.element);
                    }
                } else {
                    env.getMessager().printMessage(Diagnostic.Kind.ERROR, "Only one field can be marked as AdapterId ", property.element);
                }
            }
        }
    }

    private MethodSpec generateSame() {
        final ParameterSpec var1 = baseAdapterItemSpec();
        return MethodSpec.methodBuilder("same")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .addParameter(var1)
                .addStatement("return equals($N)", var1).build();
    }

    private MethodSpec generateChangePayload(List<Property> properties, TypeName type) {
        final ParameterSpec var1 = baseAdapterItemSpec();
        final CodeBlock.Builder block = CodeBlock.builder();
        block.addStatement("final List<Object> changes = new $T<>()", ArrayList.class);

        final ClassName objects = ClassName.get("com.appunite.rx.internal", "Objects");

        for (Property property : properties) {
            if (property.annotations.contains("AdapterPayload")) {
                block.beginControlFlow("if(!$T.equal($N(), (($T) $N).$N()))", objects, property.methodName, type, var1, property.methodName);
                block.addStatement("changes.add($S)", property.payloadName);
                block.endControlFlow();
            }
        }

        block.addStatement("return changes");

        return MethodSpec.methodBuilder("changePayload")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(List.class)
                .addParameter(var1)
                .addCode(block.build()).build();
    }

    private MethodSpec generateAdapterId(List<Property> properties) {
        final CodeBlock.Builder block = CodeBlock.builder();
        boolean returned = false;
        for (Property property : properties) {
            if (property.annotations.contains("AdapterId")) {
                returned = true;
                block.addStatement("return $N().hashCode()", property.methodName);
                break;
            }
        }

        final MethodSpec.Builder builder = MethodSpec.methodBuilder("adapterId")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(long.class);
        if (returned) {
            builder.addCode(block.build());
        } else {
            builder.addStatement("return NO_ID");
        }

        return builder.build();
    }

    private MethodSpec generateMatches(List<Property> properties, TypeName type) {
        final ParameterSpec var1 = baseAdapterItemSpec();

        MethodSpec.Builder builder = MethodSpec.methodBuilder("matches")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .addParameter(var1);

        CodeBlock.Builder block = CodeBlock.builder();
        block.add("return $N instanceof $T", var1, type);

        for (Property property : properties) {
            if (property.annotations.contains("AdapterId")) {
                block.add(" && $T.equal($N(), (($T) $N).$N())", ClassName.get("com.appunite.rx.internal", "Objects"), property.methodName, type, var1, property.methodName);
            }
        }

        block.addStatement("");
        return builder.addCode(block.build()).build();
    }

    private MethodSpec generateConstructor(List<Property> properties) {
        final List<ParameterSpec> params = Lists.newArrayListWithCapacity(properties.size());
        for (Property property : properties) {
            params.add(ParameterSpec.builder(property.type, property.humanName).build());
        }

        final MethodSpec.Builder builder = MethodSpec.constructorBuilder()
                .addParameters(params);

        final StringBuilder superFormat = new StringBuilder("super(");
        final List<ParameterSpec> args = new ArrayList<>();
        for (int i = 0, n = params.size(); i < n; i++) {
            args.add(params.get(i));
            superFormat.append("$N");
            if (i < n - 1) superFormat.append(", ");
        }
        superFormat.append(")");
        builder.addStatement(superFormat.toString(), args.toArray());

        return builder.build();
    }

    private ImmutableList<Property> readProperties(Map<String, ExecutableElement> properties) {
        final ImmutableList.Builder<Property> values = ImmutableList.builder();
        for (Map.Entry<String, ExecutableElement> entry : properties.entrySet()) {
            values.add(new Property(entry.getKey(), entry.getValue()));
        }
        return values.build();
    }


    private static ExecutableElement findMatches(Context context) {
        final ProcessingEnvironment env = context.processingEnvironment();
        final TypeMirror baseAdapterItem = env.getElementUtils().getTypeElement("com.appunite.rx.android.adapter.BaseAdapterItem").asType();
        for (ExecutableElement element : MoreElements.getLocalAndInheritedMethods(context.autoValueClass(), env.getElementUtils())) {
            if (element.getSimpleName().contentEquals("matches")
                    && MoreTypes.isTypeOf(boolean.class, element.getReturnType())
                    && !element.getModifiers().contains(Modifier.ABSTRACT)) {
                final List<? extends VariableElement> parameters = element.getParameters();
                if (parameters.size() == 1
                        && env.getTypeUtils().isSameType(baseAdapterItem, parameters.get(0).asType())) {
                    return element;
                }
            }
        }
        return null;
    }

    private static ExecutableElement findSame(Context context) {
        final ProcessingEnvironment env = context.processingEnvironment();
        final TypeMirror baseAdapterItem = env.getElementUtils().getTypeElement("com.appunite.rx.android.adapter.BaseAdapterItem").asType();
        for (ExecutableElement element : MoreElements.getLocalAndInheritedMethods(context.autoValueClass(), env.getElementUtils())) {
            if (element.getSimpleName().contentEquals("same")
                    && MoreTypes.isTypeOf(boolean.class, element.getReturnType())
                    && !element.getModifiers().contains(Modifier.ABSTRACT)) {
                final List<? extends VariableElement> parameters = element.getParameters();
                if (parameters.size() == 1
                        && env.getTypeUtils().isSameType(baseAdapterItem, parameters.get(0).asType())) {
                    return element;
                }
            }
        }

        return null;
    }

    private ParameterSpec baseAdapterItemSpec() {
        return ParameterSpec
                .builder(ClassName.get("com.appunite.rx.android.adapter", "BaseAdapterItem"), "var1")
                .build();
    }
}
