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
package com.intellij.plugins.haxe.util;

import com.intellij.lang.ASTNode;
import com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets;
import com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.lang.psi.impl.AbstractHaxeNamedComponent;
import com.intellij.psi.*;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.mozilla.javascript.ast.VariableDeclaration;

import java.util.ArrayList;
import java.util.List;

public class HaxeTypeUtil {
  // @TODO: Check if cache works
  static public SpecificTypeReference getFieldOrMethodType(AbstractHaxeNamedComponent comp) {
    // @TODO: cache should check if any related type has changed, which return depends
    long stamp = comp.getContainingFile().getModificationStamp();
    if (comp._cachedType == null || comp._cachedTypeStamp != stamp) {
      comp._cachedType = _getFieldOrMethodType(comp);
      comp._cachedTypeStamp = stamp;
    }

    return comp._cachedType;
  }

  static private SpecificTypeReference _getFieldOrMethodType(AbstractHaxeNamedComponent comp) {
    try {
      if (comp instanceof PsiMethod) {
        return SpecificHaxeClassReference.ensure(getFunctionReturnType(comp));
      } else {
        return SpecificHaxeClassReference.ensure(getFieldType(comp));
      }
    } catch (Throwable e) {
      e.printStackTrace();
      return createPrimitiveType("Unknown", comp, null);
    }
  }

  static private SpecificTypeReference getFieldType(AbstractHaxeNamedComponent comp) {
    SpecificTypeReference type = getTypeFromTypeTag(comp);
    if (type != null) return type;
    // Here detect assignment
    if (comp instanceof HaxeVarDeclarationPart) {
      HaxeVarInit init = ((HaxeVarDeclarationPart)comp).getVarInit();
      if (init != null) {
        PsiElement child = init.getExpression().getFirstChild();
        SpecificTypeReference type1 = HaxeTypeUtil.getPsiElementType(child);
        HaxeVarDeclaration decl = ((HaxeVarDeclaration)comp.getParent());
        boolean isConstant = false;
        if (decl != null) {
          isConstant = decl.hasModifierProperty(HaxePsiModifier.INLINE);
          PsiModifierList modifierList = decl.getModifierList();
          //System.out.println(decl.getText());
        }
        return isConstant ? type1 : type1.withConstantValue(null);
      }
    }

    return null;
  }

  static private SpecificTypeReference getFunctionReturnType(AbstractHaxeNamedComponent comp) {
    SpecificTypeReference type = getTypeFromTypeTag(comp);
    if (type != null) return type;

    if (comp instanceof PsiMethod) {
      return getReturnTypeFromBody(((PsiMethod)comp).getBody());
    } else {
      throw new RuntimeException("Can't get the body of a no PsiMethod");
    }
  }

  static private SpecificTypeReference getTypeFromTypeTag(AbstractHaxeNamedComponent comp) {
    final HaxeTypeTag typeTag = PsiTreeUtil.getChildOfType(comp, HaxeTypeTag.class);
    if (typeTag == null) return null;
    final HaxeTypeOrAnonymous typeOrAnonymous = typeTag.getTypeOrAnonymous();
    final HaxeFunctionType functionType = typeTag.getFunctionType();

    if (typeOrAnonymous != null) {
      return getTypeFromTypeOrAnonymous(typeOrAnonymous);
    }

    //comp.getContainingFile().getNode().putUserData();

    if (functionType != null) {
      return getTypeFromFunctionType(functionType);
    }

    return null;
  }

  private static SpecificTypeReference getTypeFromFunctionType(HaxeFunctionType type) {
    ArrayList<SpecificTypeReference> references = new ArrayList<SpecificTypeReference>();
    for (HaxeTypeOrAnonymous anonymous : type.getTypeOrAnonymousList()) {
      references.add(getTypeFromTypeOrAnonymous(anonymous));
    }
    return new SpecificFunctionReference(references.toArray(new SpecificTypeReference[0]));
  }

  static private SpecificTypeReference getTypeFromType(@NotNull HaxeType type) {
    //System.out.println("Type:" + type);
    //System.out.println("Type:" + type.getText());
    HaxeReferenceExpression expression = type.getReferenceExpression();
    HaxeClassReference reference = new HaxeClassReference(expression.getText(), expression);
    HaxeTypeParam param = type.getTypeParam();
    ArrayList<SpecificTypeReference> references = new ArrayList<SpecificTypeReference>();
    if (param != null) {
      for (HaxeTypeListPart part : param.getTypeList().getTypeListPartList()) {
        for (HaxeTypeOrAnonymous anonymous : part.getTypeOrAnonymousList()) {
          references.add(getTypeFromTypeOrAnonymous(anonymous));
        }
      }
    }
    //type.getTypeParam();
    return SpecificHaxeClassReference.withGenerics(reference, references.toArray(SpecificHaxeClassReference.EMPTY));
  }

  static private SpecificTypeReference getTypeFromTypeOrAnonymous(@NotNull HaxeTypeOrAnonymous typeOrAnonymous) {
    // @TODO: Do a proper type resolving
    HaxeType type = typeOrAnonymous.getType();
    if (type != null) {
      return getTypeFromType(type);
    }
    return null;
  }

  static private SpecificTypeReference getReturnTypeFromBody(PsiCodeBlock body) {
    return getPsiElementType(body);
  }

  static public SpecificTypeReference getPsiElementType(PsiElement element) {
    //System.out.println("Handling element: " + element.getClass());
    if (element instanceof PsiCodeBlock) {
      SpecificTypeReference type = null;
      for (PsiElement childElement : element.getChildren()) {
        type = getPsiElementType(childElement);
        if (childElement instanceof HaxeReturnStatement) {
          //System.out.println("HaxeReturnStatement:" + type);
          return type;
        }
      }
      return type;
    }

    if (element instanceof HaxeReturnStatement) {
      return getPsiElementType(element.getChildren()[0]);
    }

    if (element instanceof HaxeNewExpression) {
      return getTypeFromType(((HaxeNewExpression)element).getType());
    }

    if (element instanceof HaxeCallExpression) {
      SpecificTypeReference functionType = getPsiElementType(((HaxeCallExpression)element).getExpression());
      // @TODO: resolve the function type return type
      return functionType;
    }

    if (element instanceof HaxeLiteralExpression) {
      return getPsiElementType(element.getFirstChild());
    }

    if (element instanceof HaxeStringLiteralExpression) {
      // @TODO: check if it has string interpolation inside, in that case text is not constant
      return createPrimitiveType("String", element, ((HaxeStringLiteralExpression)element).getCanonicalText());
    }

    if (element instanceof PsiJavaToken) {
      IElementType type = ((PsiJavaToken)element).getTokenType();

      if (type == HaxeTokenTypes.LITINT || type == HaxeTokenTypes.LITHEX || type == HaxeTokenTypes.LITOCT) {
        return createPrimitiveType("Int", element, Integer.decode(element.getText()));
      } else if (type == HaxeTokenTypes.LITFLOAT) {
        return createPrimitiveType("Float", element, Float.parseFloat(element.getText()));
      } else if (type == HaxeTokenTypes.KFALSE || type == HaxeTokenTypes.KTRUE) {
        return createPrimitiveType("Bool", element, type == HaxeTokenTypes.KTRUE);
      } else {
        //System.out.println("Unhandled token type: " + tokenType);
        return createPrimitiveType("Dynamic", element, null);
      }
    }

    if (element instanceof PsiIfStatement) {
      PsiStatement thenBranch = ((PsiIfStatement)element).getThenBranch();
      PsiStatement elseBranch = ((PsiIfStatement)element).getElseBranch();
      if (elseBranch != null) {
        return HaxeTypeUnifier.unify(getPsiElementType(thenBranch), getPsiElementType(elseBranch));
      } else {
        return getPsiElementType(thenBranch);
      }
    }

    if (element instanceof HaxeTernaryExpression) {
      HaxeExpression[] list = ((HaxeTernaryExpression)element).getExpressionList().toArray(new HaxeExpression[0]);
      return HaxeTypeUnifier.unify(getPsiElementType(list[1]), getPsiElementType(list[2]));
    }

    if (
      (element instanceof HaxeAdditiveExpression) ||
      (element instanceof HaxeMultiplicativeExpression)
    ) {
      String operatorText = getOperator(element, HaxeTokenTypeSets.OPERATORS);
      PsiElement[] children = element.getChildren();
      return getBinaryOperatorResult(getPsiElementType(children[0]), getPsiElementType(children[1]), operatorText);
    }

    //System.out.println("Unhandled " + element.getClass());
    return createPrimitiveType("Dynamic", element, null);
  }

  static private SpecificHaxeClassReference createPrimitiveType(String type, PsiElement element, Object constant) {
    return SpecificHaxeClassReference.withoutGenerics(new HaxeClassReference(type, element), constant);
  }

  static private SpecificTypeReference getBinaryOperatorResult(SpecificTypeReference left, SpecificTypeReference right, String operator) {
    PsiElement elementContext = left.getElementContext();
    SpecificTypeReference result = HaxeTypeUnifier.unify(left, right);
    if (operator.equals("/")) result = createPrimitiveType("Float", elementContext, null);
    // @TODO: Check operator overloading
    if (left.getConstant() != null && right.getConstant() != null) {
      result = result.withConstantValue(applyOperator(left.getConstant(), right.getConstant(), operator));
    }
    return result;
  }

  static public double getDoubleValue(Object value) {
    if (value instanceof Long) return (Long)value;
    if (value instanceof Integer) return (Integer)value;
    if (value instanceof Double) return (Double)value;
    if (value instanceof Float) return (Float)value;
    return Double.NaN;
  }

  static public Object applyOperator(Object left, Object right, String operator) {
    double leftv = getDoubleValue(left);
    double rightv = getDoubleValue(right);
    if (operator.equals("+")) return leftv + rightv;
    if (operator.equals("-")) return leftv - rightv;
    if (operator.equals("*")) return leftv * rightv;
    if (operator.equals("/")) return leftv / rightv;
    if (operator.equals("%")) return leftv % rightv;
    throw new RuntimeException("Unsupporteed operator");
  }

  static private String getOperator(PsiElement element, TokenSet set) {
    ASTNode operatorNode = element.getNode().findChildByType(set);
    if (operatorNode == null) return "";
    return operatorNode.getText();
  }

  static private String getOperator(PsiElement element, IElementType... operators) {
    return getOperator(element, TokenSet.create(operators));
  }
}