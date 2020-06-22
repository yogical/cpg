/*
 * Copyright (c) 2019, Fraunhofer AISEC. All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *                    $$$$$$\  $$$$$$$\   $$$$$$\
 *                   $$  __$$\ $$  __$$\ $$  __$$\
 *                   $$ /  \__|$$ |  $$ |$$ /  \__|
 *                   $$ |      $$$$$$$  |$$ |$$$$\
 *                   $$ |      $$  ____/ $$ |\_$$ |
 *                   $$ |  $$\ $$ |      $$ |  $$ |
 *                   \$$$$$   |$$ |      \$$$$$   |
 *                    \______/ \__|       \______/
 *
 */

package de.fraunhofer.aisec.cpg.frontends.cpp;

import de.fraunhofer.aisec.cpg.frontends.Handler;
import de.fraunhofer.aisec.cpg.graph.ConstructorDeclaration;
import de.fraunhofer.aisec.cpg.graph.Declaration;
import de.fraunhofer.aisec.cpg.graph.Expression;
import de.fraunhofer.aisec.cpg.graph.FieldDeclaration;
import de.fraunhofer.aisec.cpg.graph.FunctionDeclaration;
import de.fraunhofer.aisec.cpg.graph.MethodDeclaration;
import de.fraunhofer.aisec.cpg.graph.NodeBuilder;
import de.fraunhofer.aisec.cpg.graph.ParamVariableDeclaration;
import de.fraunhofer.aisec.cpg.graph.RecordDeclaration;
import de.fraunhofer.aisec.cpg.graph.Type;
import de.fraunhofer.aisec.cpg.graph.ValueDeclaration;
import de.fraunhofer.aisec.cpg.graph.VariableDeclaration;
import de.fraunhofer.aisec.cpg.passes.scopes.RecordScope;
import de.fraunhofer.aisec.cpg.passes.scopes.Scope;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.eclipse.cdt.core.dom.ast.IASTCompositeTypeSpecifier;
import org.eclipse.cdt.core.dom.ast.IASTDeclaration;
import org.eclipse.cdt.core.dom.ast.IASTExpression;
import org.eclipse.cdt.core.dom.ast.IASTInitializer;
import org.eclipse.cdt.core.dom.ast.IASTNameOwner;
import org.eclipse.cdt.core.dom.ast.IASTNode;
import org.eclipse.cdt.core.dom.ast.IBinding;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPASTCompositeTypeSpecifier;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPASTParameterDeclaration;
import org.eclipse.cdt.internal.core.dom.parser.cpp.CPPASTArrayDeclarator;
import org.eclipse.cdt.internal.core.dom.parser.cpp.CPPASTCompositeTypeSpecifier;
import org.eclipse.cdt.internal.core.dom.parser.cpp.CPPASTDeclarator;
import org.eclipse.cdt.internal.core.dom.parser.cpp.CPPASTFunctionDeclarator;
import org.eclipse.cdt.internal.core.dom.parser.cpp.CPPASTVisibilityLabel;
import org.eclipse.cdt.internal.core.dom.parser.cpp.ICPPEvaluation;

class DeclaratorHandler extends Handler<Declaration, IASTNameOwner, CXXLanguageFrontend> {

  DeclaratorHandler(CXXLanguageFrontend lang) {
    super(Declaration::new, lang);

    map.put(CPPASTDeclarator.class, ctx -> handleDeclarator((CPPASTDeclarator) ctx));
    map.put(CPPASTArrayDeclarator.class, ctx -> handleDeclarator((CPPASTDeclarator) ctx));
    map.put(
        CPPASTFunctionDeclarator.class,
        ctx -> handleFunctionDeclarator((CPPASTFunctionDeclarator) ctx));
    map.put(
        CPPASTCompositeTypeSpecifier.class,
        ctx -> handleCompositeTypeSpecifier((CPPASTCompositeTypeSpecifier) ctx));
  }

  private int getEvaluatedIntegerValue(IASTExpression exp) {
    try {
      Method method = exp.getClass().getMethod("getEvaluation");
      ICPPEvaluation evaluation = (ICPPEvaluation) method.invoke(exp);
      return evaluation.getValue().numberValue().intValue();
    } catch (Exception e) {
      return -1;
    }
  }

  private Declaration handleDeclarator(CPPASTDeclarator ctx) {
    // type will be filled out later
    String declName = ctx.getName().toString();
    ValueDeclaration declaration;
    declaration =
        NodeBuilder.newVariableDeclaration(
            ctx.getName().toString(), Type.getUnknown(), ctx.getRawSignature());

    if (declName.contains(lang.getNamespaceDelimiter())) {
      // Todo we have to check whether externally defined functions and classes get put here to
      declaration =
          NodeBuilder.newFieldDeclaration(
              ctx.getName().toString(),
              Type.getUnknown(),
              new ArrayList<>(),
              ctx.getRawSignature(),
              null,
              null);
    } else {
      declaration =
          NodeBuilder.newVariableDeclaration(
              ctx.getName().toString(), Type.getUnknown(), ctx.getRawSignature());
    }

    IASTInitializer init = ctx.getInitializer();
    if (init != null) {
      if (declaration instanceof VariableDeclaration) {
        ((VariableDeclaration) declaration)
            .setInitializer(lang.getInitializerHandler().handle(init));
      } else if (declaration instanceof FieldDeclaration) {
        ((FieldDeclaration) declaration).setInitializer(lang.getInitializerHandler().handle(init));
      }
    }

    String typeAdjustment =
        List.of(ctx.getPointerOperators()).stream()
            .map(IASTNode::getRawSignature)
            .collect(Collectors.joining());
    if (ctx instanceof CPPASTArrayDeclarator) {
      /* TODO This is responsible for adding array size to the type adjustment. To be determined
          whether this is necessary and in which form it should be adopted
      CPPASTArrayDeclarator arrayDecl = (CPPASTArrayDeclarator) ctx;
      List<String> dimensions = new ArrayList<>();
      int elementMultiplier = 1;
      for (IASTArrayModifier mod : arrayDecl.getArrayModifiers()) {
        dimensions.add(mod.getRawSignature());
        if (mod.getRawSignature().length() > 2) {
          int dimension = getEvaluatedIntegerValue(mod.getConstantExpression());
          if (dimension == -1) {
            elementMultiplier = -1;
            break;
          }
          dimensions.set(dimensions.size() - 1, "[" + dimension + "]");
          elementMultiplier *= dimension;
        }
      }
      if (declaration.getInitializer() instanceof InitializerListExpression) {
        InitializerListExpression initList =
            (InitializerListExpression) declaration.getInitializer();
        // narrow down array type to size of initializer list expression

        // Here one could compute the statically determinable dimension size from the initializer.
        // The initializer list may
        // be flattened and the computation is far from trivial if the dimension expression is
        // constant but has to be evaluated
        // In newer standards dynamic length arrays are possible.
        if (dimensions.get(0).length() <= 2)
          if (initList.getInitializers().stream()
              .noneMatch(elmt -> elmt instanceof InitializerListExpression)) {
            if (elementMultiplier > 0)
              dimensions.set(
                  0,
                  "["
                      + (-Math.floorDiv(-initList.getInitializers().size(), elementMultiplier))
                      + "]");
          } else {
            dimensions.set(0, "[" + initList.getInitializers().size() + "]");
          }

        typeAdjustment += String.join("", dimensions);

      } else if (declaration.getInitializer() instanceof Literal
          && ((Literal) declaration.getInitializer()).getValue() instanceof String) {
        // narrow down array type to length of string literal
        typeAdjustment +=
            "["
                + (((String) ((Literal) declaration.getInitializer()).getValue()).length() + 1)
                + "]";
      } else {
        typeAdjustment += String.join("", dimensions);
      }
      */
      typeAdjustment += "[]";
    }

    // forward type adjustments
    declaration.getType().setTypeAdjustment(typeAdjustment);
    lang.getScopeManager().addValueDeclaration(declaration);
    return declaration;
  }

  private ValueDeclaration handleFunctionDeclarator(CPPASTFunctionDeclarator ctx) {
    // Attention! If this declarator has no name, this is not actually a new function but
    // rather a function pointer
    String name = ctx.getName().toString();
    if (ctx.getName().toString().isEmpty()) {
      return handleFunctionPointer(ctx);
    }

    FunctionDeclaration declaration;

    // check for function definitions that are really methods and constructors
    if (name.contains("::")) {
      String[] rr = name.split("::");

      String recordName = rr[0];
      String methodName = rr[1];

      declaration =
          NodeBuilder.newMethodDeclaration(
              methodName,
              ctx.getRawSignature(),
              false,
              this.lang.getRecordForName(recordName).orElse(null));
    } else {
      declaration = NodeBuilder.newFunctionDeclaration(name, ctx.getRawSignature());
    }
    lang.getScopeManager().enterScope(declaration);

    int i = 0;
    for (ICPPASTParameterDeclaration param : ctx.getParameters()) {
      ParamVariableDeclaration arg = lang.getParameterDeclarationHandler().handle(param);

      IBinding binding = ctx.getParameters()[i].getDeclarator().getName().resolveBinding();

      if (binding != null) {
        lang.cacheDeclaration(binding, arg);
      }

      arg.setArgumentIndex(i);
      // Note that this .addValueDeclaration call already adds arg to the function's parameters.
      // This is why the following line has been commented out by @KW
      lang.getScopeManager().addValueDeclaration(arg);
      // declaration.getParameters().add(arg);
      i++;
    }

    // Check for varargs. Note the difference to Java: here, we don't have a named array
    // containing the varargs, but they are rather treated as kind of an invisible arg list that is
    // appended to the original ones. For coherent graph behaviour, we introduce a dummy that
    // wraps this list
    if (ctx.takesVarArgs()) {
      ParamVariableDeclaration varargs =
          NodeBuilder.newMethodParameterIn("va_args", Type.getUnknown(), true, "");
      varargs.setArgumentIndex(i);
      lang.getScopeManager().addValueDeclaration(varargs);
    }

    // forward type adjustments
    declaration
        .getType()
        .setTypeAdjustment(
            List.of(ctx.getPointerOperators()).stream()
                .map(IASTNode::getRawSignature)
                .collect(Collectors.joining()));

    //    lang.addFunctionDeclaration(declaration);
    lang.getScopeManager().leaveScope(declaration);
    return declaration;
  }

  private ValueDeclaration handleFunctionPointer(CPPASTFunctionDeclarator ctx) {
    Expression initializer =
        ctx.getInitializer() == null
            ? null
            : lang.getInitializerHandler().handle(ctx.getInitializer());
    // unfortunately we are not told whether this is a field or not, so we have to find it out
    // ourselves
    ValueDeclaration result;
    FunctionDeclaration currFunction = lang.getScopeManager().getCurrentFunction();
    if (currFunction != null) {
      // variable
      result =
          NodeBuilder.newVariableDeclaration(
              ctx.getNestedDeclarator().getName().toString(),
              Type.getUnknown(),
              ctx.getRawSignature());
      ((VariableDeclaration) result).setInitializer(initializer);
      result.setLocation(lang.getLocationFromRawNode(ctx));
      result.getType().setFunctionPtr(true);
      result.refreshType();
      lang.getScopeManager().addValueDeclaration((VariableDeclaration) result);
    } else {
      RecordScope recordScope =
          (RecordScope) lang.getScopeManager().getFirstScopeThat(RecordScope.class::isInstance);
      // if (recordScope != null) {
      // field
      String code = ctx.getRawSignature();
      Pattern namePattern = Pattern.compile("\\((\\*|.+\\*)(?<name>[^)]*)");
      Matcher matcher = namePattern.matcher(code);
      String name = "";
      if (matcher.find()) {
        name = matcher.group("name").strip();
      }
      result =
          NodeBuilder.newFieldDeclaration(
              name,
              Type.getUnknown(),
              Collections.emptyList(),
              code,
              lang.getLocationFromRawNode(ctx),
              initializer);
      result.setLocation(lang.getLocationFromRawNode(ctx));
      result.getType().setFunctionPtr(true);
      result.refreshType();
      /*} else {
        // not in a record and not in a field, strange. This should not happen
        log.error(
            "Function pointer declaration that is neither in a function nor in a record. "
                + "This should not happen!");
        return null;
      }*/
      lang.getScopeManager().addValueDeclaration((FieldDeclaration) result);
    }
    return result;
  }

  private RecordDeclaration handleCompositeTypeSpecifier(CPPASTCompositeTypeSpecifier ctx) {
    String kind;
    switch (ctx.getKey()) {
      default:
      case IASTCompositeTypeSpecifier.k_struct:
        kind = "struct";
        break;
      case IASTCompositeTypeSpecifier.k_union:
        kind = "union";
        break;
      case ICPPASTCompositeTypeSpecifier.k_class:
        kind = "class";
        break;
    }

    // resolveBinding().getName().
    String className =
        lang.getScopeManager().getCurrentNamePrefixWithDelimiter() + ctx.getName().toString();
    List<String> bases =
        Arrays.stream(ctx.getBaseSpecifiers())
            .map(specifer -> resolveFQN(specifer.getNameSpecifier().resolveBinding()))
            .collect(Collectors.toList());

    RecordDeclaration recordDeclaration =
        NodeBuilder.newRecordDeclaration(
            className,
            bases.stream().map(base -> new Type(base)).collect(Collectors.toList()),
            kind,
            ctx.getRawSignature());

    this.lang.addRecord(recordDeclaration);

    lang.getScopeManager().enterScope(recordDeclaration);

    if (kind.equals("class")) {
      de.fraunhofer.aisec.cpg.graph.FieldDeclaration thisDeclaration =
          NodeBuilder.newFieldDeclaration(
              "this",
              new de.fraunhofer.aisec.cpg.graph.Type(ctx.getName().toString()),
              new ArrayList<>(),
              "this",
              null,
              null);
      lang.getScopeManager().addValueDeclaration(thisDeclaration);
    }

    for (IASTDeclaration member : ctx.getMembers()) {
      if (member instanceof CPPASTVisibilityLabel) {
        // TODO: parse visibility
        continue;
      }
      Declaration declaration = lang.getDeclarationHandler().handle(member);
      Scope declarationScope = lang.getScopeManager().getScopeOfStatment(declaration);

      if (declaration instanceof FunctionDeclaration) {
        MethodDeclaration method =
            MethodDeclaration.from((FunctionDeclaration) declaration, recordDeclaration);
        declaration.disconnectFromGraph();

        // check, if its a constructor
        if (declaration.getName().equals(recordDeclaration.getName())) {
          ConstructorDeclaration constructor = ConstructorDeclaration.from(method);
          if (declarationScope != null) {
            declarationScope.setAstNode(
                constructor); // Adjust cpg Node by which scopes are identified
          }
          // recordDeclaration.getConstructors().add(constructor);
          lang.getScopeManager().addValueDeclaration(constructor);
        } else {
          // recordDeclaration.getMethods().add(method);
          lang.getScopeManager().addValueDeclaration(method);
        }

        if (declarationScope != null) {
          declarationScope.setAstNode(method); // Adjust cpg Node by which scopes are identified
        }
      } else if (declaration instanceof VariableDeclaration) {
        // recordDeclaration.getFields().add();
        lang.getScopeManager()
            .addValueDeclaration(FieldDeclaration.from((VariableDeclaration) declaration));
        lang.getScopeManager().removeDeclaration(declaration);
      } else if (declaration instanceof FieldDeclaration) {
        // recordDeclaration.getFields().add((FieldDeclaration) declaration);
        lang.getScopeManager().addValueDeclaration((FieldDeclaration) declaration);
      } else if (declaration instanceof RecordDeclaration) {
        // record is not stored as reference in the scope
        lang.getScopeManager().addDeclaration(declaration);
      }
    }

    if (recordDeclaration.getConstructors().isEmpty()) {
      de.fraunhofer.aisec.cpg.graph.ConstructorDeclaration constructorDeclaration =
          NodeBuilder.newConstructorDeclaration(
              recordDeclaration.getName(), recordDeclaration.getName(), recordDeclaration);
      recordDeclaration.getConstructors().add(constructorDeclaration);
      lang.getScopeManager().addValueDeclaration(constructorDeclaration);
    }

    lang.getScopeManager().leaveScope(recordDeclaration);
    return recordDeclaration;
  }

  public String resolveFQN(IBinding binding) {
    String fqn = binding.getName();
    IBinding owner = binding.getOwner();
    while (owner != null) {
      fqn = owner.getName() + lang.getNamespaceDelimiter() + fqn;
      owner = owner.getOwner();
    }
    return fqn;
  }
}
