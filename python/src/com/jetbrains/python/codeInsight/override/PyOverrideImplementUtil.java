/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package com.jetbrains.python.codeInsight.override;

import com.google.common.collect.Lists;
import com.intellij.codeInsight.CodeInsightUtilCore;
import com.intellij.featureStatistics.FeatureUsageTracker;
import com.intellij.featureStatistics.ProductivityFeatureNames;
import com.intellij.ide.util.MemberChooser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.Result;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.SpeedSearchComparator;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.impl.PyFunctionBuilder;
import com.jetbrains.python.psi.impl.PyPsiUtils;
import com.jetbrains.python.psi.types.PyNoneType;
import com.jetbrains.python.psi.types.TypeEvalContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * @author Alexey.Ivanov
 */
public class PyOverrideImplementUtil {
  private static final Logger LOG = Logger.getInstance("#com.jetbrains.python.codeInsight.override.PyOverrideImplementUtil");

  private PyOverrideImplementUtil() {
  }

  @Nullable
  public static PyClass getContextClass(@NotNull final Project project, @NotNull final Editor editor, @NotNull final PsiFile file) {
    PsiDocumentManager.getInstance(project).commitAllDocuments();

    int offset = editor.getCaretModel().getOffset();
    PsiElement element = file.findElementAt(offset);
    if (element == null) {
      // are we in whitespace after last class? PY-440
      final PsiElement lastChild = file.getLastChild();
      if (lastChild != null &&
          offset >= lastChild.getTextRange().getStartOffset() &&
          offset <= lastChild.getTextRange().getEndOffset()) {
        element = lastChild;
      }
    }
    final PyClass pyClass = PsiTreeUtil.getParentOfType(element, PyClass.class, false);
    if (pyClass == null && element instanceof PsiWhiteSpace && element.getPrevSibling() instanceof PyClass) {
      return (PyClass) element.getPrevSibling();
    }
    return pyClass;
  }

  public static void chooseAndOverrideMethods(final Project project, @NotNull final Editor editor, @NotNull final PyClass pyClass) {
    FeatureUsageTracker.getInstance().triggerFeatureUsed(ProductivityFeatureNames.CODEASSISTS_OVERRIDE_IMPLEMENT);
    chooseAndOverrideOrImplementMethods(project, editor, pyClass);
  }


  private static void chooseAndOverrideOrImplementMethods(final Project project,
                                                         @NotNull final Editor editor,
                                                         @NotNull final PyClass pyClass) {
    LOG.assertTrue(pyClass.isValid());
    ApplicationManager.getApplication().assertReadAccessAllowed();

    final Collection<PyFunction> superFunctions = getAllSuperFunctions(pyClass);
    chooseAndOverrideOrImplementMethods(project, editor, pyClass, superFunctions, "Select Methods to Override", false);
  }

  public static void chooseAndOverrideOrImplementMethods(@NotNull final Project project,
                                                         @NotNull final Editor editor,
                                                         @NotNull final PyClass pyClass,
                                                         @NotNull final Collection<PyFunction> superFunctions,
                                                         @NotNull final String title, final boolean implement) {
    List<PyMethodMember> elements = new ArrayList<PyMethodMember>();
    for (PyFunction function : superFunctions) {
      final String name = function.getName();
      if (name == null || PyUtil.isClassPrivateName(name)) {
        continue;
      }
      if (pyClass.findMethodByName(name, false) == null) {
        final PyMethodMember member = new PyMethodMember(function);
        elements.add(member);
      }
    }
    if (elements.size() == 0) {
      return;
    }

    final MemberChooser<PyMethodMember> chooser =
      new MemberChooser<PyMethodMember>(elements.toArray(new PyMethodMember[elements.size()]), false, true, project) {
        @Override
        protected SpeedSearchComparator getSpeedSearchComparator() {
          return new SpeedSearchComparator(false) {
            @Nullable
            @Override
            public Iterable<TextRange> matchingFragments(String pattern, String text) {
              return super.matchingFragments(PyMethodMember.trimUnderscores(pattern), text);
            }
          };
        }
      };
    chooser.setTitle(title);
    chooser.setCopyJavadocVisible(false);
    chooser.show();
    if (chooser.getExitCode() != DialogWrapper.OK_EXIT_CODE) {
      return;
    }
    List<PyMethodMember> membersToOverride = chooser.getSelectedElements();
    overrideMethods(editor, pyClass, membersToOverride, implement);
  }

  public static void overrideMethods(final Editor editor, final PyClass pyClass, final List<PyMethodMember> membersToOverride,
                                     final boolean implement) {
    if (membersToOverride == null) {
      return;
    }
    new WriteCommandAction(pyClass.getProject(), pyClass.getContainingFile()) {
      protected void run(final Result result) throws Throwable {
        write(pyClass, membersToOverride, editor, implement);
      }
    }.execute();
  }

  private static void write(@NotNull final PyClass pyClass,
                            @NotNull final List<PyMethodMember> newMembers,
                            @NotNull final Editor editor, boolean implement) {
    final PyStatementList statementList = pyClass.getStatementList();
    final int offset = editor.getCaretModel().getOffset();
    PsiElement anchor = null;
    for (PyStatement statement: statementList.getStatements()) {
      if (statement.getTextRange().getStartOffset() < offset ||
          (statement instanceof PyExpressionStatement && ((PyExpressionStatement)statement).getExpression() instanceof PyStringLiteralExpression)) {
        anchor = statement;
      }
    }

    PyFunction element = null;
    for (PyMethodMember newMember : newMembers) {
      PyFunction baseFunction = (PyFunction) newMember.getPsiElement();
      final PyFunctionBuilder builder = buildOverriddenFunction(pyClass, baseFunction, implement);
      PyFunction function = builder.addFunctionAfter(statementList, anchor, LanguageLevel.forElement(statementList));
      element = CodeInsightUtilCore.forcePsiPostprocessAndRestoreElement(function);
    }

    PyPsiUtils.removeRedundantPass(statementList);
    if (element != null) {
      final PyStatementList targetStatementList = element.getStatementList();
      final int start = targetStatementList != null
                        ? targetStatementList.getTextRange().getStartOffset()
                        : element.getTextRange().getStartOffset();
      editor.getCaretModel().moveToOffset(start);
      editor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);
      editor.getSelectionModel().setSelection(start, element.getTextRange().getEndOffset());
    }
  }

  private static PyFunctionBuilder buildOverriddenFunction(PyClass pyClass, PyFunction baseFunction, boolean implement) {
    PyFunctionBuilder pyFunctionBuilder = new PyFunctionBuilder(baseFunction.getName());
    final PyDecoratorList decorators = baseFunction.getDecoratorList();
    if (decorators != null && decorators.findDecorator(PyNames.CLASSMETHOD) != null) {
      pyFunctionBuilder.decorate(PyNames.CLASSMETHOD);
    }
    PyAnnotation anno = baseFunction.getAnnotation();
    if (anno != null) {
      pyFunctionBuilder.annotation(anno.getText());
    }
    final TypeEvalContext context = TypeEvalContext.userInitiated(baseFunction.getContainingFile());
    final List<PyParameter> baseParams = PyUtil.getParameters(baseFunction, context);
    for (PyParameter parameter : baseParams) {
      pyFunctionBuilder.parameter(parameter.getText());
    }

    PyClass baseClass = baseFunction.getContainingClass();
    assert baseClass != null;
    StringBuilder statementBody = new StringBuilder();

    boolean hadStar = false;
    List<String> parameters = new ArrayList<String>();
    for (PyParameter parameter: baseParams) {
      final PyNamedParameter pyNamedParameter = parameter.getAsNamed();
      if (pyNamedParameter != null) {
        String repr = pyNamedParameter.getRepr(false);
        parameters.add(hadStar && !pyNamedParameter.isKeywordContainer() ? pyNamedParameter.getName() + "=" + repr : repr);
        if (pyNamedParameter.isPositionalContainer()) {
          hadStar = true;
        }
      }
      else if (parameter instanceof PySingleStarParameter) {
        hadStar = true;
      }
      else {
        parameters.add(parameter.getText());
      }
    }

    if (PyNames.FAKE_OLD_BASE.equals(baseClass.getName()) || implement) {
      statementBody.append(PyNames.PASS);
    }
    else {
      if (!PyNames.INIT.equals(baseFunction.getName()) && baseFunction.getReturnType(context) != PyNoneType.INSTANCE) {
        statementBody.append("return ");
      }
      if (baseClass.isNewStyleClass()) {
        statementBody.append(PyNames.SUPER);
        statementBody.append("(");
        final LanguageLevel langLevel = ((PyFile)pyClass.getContainingFile()).getLanguageLevel();
        if (!langLevel.isPy3K()) {
          final String baseFirstName = !baseParams.isEmpty() ? baseParams.get(0).getName() : null;
          final String firstName = baseFirstName != null ? baseFirstName : PyNames.CANONICAL_SELF;
          PsiElement outerClass = PsiTreeUtil.getParentOfType(pyClass, PyClass.class, true);
          String className = pyClass.getName();
          final List<String> nameResult = Lists.newArrayList(className);
          while(outerClass instanceof PyClass) {
            nameResult.add(0, ((PyClass)outerClass).getName());
            outerClass = PsiTreeUtil.getParentOfType(outerClass, PyClass.class, true);
          }

          className = StringUtil.join(nameResult, ".");
          statementBody.append(className).append(", ").append(firstName);
        }
        statementBody.append(").").append(baseFunction.getName()).append("(");
        if (parameters.size() > 0) {
          parameters.remove(0);
        }
      }
      else {
        statementBody.append(getReferenceText(pyClass, baseClass)).append(".").append(baseFunction.getName()).append("(");
      }
      statementBody.append(StringUtil.join(parameters, ", "));
      statementBody.append(")");
    }

    pyFunctionBuilder.statement(statementBody.toString());
    return pyFunctionBuilder;
  }

  // TODO find a better place for this logic
  private static String getReferenceText(PyClass fromClass, PyClass toClass) {
    final PyExpression[] superClassExpressions = fromClass.getSuperClassExpressions();
    for (PyExpression expression : superClassExpressions) {
      if (expression instanceof PyReferenceExpression) {
        PsiElement target = ((PyReferenceExpression) expression).getReference().resolve();
        if (target == toClass) {
          return expression.getText();
        }
      }
    }
    return toClass.getName();
  }

  @NotNull
  public static Collection<PyFunction> getAllSuperFunctions(@NotNull final PyClass pyClass) {
    final Map<String, PyFunction> superFunctions = new HashMap<String, PyFunction>();
    for (PyClass aClass : pyClass.getAncestorClasses()) {
      for (PyFunction function : aClass.getMethods()) {
        if (!superFunctions.containsKey(function.getName())) {
          superFunctions.put(function.getName(), function);
        }
      }
    }
    return superFunctions.values();
  }
}
