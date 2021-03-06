/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2015 AS3Boyan
 * Copyright 2014-2014 Elias Ku
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
package com.intellij.plugins.haxe.ide.annotator;

import com.intellij.lang.annotation.Annotation;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.model.*;
import com.intellij.plugins.haxe.model.fixer.HaxeFixer;
import com.intellij.plugins.haxe.model.type.HaxeTypeResolver;
import com.intellij.plugins.haxe.model.type.SpecificTypeReference;
import com.intellij.plugins.haxe.util.*;
import com.intellij.psi.*;
import org.apache.commons.lang.StringUtils;

import java.util.*;

public class HaxeSemanticAnnotator implements Annotator {
  @Override
  public void annotate(PsiElement element, AnnotationHolder holder) {
    analyzeSingle(element, holder);
  }

  static void analyzeSingle(final PsiElement element, AnnotationHolder holder) {
    if (element instanceof HaxePackageStatement) {
      PackageChecker.check((HaxePackageStatement)element, holder);
    } else if (element instanceof HaxeMethod) {
      MethodChecker.check((HaxeMethod)element, holder);
    } else if (element instanceof HaxeClass) {
      ClassChecker.check((HaxeClass)element, holder);
    } if (element instanceof HaxeType) {
      TypeChecker.check((HaxeType)element, holder);
    } if (element instanceof HaxeVarDeclaration) {
      FieldChecker.check((HaxeVarDeclaration)element, holder);
    }
  }
}

class TypeTagChecker {
  public static void check(final PsiElement erroredElement, final HaxeTypeTag tag, final HaxeVarInit initExpression, boolean requireConstant, final AnnotationHolder holder) {
    final SpecificTypeReference type1 = HaxeTypeResolver.getTypeFromTypeTag(tag);
    final SpecificTypeReference type2 = HaxeTypeResolver.getPsiElementType(initExpression);
    final HaxeDocumentModel document = HaxeDocumentModel.fromElement(tag);
    if (!type1.canAssign(type2)) {
      // @TODO: Move to bundle
      Annotation annotation = holder.createErrorAnnotation(erroredElement, "Incompatible type " + type1 + " can't be assigned from " + type2);
      annotation.registerFix(new HaxeFixer("Change type") {
        @Override
        public void run() {
          document.replaceElementText(tag, ":" + type2.toStringWithoutConstant());
        }
      });
      annotation.registerFix(new HaxeFixer("Remove init") {
        @Override
        public void run() {
          document.replaceElementText(initExpression, "", StripSpaces.BEFORE);
        }
      });
    } else if (requireConstant && type2.getConstant() == null) {
      // @TODO: Move to bundle
      holder.createErrorAnnotation(erroredElement, "Parameter default type should be constant but was " + type2);
    }
  }
}

class FieldChecker {
  public static void check(final HaxeVarDeclaration var, final AnnotationHolder holder) {
    HaxeFieldModel field = var.getModel();
    if (field.isProperty()) {
      checkProperty(field, holder);
    }
    if (field.hasInitializer() && field.hasTypeTag()) {
      TypeTagChecker.check(field.getPsi(), field.getTypeTagPsi(), field.getInitializerPsi(), false, holder);
    }
  }

  public static void checkProperty(final HaxeFieldModel field, final AnnotationHolder holder) {
    final HaxeDocumentModel document = field.getDocument();

    if (field.getGetterPsi() != null && !field.getGetterType().isValidGetter()) {
      holder.createErrorAnnotation(field.getGetterPsi(), "Invalid getter accessor");
    }

    if (field.getSetterPsi() != null && !field.getSetterType().isValidSetter()) {
      holder.createErrorAnnotation(field.getSetterPsi(), "Invalid setter accessor");
    }

    if (field.getGetterType() == HaxeAccessorType.GET) {
      final String methodName = "get_" + field.getName();
      HaxeMethodModel method = field.getDeclaringClass().getMethod(methodName);
      if (method == null) {
        Annotation annotation = holder.createErrorAnnotation(field.getGetterPsi(), "Can't find method " + methodName);
        annotation.registerFix(new HaxeFixer("Add method") {
          @Override
          public void run() {
            field.getDeclaringClass().addMethod(methodName);
          }
        });
      }
    }

    if (field.getSetterType() == HaxeAccessorType.SET) {
      final String methodName = "set_" + field.getName();
      HaxeMethodModel method = field.getDeclaringClass().getMethod(methodName);
      if (method == null) {
        Annotation annotation = holder.createErrorAnnotation(field.getSetterPsi(), "Can't find method " + methodName);
        annotation.registerFix(new HaxeFixer("Add method") {
          @Override
          public void run() {
            field.getDeclaringClass().addMethod(methodName);
          }
        });
      }
    }

    if (field.isProperty() && !field.isRealVar() && field.hasInitializer()) {
      final HaxeVarInit psi = field.getInitializerPsi();
      Annotation annotation = holder.createErrorAnnotation(
        field.getInitializerPsi(),
        "This field cannot be initialized because it is not a real variable"
      );
      annotation.registerFix(new HaxeFixer("Remove init") {
        @Override
        public void run() {
          document.replaceElementText(psi, "", StripSpaces.BEFORE);
        }
      });
      annotation.registerFix(new HaxeFixer("Add @:isVar") {
        @Override
        public void run() {
          field.getModifiers().addModifier(HaxeModifierType.IS_VAR);
        }
      });
      if (field.getSetterPsi() != null) {
        annotation.registerFix(new HaxeFixer("Make setter null") {
          @Override
          public void run() {
            document.replaceElementText(field.getSetterPsi(), "null");
          }
        });
      }
    }
  }
}

class TypeChecker {
  static public void check(final HaxeType type, final AnnotationHolder holder) {
    check(type.getReferenceExpression().getIdentifier(), holder);
  }

  static public void check(final PsiIdentifier identifier, final AnnotationHolder holder) {
    if (identifier == null) return;
    final String typeName = identifier.getText();
    if (!HaxeClassModel.isValidClassName(typeName)) {
      Annotation annotation = holder.createErrorAnnotation(identifier, "Type name must start by upper case");
      annotation.registerFix(new HaxeFixer("Change name") {
        @Override
        public void run() {
          HaxeDocumentModel.fromElement(identifier).replaceElementText(
            identifier,
            typeName.substring(0, 1).toUpperCase() + typeName.substring(1)
          );
        }
      });
    }
  }
}

class ClassChecker {
  static public void check(final HaxeClass clazzPsi, final AnnotationHolder holder) {
    HaxeClassModel clazz = clazzPsi.getModel();
    checkDuplicatedFields(clazz, holder);
    checkClassName(clazz, holder);
    checkInterfaces(clazz, holder);
    checkExtends(clazz, holder);
    checkInterfacesMethods(clazz, holder);
  }

  static private void checkDuplicatedFields(final HaxeClassModel clazz, final AnnotationHolder holder) {
    Map<String, HaxeMemberModel> map = new HashMap<String, HaxeMemberModel>();
    Set<HaxeMemberModel> repeatedMembers = new HashSet<HaxeMemberModel>();
    for (HaxeMemberModel member : clazz.getMembersSelf()) {
      final String memberName = member.getName();
      HaxeMemberModel repeatedMember = map.get(memberName);
      if (repeatedMember != null) {
        repeatedMembers.add(member);
        repeatedMembers.add(repeatedMember);
      } else {
        map.put(memberName, member);
      }
    }

    for (HaxeMemberModel member : repeatedMembers) {
      holder.createErrorAnnotation(member.getNameOrBasePsi(), "Duplicate class field declaration : " + member.getName());
    }


    //Duplicate class field declaration
  }

  static private void checkClassName(final HaxeClassModel clazz, final AnnotationHolder holder) {
    TypeChecker.check(clazz.getNamePsi(), holder);
  }

  static public void checkExtends(final HaxeClassModel clazz, final AnnotationHolder holder) {
    HaxeClassReferenceModel reference = clazz.getParentClassReference();
    if (reference != null) {
      HaxeClassModel aClass1 = reference.getHaxeClass();
      if (aClass1 != null && !aClass1.isClass()) {
        // @TODO: Move to bundle
        holder.createErrorAnnotation(reference.getPsi(), "Not a class");
      }
    }
  }

  static public void checkInterfaces(final HaxeClassModel clazz, final AnnotationHolder holder) {
    for (HaxeClassReferenceModel interfaze : clazz.getImplementingInterfaces()) {
      if (interfaze.getHaxeClass() == null || !interfaze.getHaxeClass().isInterface()) {
        // @TODO: Move to bundle
        holder.createErrorAnnotation(interfaze.getPsi(), "Not an interface");
      }
    }
  }

  static public void checkInterfacesMethods(final HaxeClassModel clazz, final AnnotationHolder holder) {
    for (HaxeClassReferenceModel reference : clazz.getImplementingInterfaces()) {
      checkInterfaceMethods(clazz, reference, holder);
    }
  }

  static public void checkInterfaceMethods(final HaxeClassModel clazz, final HaxeClassReferenceModel intReference, final AnnotationHolder holder) {
    final List<HaxeMethodModel> missingMethods = new ArrayList<HaxeMethodModel>();
    final List<String> missingMethodsNames = new ArrayList<String>();

    if (intReference.getHaxeClass() != null) {
      for (HaxeMethodModel method : intReference.getHaxeClass().getMethods()) {
        if (!method.isStatic()) {
          if (!clazz.hasMethodSelf(method.getName())) {
            missingMethods.add(method);
            missingMethodsNames.add(method.getName());
          }
        }
      }
    }

    if (missingMethods.size() > 0) {
      // @TODO: Move to bundle
      Annotation annotation = holder.createErrorAnnotation(intReference.getPsi(), "Not implemented methods: " + StringUtils.join(missingMethodsNames, ", "));
      annotation.registerFix(new HaxeFixer("Implement methods") {
        @Override
        public void run() {
          clazz.addMethodsFromPrototype(missingMethods);
        }
      });
    }
  }
}

class MethodChecker {
  static public void check(final HaxeMethod methodPsi, final AnnotationHolder holder) {
    final HaxeMethodModel currentMethod = methodPsi.getModel();
    checkTypeTagInInterfacesAndExternClass(currentMethod, holder);
    checkMethodArguments(currentMethod, holder);
    checkOverride(methodPsi, holder);
    if (HaxeSemanticAnnotatorConfig.ENABLE_EXPERIMENTAL_BODY_CHECK) {
      MethodBodyChecker.check(methodPsi, holder);
    }
    //currentMethod.getBodyPsi()
  }

  static public void checkTypeTagInInterfacesAndExternClass(final HaxeMethodModel currentMethod, final AnnotationHolder holder) {
    HaxeClassModel currentClass = currentMethod.getDeclaringClass();
    if (currentClass.isExtern() || currentClass.isInterface()) {
      if (currentMethod.getReturnTypeTagPsi() == null) {
        holder.createErrorAnnotation(currentMethod.getNameOrBasePsi(), HaxeBundle.message("haxe.semantic.type.required"));
      }
      for (final HaxeParameterModel param : currentMethod.getParameters()) {
        if (param.getTypeTagPsi() == null) {
          holder.createErrorAnnotation(param.getNameOrBasePsi(), HaxeBundle.message("haxe.semantic.type.required"));
        }
      }
    }
  }

  static public void checkMethodArguments(final HaxeMethodModel currentMethod, final AnnotationHolder holder) {
    boolean hasOptional = false;
    HashMap<String, PsiElement> argumentNames = new HashMap<String, PsiElement>();
    for (final HaxeParameterModel param : currentMethod.getParameters()) {
      String paramName = param.getName();

      if (param.hasOptionalPsi() && param.getVarInitPsi() != null) {
        // @TODO: Move to bundle
        holder.createWarningAnnotation(param.getOptionalPsi(), "Optional not needed when specified an init value");
      }
      if (param.getVarInitPsi() != null && param.getTypeTagPsi() != null) {
        TypeTagChecker.check(
          param.getPsi(),
          param.getTypeTagPsi(),
          param.getVarInitPsi(),
          true,
          holder
        );
      }
      if (param.isOptional()) {
        hasOptional = true;
      } else if (hasOptional) {
        // @TODO: Move to bundle
        holder.createWarningAnnotation(param.getPsi(), "Non-optional argument after optional argument");
      }

      if (argumentNames.containsKey(paramName)) {
        // @TODO: Move to bundle
        holder.createWarningAnnotation(param.getNameOrBasePsi(), "Repeated argument name '" + paramName + "'");
        holder.createWarningAnnotation(argumentNames.get(paramName), "Repeated argument name '" + paramName + "'");
      } else {
        argumentNames.put(paramName, param.getNameOrBasePsi());
      }
    }
  }

  static public void checkOverride(final HaxeMethod methodPsi, final AnnotationHolder holder) {
    final HaxeMethodModel currentMethod = methodPsi.getModel();
    final HaxeClassModel currentClass = currentMethod.getDeclaringClass();
    final HaxeModifiersModel currentModifiers = currentMethod.getModifiers();

    final HaxeClassReferenceModel parentClass = (currentClass != null) ? currentClass.getParentClassReference() : null;
    final HaxeMethodModel parentMethod = ((parentClass != null) && parentClass.getHaxeClass() != null) ? parentClass.getHaxeClass().getMethod(currentMethod.getName()) : null;
    final HaxeModifiersModel parentModifiers = (parentMethod != null) ? parentMethod.getModifiers() : null;

    boolean requiredOverride = false;

    if (currentMethod.isConstructor()) {
      requiredOverride = false;
      if (currentModifiers.hasModifier(HaxeModifierType.STATIC)) {
        // @TODO: Move to bundle
        holder.createErrorAnnotation(currentMethod.getNameOrBasePsi(), "Constructor can't be static").registerFix(
          new HaxeFixer("Remove static") {
            @Override
            public void run() {
              currentModifiers.removeModifier(HaxeModifierType.STATIC);
            }
          }
        );
      }
    } else if (currentMethod.isStaticInit()) {
      requiredOverride = false;
      if (!currentModifiers.hasModifier(HaxeModifierType.STATIC)) {
        holder.createErrorAnnotation(currentMethod.getNameOrBasePsi(), "__init__ must be static").registerFix(
          new HaxeFixer("Add static") {
            @Override
            public void run() {
              currentModifiers.addModifier(HaxeModifierType.STATIC);
            }
          }
        );
      }
    }
    else if (parentMethod != null) {
      requiredOverride = true;

      if (parentModifiers.hasAnyModifier(HaxeModifierType.INLINE, HaxeModifierType.STATIC, HaxeModifierType.FINAL)) {
        Annotation annotation = holder.createErrorAnnotation(currentMethod.getNameOrBasePsi(), "Can't override static, inline or final methods");
        for (HaxeModifierType mod : new HaxeModifierType[] { HaxeModifierType.FINAL, HaxeModifierType.INLINE, HaxeModifierType.STATIC }) {
          if (parentModifiers.hasModifier(mod)) {
            annotation.registerFix(new RemoveModifierIntent("Remove " + mod.s + " from " + parentMethod.getFullName(), parentModifiers, mod));
          }
        }
      }

      if (currentModifiers.getVisibility().hasLowerVisibilityThan(parentModifiers.getVisibility())) {
        Annotation annotation = holder.createErrorAnnotation(currentMethod.getNameOrBasePsi(), "Field " + currentMethod.getName() + " has less visibility (public/private) than superclass one");
        annotation.registerFix(
          new HaxeFixer("Change current method visibility") {
            @Override
            public void run() {
              currentModifiers.replaceVisibility(parentModifiers.getVisibility());
            }
          }
        );
        annotation.registerFix(
          new HaxeFixer("Change parent method visibility") {
            @Override
            public void run() {
              parentModifiers.replaceVisibility(currentModifiers.getVisibility());
            }
          }
        );
      }
    }

    //System.out.println(aClass);
    if (currentModifiers.hasModifier(HaxeModifierType.OVERRIDE) && !requiredOverride) {
      holder.createErrorAnnotation(currentModifiers.getModifierPsi(HaxeModifierType.OVERRIDE), "Overriding nothing").registerFix(
        new RemoveModifierIntent("Remove override", currentModifiers, HaxeModifierType.OVERRIDE)
      );
    } else if (!currentModifiers.hasModifier(HaxeModifierType.OVERRIDE) && requiredOverride) {
      holder.createErrorAnnotation(currentMethod.getNameOrBasePsi(), "Must override").registerFix(
        new HaxeFixer("Add override") {
          @Override
          public void run() {
            currentModifiers.addModifier(HaxeModifierType.OVERRIDE);
          }
        }
      );
    }
  }
}

class PackageChecker {
  static public void check(final HaxePackageStatement element, final AnnotationHolder holder) {
    final HaxeReferenceExpression expression = ((HaxePackageStatement)element).getReferenceExpression();
    String packageName = (expression != null) ? expression.getText() : "";
    PsiDirectory fileDirectory = element.getContainingFile().getParent();
    List<PsiFileSystemItem> fileRange = PsiFileUtils.getRange(PsiFileUtils.findRoot(fileDirectory), fileDirectory);
    fileRange.remove(0);
    String actualPath = PsiFileUtils.getListPath(fileRange);
    final String actualPackage = actualPath.replace('/', '.');
    final String actualPackage2 = HaxeResolveUtil.getPackageName(element.getContainingFile());
    // @TODO: Should use HaxeResolveUtil

    for (String s : StringUtils.split(packageName, '.')) {
      if (!s.substring(0, 1).toLowerCase().equals(s.substring(0, 1))) {
        //HaxeSemanticError.addError(element, new HaxeSemanticError("Package name '" + s + "' must start with a lower case character"));
        // @TODO: Move to bundle
        holder.createErrorAnnotation(element, "Package name '" + s + "' must start with a lower case character");
      }
    }

    if (!packageName.equals(actualPackage)) {
      holder.createErrorAnnotation(
        element,
        "Invalid package name! '" + packageName + "' should be '" + actualPackage + "'").registerFix(
        new HaxeFixer("Fix package") {
          @Override
          public void run() {
            Document document =
              PsiDocumentManager.getInstance(element.getProject()).getDocument(element.getContainingFile());

            if (expression != null) {
              TextRange range = expression.getTextRange();
              document.replaceString(range.getStartOffset(), range.getEndOffset(), actualPackage);
            }
            else {
              int offset =
                element.getNode().findChildByType(HaxeTokenTypes.OSEMI).getTextRange().getStartOffset();
              document.replaceString(offset, offset, actualPackage);
            }
          }
        }
      );
    }
  }
}

class RemoveModifierIntent extends HaxeFixer {
  private HaxeModifiersModel modifiers;
  private HaxeModifierType modifierToRemove;

  public RemoveModifierIntent(String text, HaxeModifiersModel modifiers, HaxeModifierType modifierToRemove) {
    super(text);
    this.modifiers = modifiers;
    this.modifierToRemove = modifierToRemove;
  }

  @Override
  public void run() {
    modifiers.removeModifier(modifierToRemove);
  }
}

class MethodBodyChecker {
  public static void check(HaxeMethod psi, AnnotationHolder holder) {
    final HaxeMethodModel method = psi.getModel();
    HaxeTypeResolver.getPsiElementType(method.getBodyPsi(), holder);
  }
}