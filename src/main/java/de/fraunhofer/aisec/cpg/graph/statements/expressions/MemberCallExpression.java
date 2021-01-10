/*
 * Copyright (c) 2020, Fraunhofer AISEC. All rights reserved.
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

package de.fraunhofer.aisec.cpg.graph.statements.expressions;

import de.fraunhofer.aisec.cpg.graph.HasType;
import de.fraunhofer.aisec.cpg.graph.SubGraph;
import de.fraunhofer.aisec.cpg.graph.types.Type;
import java.util.Objects;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Represents a {@link CallExpression} to a function, which is a member of an object. For example
 * <code>obj.toString()</code>.
 */
public class MemberCallExpression extends CallExpression {

  @SubGraph("AST")
  private MemberExpression member;

  public MemberExpression getMember() {
    return member;
  }

  public void setMember(MemberExpression member) {
    this.member = member;
  }

  /**
   * Returns the base of this member call expression. This is a convenience function, since the base
   * is not part of the member call expression, but rather its member expression.
   */
  @Nullable
  public Expression getBase() {
    return this.member != null ? this.member.getBase() : null;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof MemberCallExpression)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    MemberCallExpression that = (MemberCallExpression) o;
    return Objects.equals(member, that.member);
  }

  @Override
  public int hashCode() {
    return super.hashCode();
  }

  @Override
  public void typeChanged(HasType src, HasType root, Type oldType) {
    if (src == getBase()) {
      setFqn(src.getType().getRoot().getTypeName() + "." + this.getName());
    } else {
      super.typeChanged(src, root, oldType);
    }
  }

  @Override
  public void possibleSubTypesChanged(HasType src, HasType root, Set<Type> oldSubTypes) {
    if (src != getBase()) {
      super.possibleSubTypesChanged(src, root, oldSubTypes);
    }
  }
}
