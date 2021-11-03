/*
 * Copyright (c) 2019 - 2020, Fraunhofer AISEC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
package de.fraunhofer.aisec.cpg.frontends.llvm

import de.fraunhofer.aisec.cpg.frontends.Handler
import de.fraunhofer.aisec.cpg.frontends.TranslationException
import de.fraunhofer.aisec.cpg.graph.NodeBuilder.*
import de.fraunhofer.aisec.cpg.graph.declarations.FunctionDeclaration
import de.fraunhofer.aisec.cpg.graph.declarations.TranslationUnitDeclaration
import de.fraunhofer.aisec.cpg.graph.declarations.VariableDeclaration
import de.fraunhofer.aisec.cpg.graph.statements.CompoundStatement
import de.fraunhofer.aisec.cpg.graph.statements.DeclarationStatement
import de.fraunhofer.aisec.cpg.graph.statements.LabelStatement
import de.fraunhofer.aisec.cpg.graph.statements.Statement
import de.fraunhofer.aisec.cpg.graph.statements.expressions.*
import de.fraunhofer.aisec.cpg.graph.types.ObjectType
import de.fraunhofer.aisec.cpg.graph.types.TypeParser
import de.fraunhofer.aisec.cpg.graph.types.UnknownType
import org.bytedeco.javacpp.Pointer
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

class StatementHandler(lang: LLVMIRLanguageFrontend) :
    Handler<Statement, Pointer, LLVMIRLanguageFrontend>(::Statement, lang) {
    init {
        map.put(LLVMValueRef::class.java) { handleInstruction(it as LLVMValueRef) }
        map.put(LLVMBasicBlockRef::class.java) { handleBasicBlock(it as LLVMBasicBlockRef) }
    }

    /**
     * Handles the parsing of
     * [instructions](https://llvm.org/docs/LangRef.html#instruction-reference). Instructions are
     * usually mapped to statements.
     *
     * It is noteworthy, that LLVM IR is a single state assignment form, meaning, that all
     * instructions that perform an assignment will result in a [DeclarationStatement] and a
     * [VariableDeclaration], with the original instruction wrapped into the
     * [VariableDeclaration.initializer] property.
     *
     * Currently, this wrapping is done in the individual instruction parsing functions, but should
     * be extracted from that, e.g. by routing it through the [DeclarationHandler].
     */
    private fun handleInstruction(instr: LLVMValueRef): Statement {
        if (LLVMIsABinaryOperator(instr) != null) {
            return handleBinaryInstruction(instr)
        } else if (LLVMIsACastInst(instr) != null) {
            return declarationOrNot(lang.expressionHandler.handleCastInstruction(instr), instr)
        }

        val opcode = instr.opCode

        when (opcode) {
            LLVMRet -> {
                val ret = newReturnStatement(lang.getCodeFromRawNode(instr))

                val numOps = LLVMGetNumOperands(instr)
                if (numOps != 0) {
                    ret.returnValue = lang.getOperandValueAtIndex(instr, 0)
                }

                return ret
            }
            LLVMBr -> {
                return handleBrStatement(instr)
            }
            LLVMSwitch -> {
                return handleSwitchStatement(instr)
            }
            LLVMIndirectBr -> {
                return handleIndirectbrStatement(instr)
            }
            LLVMCall, LLVMInvoke -> {
                return handleFunctionCall(instr)
            }
            LLVMUnreachable -> {
                // Does nothing
                return newEmptyStatement(lang.getCodeFromRawNode(instr))
            }
            LLVMCallBr -> {
                // Maps to a call but also to a goto statement? Barely used => not relevant
                log.error("Cannot parse callbr instruction yet")
            }
            LLVMFNeg -> {
                val fneg = newUnaryOperator("-", false, true, lang.getCodeFromRawNode(instr))
                fneg.input = lang.getOperandValueAtIndex(instr, 0)
                return fneg
            }
            LLVMAlloca -> {
                return handleAlloca(instr)
            }
            LLVMLoad -> {
                return handleLoad(instr)
            }
            LLVMStore -> {
                return handleStore(instr)
            }
            LLVMExtractValue, LLVMGetElementPtr -> {
                return declarationOrNot(lang.expressionHandler.handleGetElementPtr(instr), instr)
            }
            LLVMICmp -> {
                return handleIntegerComparison(instr)
            }
            LLVMFCmp -> {
                return handleFloatComparison(instr)
            }
            LLVMPHI -> {
                lang.phiList.add(instr)
                return newEmptyStatement(lang.getCodeFromRawNode(instr))
            }
            LLVMSelect -> {
                return declarationOrNot(lang.expressionHandler.handleSelect(instr), instr)
            }
            LLVMUserOp1, LLVMUserOp2 -> {
                log.info(
                    "userop instruction is not a real instruction. Replacing it with empty statement"
                )
                return newEmptyStatement(lang.getCodeFromRawNode(instr))
            }
            LLVMVAArg -> {
                return handleVaArg(instr)
            }
            LLVMExtractElement -> {
                return handleExtractelement(instr)
            }
            LLVMInsertElement -> {
                return handleInsertelement(instr)
            }
            LLVMShuffleVector -> {
                return handleShufflevector(instr)
            }
            LLVMInsertValue -> {
                return handleInsertValue(instr)
            }
            LLVMFreeze -> {
                log.error("Cannot parse freeze instruction yet")
            }
            LLVMFence -> {
                log.error("Cannot parse fence instruction yet")
            }
            LLVMAtomicCmpXchg -> {
                return handleAtomiccmpxchg(instr)
            }
            LLVMAtomicRMW -> {
                return handleAtomicrmw(instr)
            }
            LLVMResume -> {
                // Marks the end of a sequence of catch statements => Can't do anything here.
                val empty = newEmptyStatement(lang.getCodeFromRawNode(instr))
                empty.name = "resume"
                return empty
            }
            LLVMLandingPad -> {
                return handleLandingpad(instr)
            }
            LLVMCleanupRet -> {
                log.error("Cannot parse cleanupret instruction yet")
            }
            LLVMCatchRet -> {
                log.error("Cannot parse catchret instruction yet")
            }
            LLVMCatchPad -> {
                log.error("Cannot parse catchpad instruction yet")
            }
            LLVMCleanupPad -> {
                log.error("Cannot parse cleanuppad instruction yet")
            }
            LLVMCatchSwitch -> {
                log.error("Cannot parse catchswitch instruction yet")
            }
        }

        log.error("Not handling instruction opcode {} yet", opcode)
        return Statement()
    }

    /**
     * Handles the [`va_arg`](https://llvm.org/docs/LangRef.html#va-arg-instruction) instruction. It
     * is simulated by a call to a function called `va_arg` simulating the respective C++-macro. The
     * function takes two arguments: the vararg-list and the type of the return value.
     */
    private fun handleVaArg(instr: LLVMValueRef): Statement {
        val callExpr = newCallExpression("va_arg", "va_arg", lang.getCodeFromRawNode(instr), false)
        val operandName = lang.getOperandValueAtIndex(instr, 0)
        callExpr.addArgument(operandName)
        val expectedType = lang.typeOf(instr)
        val typeLiteral = newLiteral(expectedType, expectedType, lang.getCodeFromRawNode(instr))
        callExpr.addArgument(typeLiteral) // TODO: Is this correct??
        return declarationOrNot(callExpr, instr)
    }

    /** Handles all kinds of instructions which are a arithmetic or logical binary instruction. */
    private fun handleBinaryInstruction(instr: LLVMValueRef): Statement {
        when (instr.opCode) {
            LLVMAdd, LLVMFAdd -> {
                return handleBinaryOperator(instr, "+", false)
            }
            LLVMSub, LLVMFSub -> {
                return handleBinaryOperator(instr, "-", false)
            }
            LLVMMul, LLVMFMul -> {
                return handleBinaryOperator(instr, "*", false)
            }
            LLVMUDiv -> {
                return handleBinaryOperator(instr, "/", true)
            }
            LLVMSDiv, LLVMFDiv -> {
                return handleBinaryOperator(instr, "/", false)
            }
            LLVMURem -> {
                return handleBinaryOperator(instr, "%", true)
            }
            LLVMSRem, LLVMFRem -> {
                return handleBinaryOperator(instr, "%", false)
            }
            LLVMShl -> {
                return handleBinaryOperator(instr, "<<", false)
            }
            LLVMLShr -> {
                return handleBinaryOperator(instr, ">>", true)
            }
            LLVMAShr -> {
                return handleBinaryOperator(instr, ">>", false)
            }
            LLVMAnd -> {
                return handleBinaryOperator(instr, "&", false)
            }
            LLVMOr -> {
                return handleBinaryOperator(instr, "|", false)
            }
            LLVMXor -> {
                return handleBinaryOperator(instr, "^", false)
            }
        }
        return Statement()
    }

    /**
     * Handles the ['alloca'](https://llvm.org/docs/LangRef.html#alloca-instruction) instruction,
     * which allocates a defined block of memory. The closest what we have in the graph is the
     * [ArrayCreationExpression], which creates a fixed sized array, i.e., a block of memory.
     */
    private fun handleAlloca(instr: LLVMValueRef): Statement {
        val array = newArrayCreationExpression(lang.getCodeFromRawNode(instr))

        array.type = lang.typeOf(instr)

        // LLVM is quite forthcoming here. in case the optional length parameter is omitted in the
        // source code, it will automatically be set to 1
        val size = lang.getOperandValueAtIndex(instr, 0)

        array.addDimension(size)

        return declarationOrNot(array, instr)
    }

    /**
     * Handles the [`store`](https://llvm.org/docs/LangRef.html#store-instruction) instruction. It
     * stores a particular value at a pointer address. This is the rough equivalent to an assignment
     * of a de-referenced pointer in C like `*a = 1`.
     */
    private fun handleStore(instr: LLVMValueRef): Statement {
        val binOp = newBinaryOperator("=", lang.getCodeFromRawNode(instr))

        val dereference = newUnaryOperator("*", false, true, "")
        dereference.input = lang.getOperandValueAtIndex(instr, 1)

        binOp.lhs = dereference
        binOp.rhs = lang.getOperandValueAtIndex(instr, 0)

        return binOp
    }

    /**
     * Handles the [`load`](https://llvm.org/docs/LangRef.html#load-instruction) instruction, which
     * is basically just a pointer de-reference.
     */
    private fun handleLoad(instr: LLVMValueRef): Statement {
        val ref = newUnaryOperator("*", false, true, "")
        ref.input = lang.getOperandValueAtIndex(instr, 0)

        return declarationOrNot(ref, instr)
    }

    /**
     * Handles the [`icmp`](https://llvm.org/docs/LangRef.html#icmp-instruction) instruction for
     * comparing integer values.
     */
    private fun handleIntegerComparison(instr: LLVMValueRef): Statement {
        var unsigned = false
        val cmpPred =
            when (LLVMGetICmpPredicate(instr)) {
                LLVMIntEQ -> "=="
                LLVMIntNE -> "!="
                LLVMIntUGT -> {
                    unsigned = true
                    ">"
                }
                LLVMIntUGE -> {
                    unsigned = true
                    ">="
                }
                LLVMIntULT -> {
                    unsigned = true
                    "<"
                }
                LLVMIntULE -> {
                    unsigned = true
                    "<="
                }
                LLVMIntSGT -> ">"
                LLVMIntSGE -> ">="
                LLVMIntSLT -> "<"
                LLVMIntSLE -> "<="
                else -> "unknown"
            }

        return handleBinaryOperator(instr, cmpPred, unsigned)
    }

    /**
     * Handles the [`fcmp`](https://llvm.org/docs/LangRef.html#fcmp-instruction) instruction for
     * comparing floating point values.
     */
    private fun handleFloatComparison(instr: LLVMValueRef): Statement {
        var unordered = false
        val cmpPred =
            when (LLVMGetFCmpPredicate(instr)) {
                LLVMRealPredicateFalse -> {
                    return newLiteral(false, TypeParser.createFrom("i1", true), "false")
                }
                LLVMRealOEQ -> "=="
                LLVMRealOGT -> ">"
                LLVMRealOGE -> ">="
                LLVMRealOLT -> "<"
                LLVMRealOLE -> "<="
                LLVMRealONE -> "!="
                LLVMRealORD -> "ord"
                LLVMRealUNO -> "uno"
                LLVMRealUEQ -> {
                    unordered = true
                    "=="
                }
                LLVMRealUGT -> {
                    unordered = true
                    ">"
                }
                LLVMRealUGE -> {
                    unordered = true
                    ">="
                }
                LLVMRealULT -> {
                    unordered = true
                    "<"
                }
                LLVMRealULE -> {
                    unordered = true
                    "<="
                }
                LLVMRealUNE -> {
                    unordered = true
                    "!="
                }
                LLVMRealPredicateTrue -> {
                    return newLiteral(true, TypeParser.createFrom("i1", true), "true")
                }
                else -> "unknown"
            }

        return handleBinaryOperator(instr, cmpPred, false, unordered)
    }

    /**
     * Handles the [`insertvalue`](https://llvm.org/docs/LangRef.html#insertvalue-instruction)
     * instruction.
     *
     * We use it similar to a constructor and assign the individual sub-elements.
     */
    private fun handleInsertValue(instr: LLVMValueRef): Statement {
        val numOps = LLVMGetNumIndices(instr)
        val indices = LLVMGetIndices(instr)

        var baseType = lang.typeOf(LLVMGetOperand(instr, 0))
        val operand = lang.getOperandValueAtIndex(instr, 0)
        val valueToSet = lang.getOperandValueAtIndex(instr, 1)

        var base = operand

        // Make a copy of the operand
        var copy = Statement()
        if (operand !is ConstructExpression) {
            copy = declarationOrNot(operand, instr)
            if (copy is DeclarationStatement) {
                base =
                    newDeclaredReferenceExpression(
                        copy.singleDeclaration.name,
                        (copy.singleDeclaration as VariableDeclaration).type,
                        lang.getCodeFromRawNode(instr)
                    )
            }
        }
        var expr: Expression

        for (idx: Int in 0 until numOps) {
            val index = indices.get(idx.toLong())

            if (base is ConstructExpression) {
                if (idx == numOps - 1) {
                    base.setArgument(index, valueToSet)
                    return declarationOrNot(operand, instr)
                }
                base = base.arguments[index]
            } else {
                // otherwise, this is a member field access, where the index denotes the n-th field
                // in the structure
                val record = (baseType as? ObjectType)?.recordDeclaration

                // this should not happen at this point, we cannot continue
                if (record == null) {
                    log.error(
                        "Could not find structure type with name {}, cannot continue",
                        baseType.typeName
                    )
                    break
                }

                log.debug(
                    "Trying to access a field within the record declaration of {}",
                    record.name
                )

                // look for the field
                val field = record.getField("field_$index")

                // our new base-type is the type of the field
                baseType = field?.type ?: UnknownType.getUnknownType()

                // construct our member expression
                expr = newMemberExpression(base, field?.type, field?.name, ".", "")
                log.info("{}", expr)

                // the current expression is the new base
                base = expr
            }
        }

        val compoundStatement = newCompoundStatement(lang.getCodeFromRawNode(instr))

        val assignment = newBinaryOperator("=", lang.getCodeFromRawNode(instr))
        assignment.lhs = base
        assignment.rhs = valueToSet
        compoundStatement.addStatement(copy)
        compoundStatement.addStatement(assignment)

        return compoundStatement
    }

    /**
     * Parses the [`cmpxchg`](https://llvm.org/docs/LangRef.html#cmpxchg-instruction) instruction.
     * It returns a single [Statement] or a [CompoundStatement] if the value is assigned to another
     * variable. Performs the following operation atomically:
     * ```
     * lhs = {*pointer, *pointer == cmp} // A struct of {T, i1}
     * if(*pointer == cmp) { *pointer = new }
     * ```
     * Returns a [CompoundStatement] with those two instructions or, if `lhs` doesn't exist, only
     * the if-then statement.
     */
    private fun handleAtomiccmpxchg(instr: LLVMValueRef): Statement {
        val instrStr = lang.getCodeFromRawNode(instr)
        val compoundStatement = newCompoundStatement(instrStr)
        compoundStatement.name = "atomiccmpxchg"
        val ptr = lang.getOperandValueAtIndex(instr, 0)
        val cmp = lang.getOperandValueAtIndex(instr, 1)
        val value = lang.getOperandValueAtIndex(instr, 2)

        val ptrDeref = newUnaryOperator("*", false, true, instrStr)
        ptrDeref.input = ptr

        val cmpExpr = newBinaryOperator("==", instrStr)
        cmpExpr.lhs = ptrDeref
        cmpExpr.rhs = cmp

        val lhs = LLVMGetValueName(instr).string
        if (lhs != "") {
            // we need to create a crazy struct here. the target type can be found here
            val targetType = lang.typeOf(instr)

            // construct it
            val construct = newConstructExpression("")
            construct.instantiates = (targetType as? ObjectType)?.recordDeclaration

            construct.addArgument(ptrDeref)
            construct.addArgument(cmpExpr)

            val decl = declarationOrNot(construct, instr)
            compoundStatement.addStatement(decl)
        }
        val assignment = newBinaryOperator("=", instrStr)
        assignment.lhs = ptrDeref
        assignment.rhs = value

        val ifStatement = newIfStatement(instrStr)
        ifStatement.condition = cmpExpr
        ifStatement.thenStatement = assignment

        compoundStatement.addStatement(ifStatement)

        return compoundStatement
    }

    /**
     * Parses the `atomicrmw` instruction. It returns either a single [Statement] or a
     * [CompoundStatement] if the value is assigned to another variable. >>>>>>> Start with cmpxchg
     * instruction
     */
    private fun handleAtomicrmw(instr: LLVMValueRef): Statement {
        val lhs = LLVMGetValueName(instr).string
        val instrStr = lang.getCodeFromRawNode(instr)
        val operation = LLVMGetAtomicRMWBinOp(instr)
        val ptr = lang.getOperandValueAtIndex(instr, 0)
        val value = lang.getOperandValueAtIndex(instr, 1)
        val ty = value.type
        val exchOp = newBinaryOperator("=", instrStr)
        exchOp.name = "atomicrmw"

        val ptrDeref = newUnaryOperator("*", false, true, instrStr)
        ptrDeref.input = ptr
        exchOp.lhs = ptrDeref

        when (operation) {
            LLVMAtomicRMWBinOpXchg -> {
                exchOp.rhs = value
            }
            LLVMAtomicRMWBinOpFAdd, LLVMAtomicRMWBinOpAdd -> {
                val binaryOperator = newBinaryOperator("+", instrStr)
                binaryOperator.lhs = ptrDeref
                binaryOperator.rhs = value
                exchOp.rhs = binaryOperator
            }
            LLVMAtomicRMWBinOpFSub, LLVMAtomicRMWBinOpSub -> {
                val binaryOperator = newBinaryOperator("-", instrStr)
                binaryOperator.lhs = ptrDeref
                binaryOperator.rhs = value
                exchOp.rhs = binaryOperator
            }
            LLVMAtomicRMWBinOpAnd -> {
                val binaryOperator = newBinaryOperator("&", instrStr)
                binaryOperator.lhs = ptrDeref
                binaryOperator.rhs = value
                exchOp.rhs = binaryOperator
            }
            LLVMAtomicRMWBinOpNand -> {
                val binaryOperator = newBinaryOperator("|", instrStr)
                binaryOperator.lhs = ptrDeref
                binaryOperator.rhs = value
                val unaryOperator = newUnaryOperator("~", false, true, instrStr)
                unaryOperator.input = binaryOperator
                exchOp.rhs = unaryOperator
            }
            LLVMAtomicRMWBinOpOr -> {
                val binaryOperator = newBinaryOperator("|", instrStr)
                binaryOperator.lhs = ptrDeref
                binaryOperator.rhs = value
                exchOp.rhs = binaryOperator
            }
            LLVMAtomicRMWBinOpXor -> {
                val binaryOperator = newBinaryOperator("^", instrStr)
                binaryOperator.lhs = ptrDeref
                binaryOperator.rhs = value
                exchOp.rhs = binaryOperator
            }
            LLVMAtomicRMWBinOpMax, LLVMAtomicRMWBinOpMin -> {
                val operatorCode =
                    if (operation == LLVMAtomicRMWBinOpMin) {
                        "<"
                    } else {
                        ">"
                    }
                val condition = newBinaryOperator(operatorCode, instrStr)
                condition.lhs = ptrDeref
                condition.rhs = value
                val conditional = newConditionalExpression(condition, ptrDeref, value, ty)
                exchOp.rhs = conditional
            }
            LLVMAtomicRMWBinOpUMax, LLVMAtomicRMWBinOpUMin -> {
                val operatorCode =
                    if (operation == LLVMAtomicRMWBinOpUMin) {
                        "<"
                    } else {
                        ">"
                    }
                val condition = newBinaryOperator(operatorCode, instrStr)
                val castExprLhs = newCastExpression(lang.getCodeFromRawNode(instr))
                castExprLhs.castType = TypeParser.createFrom("u${ty.name}", true)
                castExprLhs.expression = ptrDeref
                condition.lhs = castExprLhs

                val castExprRhs = newCastExpression(lang.getCodeFromRawNode(instr))
                castExprRhs.castType = TypeParser.createFrom("u${ty.name}", true)
                castExprRhs.expression = value
                condition.rhs = castExprRhs
                val conditional = newConditionalExpression(condition, ptrDeref, value, ty)
                exchOp.rhs = conditional
            }
            else -> {
                throw TranslationException("LLVMAtomicRMWBinOp $operation not supported")
            }
        }

        return if (lhs != "") {
            // set lhs = *ptr, then perform the replacement
            val compoundStatement = newCompoundStatement(instrStr)
            compoundStatement.statements = listOf(declarationOrNot(ptrDeref, instr), exchOp)
            compoundStatement
        } else {
            // only perform the replacement
            exchOp
        }
    }

    private fun handleIndirectbrStatement(instr: LLVMValueRef): Statement {
        val numOps = LLVMGetNumOperands(instr)
        val nodeCode = lang.getCodeFromRawNode(instr)
        if (numOps < 2)
            throw TranslationException(
                "Indirectbr statement without address and at least one target"
            )

        val address = lang.getOperandValueAtIndex(instr, 0)

        val switchStatement = newSwitchStatement(nodeCode)
        switchStatement.selector = address

        val caseStatements = newCompoundStatement(nodeCode)

        var idx = 1
        while (idx < numOps) {
            // The case statement is derived from the address of the label which we can jump to
            val caseBBAddress = LLVMValueAsBasicBlock(LLVMGetOperand(instr, idx)).address()
            val caseStatement = newCaseStatement(nodeCode)
            caseStatement.caseExpression =
                newLiteral(caseBBAddress, TypeParser.createFrom("long", true), nodeCode)
            caseStatements.addStatement(caseStatement)

            // Get the label of the goto statement.
            val caseLabelStatement = extractBasicBlockLabel(LLVMGetOperand(instr, idx))
            val gotoStatement = newGotoStatement(nodeCode)
            gotoStatement.targetLabel = caseLabelStatement
            gotoStatement.labelName = caseLabelStatement.name
            caseStatements.addStatement(gotoStatement)
            idx++
        }

        switchStatement.statement = caseStatements

        return switchStatement
    }

    /** Handles a [`br`](https://llvm.org/docs/LangRef.html#br-instruction) instruction. */
    private fun handleBrStatement(instr: LLVMValueRef): Statement {
        if (LLVMGetNumOperands(instr) == 3) {
            // if(op) then {goto label1} else {goto label2}
            val ifStatement = newIfStatement(lang.getCodeFromRawNode(instr))
            val condition = lang.getOperandValueAtIndex(instr, 0)
            ifStatement.condition = condition

            // Get the label of the "else" branch
            val elseGoto = newGotoStatement(lang.getCodeFromRawNode(instr))
            val elseLabel = extractBasicBlockLabel(LLVMGetOperand(instr, 1))
            elseGoto.targetLabel = elseLabel
            elseGoto.labelName = elseLabel.name
            ifStatement.elseStatement = elseGoto

            // Get the label of the "if" branch
            val ifGoto = newGotoStatement(lang.getCodeFromRawNode(instr))
            val thenLabelStatement = extractBasicBlockLabel(LLVMGetOperand(instr, 2))
            ifGoto.targetLabel = thenLabelStatement
            ifGoto.labelName = thenLabelStatement.name
            ifStatement.thenStatement = ifGoto

            return ifStatement
        } else if (LLVMGetNumOperands(instr) == 1) {
            // goto defaultLocation
            val gotoStatement = newGotoStatement(lang.getCodeFromRawNode(instr))
            val labelStatement = extractBasicBlockLabel(LLVMGetOperand(instr, 0))
            gotoStatement.labelName = labelStatement.name
            gotoStatement.targetLabel = labelStatement

            return gotoStatement
        } else {
            throw TranslationException("Wrong number of operands in br statement")
        }
    }

    /**
     * Handles a [`switch`](https://llvm.org/docs/LangRef.html#switch-instruction) instruction.
     * Throws a [TranslationException] if there are less than 2 operands specified (the first one is
     * used for the comparison of the "case" statements, the second one is the default location) or
     * if the number of operands is not even.
     *
     * Returns a [SwitchStatement].
     */
    private fun handleSwitchStatement(instr: LLVMValueRef): Statement {
        val numOps = LLVMGetNumOperands(instr)
        val nodeCode = lang.getCodeFromRawNode(instr)
        if (numOps < 2 || numOps % 2 != 0)
            throw TranslationException("Switch statement without operand and default branch")

        val operand = lang.getOperandValueAtIndex(instr, 0)

        val switchStatement = newSwitchStatement(nodeCode)
        switchStatement.selector = operand

        val caseStatements = newCompoundStatement(nodeCode)

        var idx = 2
        while (idx < numOps) {
            // Get the comparison value and add it to the CaseStatement
            val caseStatement = newCaseStatement(nodeCode)
            caseStatement.caseExpression = lang.getOperandValueAtIndex(instr, idx)
            caseStatements.addStatement(caseStatement)
            idx++
            // Get the "case" statements and add it to the CaseStatement
            val caseLabelStatement = extractBasicBlockLabel(LLVMGetOperand(instr, idx))
            val gotoStatement = newGotoStatement(nodeCode)
            gotoStatement.targetLabel = caseLabelStatement
            gotoStatement.labelName = caseLabelStatement.name
            caseStatements.addStatement(gotoStatement)
            idx++
        }

        // Get the label of the "default" branch
        caseStatements.addStatement(newDefaultStatement(nodeCode))
        val defaultLabel = extractBasicBlockLabel(LLVMGetOperand(instr, 1))
        val defaultGoto = newGotoStatement(nodeCode)
        defaultGoto.targetLabel = defaultLabel
        defaultGoto.labelName = defaultLabel.name
        caseStatements.addStatement(defaultGoto)

        switchStatement.statement = caseStatements

        return switchStatement
    }

    /**
     * Handles different types of function calls, including the
     * [`call`](https://llvm.org/docs/LangRef.html#call-instruction) and the
     * [`invoke`](https://llvm.org/docs/LangRef.html#invoke-instruction) instruction.
     *
     * Returns either a [DeclarationStatement] or a [CallExpression].
     */
    private fun handleFunctionCall(instr: LLVMValueRef): Statement {
        val instrStr = lang.getCodeFromRawNode(instr)
        val calledFunc = LLVMGetCalledValue(instr)
        var calledFuncName = LLVMGetValueName(calledFunc).string
        var max = LLVMGetNumOperands(instr) - 1
        var idx = 0

        if (calledFuncName.equals("")) {
            // Function is probably called by a local variable. For some reason, this is the last
            // operand
            val opName = lang.getOperandValueAtIndex(instr, max)
            calledFuncName = opName.name
        }

        var catchLabel = LabelStatement()
        var continueLabel = LabelStatement()
        if (instr.opCode == LLVMInvoke) {
            max-- // Last one is the Decl.Expr of the function
            // Get the label of the catch clause.
            catchLabel = extractBasicBlockLabel(LLVMGetOperand(instr, max))
            max--
            // Get the label of the continue basic block (e.g. if no error occors).
            continueLabel = extractBasicBlockLabel(LLVMGetOperand(instr, max))
            max--
            log.info(
                "Invoke expression: Usually continues at ${continueLabel.name}, exception continues at ${catchLabel.name}"
            )
        }

        val callExpr = newCallExpression(calledFuncName, calledFuncName, instrStr, false)

        while (idx < max) {
            val operandName = lang.getOperandValueAtIndex(instr, idx)
            callExpr.addArgument(operandName)
            idx++
        }

        if (instr.opCode == LLVMInvoke) {
            // For the invoke instruction, the call is surrounded by a try statement which also
            // contains a
            // goto statement after the call.
            val tryStatement = newTryStatement(instrStr!!)
            lang.scopeManager.enterScope(tryStatement)
            val tryBlock = newCompoundStatement(instrStr)
            tryBlock.addStatement(declarationOrNot(callExpr, instr))
            val tryContinue = newGotoStatement(instrStr)
            tryContinue.targetLabel = continueLabel
            tryBlock.addStatement(tryContinue)
            tryStatement.tryBlock = tryBlock
            lang.scopeManager.leaveScope(tryStatement)

            val catchClause = newCatchClause(instrStr)
            val gotoCatch = newGotoStatement(instrStr)
            gotoCatch.targetLabel = catchLabel
            val catchCompoundStatement = newCompoundStatement(instrStr)
            catchCompoundStatement.addStatement(gotoCatch)
            catchClause.body = catchCompoundStatement
            tryStatement.catchClauses = mutableListOf(catchClause)

            return tryStatement
        }

        return declarationOrNot(callExpr, instr)
    }

    /**
     * Handles a [`landingpad`](https://llvm.org/docs/LangRef.html#landingpad-instruction) by
     * replacing it with a catch instruction containing all possible catchable types. Later, the
     * [CompressLLVMPass] will move this instruction to the correct location
     */
    private fun handleLandingpad(instr: LLVMValueRef): Statement {
        val catchInstr = newCatchClause(lang.getCodeFromRawNode(instr)!!)
        /* Get the number of clauses on the landingpad instruction and iterate through the clauses to get all types for the catch clauses */
        val numClauses = LLVMGetNumClauses(instr)
        var catchType = ""
        for (i in 0 until numClauses) {
            val clause = LLVMGetClause(instr, i)
            if (LLVMIsAConstantArray(clause) == null) {
                if (LLVMIsNull(clause) == 1) {
                    catchType += "..." + " | "
                } else {
                    catchType += LLVMGetValueName(clause).string + " | "
                }
            } else {
                // TODO: filter not handled yet
            }
        }
        if (catchType.endsWith(" | ")) catchType = catchType.substring(0, catchType.length - 3)

        val except =
            newVariableDeclaration(
                "e_${instr.address()}",
                TypeParser.createFrom(
                    catchType,
                    false
                ), // TODO: This doesn't work for multiple types to catch
                lang.getCodeFromRawNode(instr),
                false
            )
        catchInstr.setParameter(except)
        catchInstr.name = catchType
        return catchInstr
    }

    /**
     * Handles the [`insertelement`](https://llvm.org/docs/LangRef.html#insertelement-instruction)
     * instruction which is modeled as access to an array at a given index. A new array with the
     * modified value is constructed.
     */
    private fun handleInsertelement(instr: LLVMValueRef): Statement {
        val instrStr = lang.getCodeFromRawNode(instr)
        val compoundStatement = newCompoundStatement(instrStr)

        // TODO: Probably we should make a proper copy of the array
        val newArrayDecl = declarationOrNot(lang.getOperandValueAtIndex(instr, 0), instr)
        compoundStatement.addStatement(newArrayDecl)

        val decl = newArrayDecl.declarations[0] as? VariableDeclaration
        val arrayExpr = newArraySubscriptionExpression(instrStr)
        arrayExpr.arrayExpression = newDeclaredReferenceExpression(decl?.name, decl?.type, instrStr)
        arrayExpr.subscriptExpression = lang.getOperandValueAtIndex(instr, 2)

        val binaryExpr = newBinaryOperator("=", instrStr)
        binaryExpr.lhs = arrayExpr
        binaryExpr.rhs = lang.getOperandValueAtIndex(instr, 1)
        compoundStatement.addStatement(binaryExpr)

        return compoundStatement
    }

    /**
     * Handles the [`extractelement`](https://llvm.org/docs/LangRef.html#extractelement-instruction)
     * instruction which is modeled as access to an array at a given index.
     */
    private fun handleExtractelement(instr: LLVMValueRef): Statement {
        val arrayExpr = newArraySubscriptionExpression(lang.getCodeFromRawNode(instr))
        arrayExpr.arrayExpression = lang.getOperandValueAtIndex(instr, 0)
        arrayExpr.subscriptExpression = lang.getOperandValueAtIndex(instr, 1)

        return declarationOrNot(arrayExpr, instr)
    }

    private fun handleShufflevector(instr: LLVMValueRef): Statement {
        val instrStr = lang.getCodeFromRawNode(instr)

        val list = newInitializerListExpression(instrStr)
        val elementType = lang.typeOf(instr).dereference()

        val initializers = mutableListOf<Expression>()

        // Fill the array with a loop
        val array1 = lang.getOperandValueAtIndex(instr, 0)
        val array1Length = LLVMGetVectorSize(LLVMTypeOf(LLVMGetOperand(instr, 0)))
        val array2 = lang.getOperandValueAtIndex(instr, 1)
        val array2Length: Int
        if (array2 is Literal<*> && array2.value == null) {
            array2Length = 0
        } else {
            array2Length = LLVMGetVectorSize(LLVMTypeOf(LLVMGetOperand(instr, 1)))
        }
        val indices = LLVMGetNumMaskElements(instr)

        for (idx in 0 until indices) {
            val idxInt = LLVMGetMaskValue(instr, idx)
            if (idxInt < array1Length) {
                val arrayExpr = newArraySubscriptionExpression(instrStr)
                arrayExpr.arrayExpression = array1
                arrayExpr.subscriptExpression =
                    newLiteral(idx, TypeParser.createFrom("i32", true), instrStr)
                initializers += arrayExpr
            } else if (idxInt < array1Length + array2Length) {
                val arrayExpr = newArraySubscriptionExpression(instrStr)
                arrayExpr.arrayExpression = array2
                arrayExpr.subscriptExpression =
                    newLiteral(idxInt - array1Length, TypeParser.createFrom("i32", true), instrStr)
                initializers += arrayExpr
            } else {
                initializers += newLiteral(null, elementType, instrStr)
            }
        }

        list.initializers = initializers

        return declarationOrNot(list, instr)
    }

    /**
     * Handles the [`phi`](https://llvm.org/docs/LangRef.html#phi-instruction) instruction. It
     * therefore adds dummy statements to the end of basic blocks where a certain variable is
     * declared and initialized. The original phi instruction is not added to the CPG.
     */
    fun handlePhi(instr: LLVMValueRef, tu: TranslationUnitDeclaration) {
        val labelMap = mutableMapOf<LabelStatement, Expression>()
        val numOps = LLVMGetNumOperands(instr)
        var i = 0
        var bbsFunction: LLVMValueRef? = null
        while (i < numOps) {
            val valI = lang.getOperandValueAtIndex(instr, i)
            val incomingBB = LLVMGetIncomingBlock(instr, i)
            if (bbsFunction == null) {
                bbsFunction = LLVMGetBasicBlockParent(incomingBB)
            } else if (bbsFunction.address() != LLVMGetBasicBlockParent(incomingBB).address()) {
                log.error(
                    "The basic blocks of the phi instructions are in different functions. Can't handle this!"
                )
                throw TranslationException(
                    "The basic blocks of the phi instructions are in different functions."
                )
            }

            val labelI = extractBasicBlockLabel(LLVMBasicBlockAsValue(incomingBB))
            i++
            labelMap[labelI] = valI
        }
        if (labelMap.keys.size == 1) {
            // We only have a single pair, so we insert a declaration in that one BB.
            val key = labelMap.keys.elementAt(0)
            val basicBlock = key.subStatement as? CompoundStatement
            val decl = declarationOrNot(labelMap[key]!!, instr)
            val mutableStatements = basicBlock?.statements?.toMutableList()
            mutableStatements?.add(basicBlock.statements.size - 1, decl)
            if (mutableStatements != null) {
                basicBlock.statements = mutableStatements
            }
            return
        }
        // We have multiple pairs, so we insert a declaration at the beginning of the function an
        // make an assignment in each BB.
        val functionName = LLVMGetValueName(bbsFunction).string
        val functions =
            tu.declarations.filter { d ->
                (d as? FunctionDeclaration)?.name != null &&
                    (d as? FunctionDeclaration)?.name.equals(functionName)
            }
        if (functions.size != 1) {
            log.error(
                "${functions.size} functions match the name of the one where the phi instruction is inserted. Can't handle this case."
            )
            throw TranslationException("Wrong number of functions for phi statement.")
        }
        // Create the dummy declaration at the beginning of the function body
        val firstBB = (functions[0] as FunctionDeclaration).body as CompoundStatement
        val declaration = VariableDeclaration()
        declaration.name = instr.name
        // add the declaration to the current scope
        lang.scopeManager.addDeclaration(declaration)
        // add it to our bindings cache
        lang.bindingsCache[instr.symbolName] = declaration

        val declStatement = newDeclarationStatement(lang.getCodeFromRawNode(instr))
        declStatement.singleDeclaration = declaration
        val mutableFunctionStatements = firstBB.statements.toMutableList()
        mutableFunctionStatements.add(0, declStatement)
        firstBB.statements = mutableFunctionStatements

        for (l in labelMap.keys) {
            // Now, we iterate over all the basic blocks and add an assign statement.
            val assignment = newBinaryOperator("=", lang.getCodeFromRawNode(instr))
            assignment.rhs = labelMap[l]!!
            assignment.lhs =
                newDeclaredReferenceExpression(
                    instr.name,
                    lang.typeOf(instr),
                    lang.getCodeFromRawNode(instr)
                )

            val basicBlock = l.subStatement as? CompoundStatement
            val mutableStatements = basicBlock?.statements?.toMutableList()
            mutableStatements?.add(basicBlock.statements.size - 1, assignment)
            if (mutableStatements != null) {
                basicBlock.statements = mutableStatements
            }
        }
    }

    /**
     * Most instructions in LLVM have a variable assignment as part of their instruction. Since LLVM
     * IR is SSA, we need to declare a new variable in this case, which is named according to
     * [valueRef]. In case the variable assignment is optional, and we directly return the
     * [Expression] associated with the instruction.
     */
    private fun declarationOrNot(rhs: Expression, valueRef: LLVMValueRef): Statement {
        var lhs = valueRef.name
        var symbolName = valueRef.symbolName

        // it could be an unnamed variable
        if (lhs == "") {
            lhs = lang.guessSlotNumber(valueRef)
            symbolName = "%$lhs"
        }

        // if it is still empty, we probably do not have a left shide
        return if (lhs != "") {
            val decl = VariableDeclaration()
            decl.name = lhs
            decl.initializer = rhs

            // add the declaration to the current scope
            lang.scopeManager.addDeclaration(decl)

            // add it to our bindings cache
            lang.bindingsCache[symbolName] = decl

            val declStatement = DeclarationStatement()
            declStatement.singleDeclaration = decl
            declStatement
        } else {
            rhs
        }
    }

    /**
     * Handles a basic block and returns a [CompoundStatement] comprised of the statements of this
     * block.
     */
    private fun handleBasicBlock(bb: LLVMBasicBlockRef): CompoundStatement {
        val compound = newCompoundStatement("")

        var instr = LLVMGetFirstInstruction(bb)
        while (instr != null) {
            log.debug("Parsing {}", lang.getCodeFromRawNode(instr))

            val stmt = lang.statementHandler.handle(instr)

            compound.addStatement(stmt)

            instr = LLVMGetNextInstruction(instr)
        }

        return compound
    }

    /**
     * Handles a binary operation and returns either a [BinaryOperator], [UnaryOperator],
     * [CallExpression] or a [DeclarationStatement].
     *
     * It expects the llvm-instruction in [instr] and the operator in [op]. The argument [unsigned]
     * indicates if the operands have to be treated as unsigned integer values. In this case, a cast
     * expression is used to ensure that the information is represented in the graph. The argument
     * [unordered] indicates if a floating-point comparison needs to be `or`ed with a check to
     * whether the value is unordered (i.e., NAN).
     */
    private fun handleBinaryOperator(
        instr: LLVMValueRef,
        op: String,
        unsigned: Boolean,
        unordered: Boolean = false
    ): Statement {
        val op1 = lang.getOperandValueAtIndex(instr, 0)
        val op2 = lang.getOperandValueAtIndex(instr, 1)

        val binaryOperator: Expression
        var binOpUnordered: BinaryOperator? = null

        if (op == "uno") {
            // Unordered comparison operand => Replace with a call to isunordered(x, y)
            // Resulting statement: i1 lhs = isordered(op1, op2)
            binaryOperator =
                newCallExpression(
                    "isunordered",
                    "isunordered",
                    LLVMPrintValueToString(instr).string,
                    false
                )
            binaryOperator.addArgument(op1)
            binaryOperator.addArgument(op2)
        } else if (op == "ord") {
            // Ordered comparison operand => Replace with !isunordered(x, y)
            // Resulting statement: i1 lhs = !isordered(op1, op2)
            val unorderedCall =
                newCallExpression(
                    "isunordered",
                    "isunordered",
                    LLVMPrintValueToString(instr).string,
                    false
                )
            unorderedCall.addArgument(op1)
            unorderedCall.addArgument(op2)
            binaryOperator =
                newUnaryOperator("!", false, true, LLVMPrintValueToString(instr).string)
            binaryOperator.input = unorderedCall
        } else {
            // Resulting statement: lhs = op1 <op> op2.
            binaryOperator = newBinaryOperator(op, lang.getCodeFromRawNode(instr))

            if (unsigned) {
                val op1Type = "u${op1.type.typeName}"
                val castExprLhs = newCastExpression(lang.getCodeFromRawNode(instr))
                castExprLhs.castType = TypeParser.createFrom(op1Type, true)
                castExprLhs.expression = op1
                binaryOperator.lhs = castExprLhs

                val op2Type = "u${op2.type.typeName}"
                val castExprRhs = newCastExpression(lang.getCodeFromRawNode(instr))
                castExprRhs.castType = TypeParser.createFrom(op2Type, true)
                castExprRhs.expression = op2
                binaryOperator.rhs = castExprRhs
            } else {
                binaryOperator.lhs = op1
                binaryOperator.rhs = op2
            }

            if (unordered) {
                // Special case for floating point comparisons which check if a value is "unordered
                // or <op>".
                // Statement is then lhs = isunordered(op1, op2) || (op1 <op> op2)
                binOpUnordered = newBinaryOperator("||", lang.getCodeFromRawNode(instr))
                binOpUnordered.rhs = binaryOperator
                val unorderedCall =
                    newCallExpression(
                        "isunordered",
                        "isunordered",
                        LLVMPrintValueToString(instr).string,
                        false
                    )
                unorderedCall.addArgument(op1)
                unorderedCall.addArgument(op2)
                binOpUnordered.lhs = unorderedCall
            }
        }

        val declOp = if (unordered) binOpUnordered else binaryOperator
        val decl = declarationOrNot(declOp!!, instr)

        (decl as? DeclarationStatement)?.let {
            // cache binding
            lang.bindingsCache[instr.symbolName] = decl.singleDeclaration as VariableDeclaration
        }

        return decl
    }

    /** Returns a [LabelStatement] for the basic block represented by [valueRef]. */
    private fun extractBasicBlockLabel(valueRef: LLVMValueRef): LabelStatement {
        val bb = LLVMValueAsBasicBlock(valueRef)
        var labelName = LLVMGetBasicBlockName(bb).string

        if (labelName.isNullOrEmpty()) {
            val bbStr = LLVMPrintValueToString(valueRef).string
            val firstLine = bbStr.trim().split("\n")[0]
            labelName = firstLine.substring(0, firstLine.indexOf(":"))
        }

        val labelStatement =
            lang.labelMap.computeIfAbsent(labelName) {
                val label = newLabelStatement(labelName)
                label.name = labelName
                label
            }
        return labelStatement
    }
}
