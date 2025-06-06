/*---------------------------------------------------------------------------------------------
 *  Copyright (c) 2024 Huawei Technologies Co., Ltd. All rights reserved.
 *  This file is a part of the ModelEngine Project.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

package modelengine.fit.ohscript.script.interpreter;

import static modelengine.fitframework.util.ObjectUtils.cast;

import modelengine.fit.ohscript.script.errors.OhPanic;
import modelengine.fit.ohscript.script.errors.RuntimeError;
import modelengine.fit.ohscript.script.errors.ScriptExecutionException;
import modelengine.fit.ohscript.script.lexer.Terminal;
import modelengine.fit.ohscript.script.lexer.Token;
import modelengine.fit.ohscript.script.parser.NonTerminal;
import modelengine.fit.ohscript.script.parser.nodes.ArrayAccessNode;
import modelengine.fit.ohscript.script.parser.nodes.ArrayDeclareNode;
import modelengine.fit.ohscript.script.parser.nodes.AsyncBlockNode;
import modelengine.fit.ohscript.script.parser.nodes.BlockNode;
import modelengine.fit.ohscript.script.parser.nodes.DoNode;
import modelengine.fit.ohscript.script.parser.nodes.DoubleFunctionDeclareNode;
import modelengine.fit.ohscript.script.parser.nodes.EachNode;
import modelengine.fit.ohscript.script.parser.nodes.EntityBodyNode;
import modelengine.fit.ohscript.script.parser.nodes.EntityCallNode;
import modelengine.fit.ohscript.script.parser.nodes.EntityExtensionNode;
import modelengine.fit.ohscript.script.parser.nodes.ExternalDataNode;
import modelengine.fit.ohscript.script.parser.nodes.ForNode;
import modelengine.fit.ohscript.script.parser.nodes.FunctionCallNode;
import modelengine.fit.ohscript.script.parser.nodes.FunctionDeclareNode;
import modelengine.fit.ohscript.script.parser.nodes.IfNode;
import modelengine.fit.ohscript.script.parser.nodes.ImportNode;
import modelengine.fit.ohscript.script.parser.nodes.InitialAssignmentNode;
import modelengine.fit.ohscript.script.parser.nodes.JavaNewNode;
import modelengine.fit.ohscript.script.parser.nodes.MapDeclareNode;
import modelengine.fit.ohscript.script.parser.nodes.MatchStatementNode;
import modelengine.fit.ohscript.script.parser.nodes.SafeBlockNode;
import modelengine.fit.ohscript.script.parser.nodes.SyntaxNode;
import modelengine.fit.ohscript.script.parser.nodes.TerminalNode;
import modelengine.fit.ohscript.script.parser.nodes.TupleDeclareNode;
import modelengine.fit.ohscript.script.parser.nodes.TupleUnPackerNode;
import modelengine.fit.ohscript.script.parser.nodes.WhileNode;
import modelengine.fit.ohscript.script.semanticanalyzer.symbolentries.UnknownSymbolEntry;
import modelengine.fit.ohscript.script.semanticanalyzer.type.expressions.TypeExprFactory;
import modelengine.fit.ohscript.script.semanticanalyzer.type.expressions.concretes.BoolTypeExpr;
import modelengine.fit.ohscript.script.semanticanalyzer.type.expressions.concretes.NullTypeExpr;
import modelengine.fit.ohscript.script.semanticanalyzer.type.expressions.concretes.NumberTypeExpr;
import modelengine.fit.ohscript.script.semanticanalyzer.type.expressions.concretes.StringTypeExpr;
import modelengine.fit.ohscript.util.Constants;
import modelengine.fit.ohscript.util.EmptyValue;
import modelengine.fit.ohscript.util.OhFunction;
import modelengine.fit.ohscript.util.Pair;
import modelengine.fit.ohscript.util.Tool;
import modelengine.fit.ohscript.util.TriFunction;
import modelengine.fitframework.util.ObjectUtils;
import modelengine.fitframework.util.StringUtils;

import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 解释器列表
 *
 * @since 1.0
 */
public enum Interpreter {
    /**
     * 脚本入口解释器。
     * <p>处理脚本根节点的解析，初始化顶层作用域并执行整个脚本块。</p>
     */
    SCRIPT {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            ActivationContext newCurrent = env.push(node.scope(), current);
            return Interpreter.BLOCK_STATEMENT.interpret(node, env, newCurrent);
        }
    },

    /**
     * 外部类标识符处理。
     * <p>用于加载外部 Java 类并创建对应的静态方法映射。</p>
     */
    UPPER_ID {
        private Map<String, ReturnValue> classes = new HashMap<>();

        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) {
            ReturnValue value = this.classes.get(node.lexeme());
            if (value != null) {
                return value;
            }

            Map<String, ReturnValue> object = new HashMap<>();
            Class<?> clazz = node.ast().asf().externalClasses().get(node.lexeme());
            Arrays.stream(clazz.getMethods())
                    .filter(method -> Modifier.isStatic(method.getModifiers()))
                    .forEach(method -> {
                        String name = method.getName();
                        DoubleFunctionDeclareNode doubleFunc = new DoubleFunctionDeclareNode(name,
                                method.getParameterCount(), node.ast().externalFunction(),
                                TypeExprFactory.createUnknown());
                        doubleFunc.setHostValue(new Pair<>(null, method));
                        doubleFunc.setAst(node.ast(), env);
                        object.put("." + name, new ReturnValue(current.root(), doubleFunc.typeExpr(), doubleFunc));
                    });
            value = new ReturnValue(current.root(), TypeExprFactory.createExternal(node.ast().start()), object);
            this.classes.put(node.lexeme(), value);
            return value;
        }
    },

    /**
     * 变量标识符解析。
     * <p>处理变量查找逻辑，支持以下场景：
     * <ul>
     *   <li>普通变量查找</li>
     *   <li>空值处理</li>
     *   <li>元编程类型操作符（如 {@code typeof}）</li>
     * </ul>
     */
    ID {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            TerminalNode id = ObjectUtils.cast(node);
            // meta programming: for type operation
            ReturnValue metaResult = this.metaOperate(node, id);
            if (metaResult != null) {
                return metaResult;
            }

            if (id.lexeme().equals(Constants.NULL)) {
                return ReturnValue.NULL;
            }

            ReturnValue value = current.get(id);
            if (value == null) {
                if (id.symbolEntry() instanceof UnknownSymbolEntry) {
                    value = current.get(id.lexeme());
                    if (value == null) {
                        RuntimeError.VAR_NOT_FOUND.raise();
                    }
                } else {
                    // try to find this
                    ReturnValue entityValue = current.get(Constants.THIS);
                    if (entityValue != null) {
                        value = entityValue.tryGet(Constants.DOT + id.lexeme(), false);
                    }
                    if (value == null || value.value() == EmptyValue.ERROR || value.value() == EmptyValue.NULL) {
                        value = ReturnValue.IGNORE;
                    }
                }
            }
            return value;
        }

        private ReturnValue metaOperate(SyntaxNode node, TerminalNode id) {
            if (node.parent().childCount() <= 1) {
                return null;
            }
            SyntaxNode op = node.parent().child(1);
            if (node.parent().child(0) == node || !(op instanceof TerminalNode)
                    || ((TerminalNode) op).nodeType() != Terminal.TYPE_OF) {
                return null;
            }
            switch (id.lexeme()) {
                case Constants.NUMBER:
                    return new ReturnValue(null, TypeExprFactory.createNumber(null), 0);
                case Constants.STRING:
                    return new ReturnValue(null, TypeExprFactory.createString(null), "");
                case Constants.FUNCTION:
                    return new ReturnValue(null, TypeExprFactory.createFunction(), null);
                case Constants.OBJECT:
                    return new ReturnValue(null, TypeExprFactory.createEntity(null, null), null);
                case Constants.TUPLE:
                    return new ReturnValue(null, TypeExprFactory.createTuple(null, null), null);
                case Constants.ARRAY:
                    return new ReturnValue(null, TypeExprFactory.createArray(null), null);
                case Constants.MAP:
                    return new ReturnValue(null, TypeExprFactory.createMap(null), null);
                case Constants.UNKNOWN:
                    return ReturnValue.UNKNOWN;
                case Constants.ERROR:
                    return ReturnValue.ERROR;
                case Constants.UNIT:
                    return ReturnValue.UNIT;
                default:
            }
            return null;
        }
    },

    /**
     * 字符串字面量解析。
     * <p>处理带引号的字符串值，自动去除外围引号。</p>
     */
    STRING {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) {
            String lexeme = node.lexeme();
            return new ReturnValue(current, TypeExprFactory.createString(node),
                    lexeme.substring(1, lexeme.length() - 1));
        }
    },

    /**
     * 数字字面量解析器。
     * <p>处理整数和浮点数字面值，自动进行BigDecimal到基本数值类型的转换。</p>
     */
    NUMBER {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) {
            return createNumberValue(new BigDecimal(node.lexeme()), node, env, current);
        }
    },

    /**
     * 布尔真值解析器。
     * <p>处理 {@code true} 关键字，创建布尔类型返回值。</p>
     */
    TRUE {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) {
            return createBoolValue(true, node, env, current);
        }
    },

    /**
     * 布尔假值解析器。
     * <p>处理 {@code false} 关键字，创建布尔类型返回值。</p>
     */
    FALSE {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) {
            return createBoolValue(false, node, env, current);
        }
    },

    /**
     * 默认解释器占位符。
     * <p>用于未实现具体解释逻辑的语法节点，抛出未实现异常。</p>
     */
    DEFAULT {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) {
            throw new IllegalStateException("ast interpreter of " + node.name() + " is not implemented");
        }
    },

    /**
     * 导入声明解释器。
     * <p>处理 {@code import} 语句，加载外部模块并注册到当前作用域。</p>
     */
    IMPORT_DECLARE {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            ImportNode importNode = ObjectUtils.cast(node);
            TerminalNode source = importNode.source();
            Map<String, ReturnValue> values = env.asfEnv().exportValues(source.lexeme());
            for (Pair<TerminalNode, TerminalNode> symbol : importNode.symbols()) {
                if (symbol.first().nodeType() == Terminal.STAR) {
                    for (Map.Entry<String, ReturnValue> entry : values.entrySet()) {
                        String key = entry.getKey();
                        ReturnValue value = entry.getValue();
                        TerminalNode n = new TerminalNode(Terminal.ID);
                        symbol.first().parent().addChild(n);
                        n.setToken(new Token(Terminal.ID, key, 0, 0, 0));
                        current.put(n, value);
                    }
                } else {
                    current.put(symbol.second(), values.get(symbol.first().lexeme()));
                }
            }
            return ReturnValue.IGNORE;
        }
    },

    /**
     * 导出声明解释器。
     * <p>处理 {@code export} 语句，将指定符号注册到模块导出表。</p>
     */
    EXPORT_DECLARE {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            for (TerminalNode export : node.ast().exports()) {
                env.addExportValue(export, export.interpret(env, current));
            }
            return ReturnValue.IGNORE;
        }
    },

    /**
     * 通用语句解释器。
     * <p>按顺序执行子节点，返回第一个非 {@code IGNORE} 的返回值。</p>
     */
    GENERAL {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            for (SyntaxNode child : node.children()) {
                ReturnValue value = child.interpret(env, current);
                if (value != ReturnValue.IGNORE) {
                    return value;
                }
            }
            return ReturnValue.IGNORE;
        }
    },

    /**
     * 数值表达式解释器。
     * <p>处理加减运算，支持三地址码形式的中间表达式求值。</p>
     */
    NUMERIC_EXPRESSION {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            return threeAddressOp(node, env, new NumericOp(), current);
        }
    },

    /**
     * 乘除表达式解释器。
     * <p>处理乘除运算，自动进行整型与浮点型运算的类型转换。</p>
     */
    TERM_EXPRESSION {
        private ReturnValue termOp(ReturnValue x, Terminal op, ReturnValue y) {
            boolean hasDouble = x.value() instanceof Double || y.value() instanceof Double;
            Number result = null;
            if (op == Terminal.STAR) {
                if (hasDouble) {
                    result = (ObjectUtils.<Number>cast(x.value())).doubleValue() * (ObjectUtils.<Number>cast(
                            y.value())).doubleValue();
                } else {
                    result = (ObjectUtils.<Number>cast(x.value())).intValue() * (ObjectUtils.<Number>cast(
                            y.value())).intValue();
                }
            }
            if (op == Terminal.SLASH) {
                if (hasDouble) {
                    result = (ObjectUtils.<Number>cast(x.value())).doubleValue() / (ObjectUtils.<Number>cast(
                            y.value())).doubleValue();
                } else {
                    result = (ObjectUtils.<Number>cast(x.value())).intValue() / (ObjectUtils.<Number>cast(
                            y.value())).intValue();
                }
            }
            return new ReturnValue(x.context(), TypeExprFactory.createNumber(x.typeExpr().node()), result);
        }

        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            return threeAddressOp(node, env, this::termOp, current);
        }
    },

    /**
     * 三元表达式解释器。
     * <p>处理 {@code condition ? trueValue : falseValue} 语法结构。</p>
     */
    TERNARY_EXPRESSION {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            Boolean condition = ObjectUtils.cast(node.child(0).interpret(env, current).value());
            if (condition) {
                return node.child(2).interpret(env, current);
            } else {
                return node.child(4).interpret(env, current);
            }
        }
    },

    /**
     * 逻辑非表达式解释器。
     * <p>处理 {@code !} 运算符，支持布尔值和数值类型的隐式转换。</p>
     */
    NEGATION {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            ReturnValue value = node.child(1).interpret(env, current);
            if (value.value() instanceof Boolean) {
                return new ReturnValue(current, TypeExprFactory.createBool(node), !((Boolean) value.value()));
            }
            if (value.value() != null && (value.value() instanceof Number)
                    && ((Number) value.value()).doubleValue() > 0) {
                return new ReturnValue(current, TypeExprFactory.createBool(node), false);
            } else {
                return new ReturnValue(current, TypeExprFactory.createBool(node), true);
            }
        }
    },

    /**
     * 一元表达式解释器。
     * <p>处理自增/自减运算符，支持前缀和后缀两种形式。</p>
     */
    UNARY_EXPRESSION {
        boolean isOperator(SyntaxNode node) {
            return (node.nodeType() == Terminal.PLUS_PLUS || node.nodeType() == Terminal.MINUS_MINUS);
        }

        void addOne(TerminalNode var, ASTEnv env, ActivationContext current) throws OhPanic {
            current.put(var, new NumericOp().op(var.interpret(env, current), Terminal.PLUS,
                    new ReturnValue(current, TypeExprFactory.createNumber(var), 1)));
        }

        void subtractOne(TerminalNode var, ASTEnv env, ActivationContext current) throws OhPanic {
            current.put(var, new NumericOp().op(var.interpret(env, current), Terminal.MINUS,
                    new ReturnValue(current, TypeExprFactory.createNumber(var), 1)));
        }

        ReturnValue negative(SyntaxNode var, ASTEnv env, ActivationContext current) throws OhPanic {
            return new NumericOp().op(var.interpret(env, current), Terminal.STAR,
                    new ReturnValue(current, TypeExprFactory.createNumber(var), -1));
        }

        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            ReturnValue value;
            if (this.isOperator(node.child(1))) {
                // right unary
                TerminalNode var = ObjectUtils.cast(node.child(0));
                value = var.interpret(env, current).clone();
                switch ((ObjectUtils.<TerminalNode>cast(node.child(1))).nodeType()) {
                    case PLUS_PLUS:
                        this.addOne(var, env, current);
                        break;
                    case MINUS_MINUS:
                        this.subtractOne(var, env, current);
                        break;
                    default:
                        break;
                }
            } else {
                Terminal operator = node.child(0).nodeType();
                if (operator == Terminal.MINUS) {
                    value = this.negative(node.child(1), env, current);
                } else {
                    TerminalNode var = ObjectUtils.cast(node.child(1));
                    switch (operator) {
                        case PLUS_PLUS:
                            this.addOne(var, env, current);
                            break;
                        case MINUS_MINUS:
                            this.subtractOne(var, env, current);
                            break;
                        default:
                            break;
                    }
                    value = var.interpret(env, current);
                }
            }
            return value;
        }
    },

    /**
     * 复合条件表达式解释器。
     * <p>处理 {@code &&} 和 {@code ||} 运算符，采用短路求值策略。</p>
     */
    CONDITION_EXPRESSION {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            ReturnValue leftValue = node.child(0).interpret(env, current);
            boolean result;
            if (leftValue.value() instanceof Boolean) {
                result = ObjectUtils.cast(leftValue.value());
            } else {
                result = (ObjectUtils.<Number>cast(leftValue.value())).doubleValue() > 0;
            }
            for (int index = 2; index < node.childCount(); index = index + 2) {
                TerminalNode op = ObjectUtils.cast(node.child(index - 1));
                if (result && op.nodeType() == Terminal.OR_OR) {
                    return this.createValue(true, node, env, current);
                }
                if (!result && op.nodeType() == Terminal.AND_AND) {
                    return this.createValue(false, node, env, current);
                }
                result = opRight(result, op, node.child(index).interpret(env, current));
            }
            return this.createValue(result, node, env, current);
        }

        private boolean opRight(boolean left, TerminalNode op, ReturnValue rightValue) {
            boolean right;
            if (rightValue.value() instanceof Boolean) {
                right = ObjectUtils.cast(rightValue.value());
            } else {
                right = (ObjectUtils.<Number>cast(rightValue.value())).doubleValue() > 0;
            }
            if (op.nodeType() == Terminal.OR_OR) {
                return left || right;
            }
            if (op.nodeType() == Terminal.AND_AND) {
                return left && right;
            }
            return false;
        }

        private ReturnValue createValue(Boolean bool, SyntaxNode node, ASTEnv env, ActivationContext current) {
            return new ReturnValue(current, TypeExprFactory.createNumber(node), bool);
        }
    },

    /**
     * 关系运算解释器。
     * <p>处理比较运算符(&gt;, &lt;, &gt;=, &lt;=），支持类型检查和数值比较。</p>
     */
    RELATIONAL_CONDITION {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            ReturnValue x = node.child(0).interpret(env, current);
            if (node.childCount() == 1) {
                return x;
            }
            ReturnValue y = node.child(2).interpret(env, current);
            TerminalNode op = ObjectUtils.cast(node.child(1));
            if (op.nodeType() == Terminal.TYPE_OF) {
                return this.createValue(x.typeExpr().type() == y.typeExpr().type(), node, current);
            }
            if (op.nodeType() == Terminal.EXACT_TYPE_OF) {
                return this.createValue(x.typeExpr().is(y.typeExpr()), node, current);
            }
            if (op.nodeType() == Terminal.EQUAL_EQUAL || op.nodeType() == Terminal.BANG_EQUAL) {
                ReturnValue equalsResult = this.compareEquals(node, current, x, y, op);
                if (equalsResult != null) {
                    return equalsResult;
                }
            }
            if (op.nodeType() == Terminal.AND_AND) {
                return this.createValue(this.andAndResult(x, y), node, current);
            }
            if (op.nodeType() == Terminal.OR_OR) {
                return this.createValue(this.orOrResult(x, y), node, current);
            }
            // 只有大小比较了
            if (x.value() == null || y.value() == null) {
                throw new OhPanic("can not compare with null", Constants.UNKNOWN_ERROR);
            }
            if (!(x.typeExpr() instanceof NumberTypeExpr || y.typeExpr() instanceof NumberTypeExpr)) {
                return this.createValue(false, node, current);
            }
            ReturnValue numberResult = numberCompare(node, current, x, y, op);
            if (numberResult != null) {
                return numberResult;
            }
            return this.createValue(false, node, current);
        }

        private ReturnValue compareEquals(SyntaxNode node, ActivationContext current, ReturnValue x, ReturnValue y,
                TerminalNode op) {
            // 处理存在null的场景
            if (x.value() == null || y.value() == null) {
                return Optional.ofNullable(this.compareEqualsEqualsOnNull(node, current, x, y, op))
                        .orElseGet(() -> Optional.ofNullable(this.compareBangEqualsOnNull(node, current, x, y, op))
                                .orElseGet(() -> this.createValue(false, node, current)));
            }
            if (op.nodeType() == Terminal.EQUAL_EQUAL) {
                return this.createValue(this.compareReturnNumberValue(x, y), node, current);
            }
            if (op.nodeType() == Terminal.BANG_EQUAL) {
                return this.createValue(!this.compareReturnNumberValue(x, y), node, current);
            }
            // 不是null且不是判定 != 或 == 的操作
            return null;
        }

        private Boolean compareReturnNumberValue(ReturnValue x, ReturnValue y) {
            Object xValue = x.value();
            Object yValue = y.value();
            Boolean result = this.compareNumber(xValue, yValue);
            if (result == null) {
                result = xValue.equals(yValue);
            }
            return result;
        }

        private Boolean compareNumber(Object xValue, Object yValue) {
            Boolean result = this.compareNumerOnBigDecimal(xValue, yValue);
            if (result != null) {
                return result;
            }
            result = this.compareNumerOnBigDecimal(yValue, xValue);
            if (result != null) {
                return result;
            }
            if (!(xValue instanceof Number)) {
                return null;
            }
            if (!(yValue instanceof Number)) {
                return false;
            }
            return Double.compare(((Number) xValue).doubleValue(), ((Number) yValue).doubleValue()) == 0;
        }

        private Boolean compareNumerOnBigDecimal(Object first, Object second) {
            if (!(first instanceof BigDecimal)) {
                return null;
            }
            if (second instanceof BigDecimal) {
                return first.equals(second);
            }
            if (!(second instanceof Integer)) {
                return null;
            }
            try {
                return ((BigDecimal) first).intValueExact() == ObjectUtils.<Integer>cast(second);
            } catch (ArithmeticException e) {
                return false;
            }
        }

        private ReturnValue compareBangEqualsOnNull(SyntaxNode node, ActivationContext current, ReturnValue x,
                ReturnValue y, TerminalNode op) {
            if (op.nodeType() != Terminal.BANG_EQUAL) {
                return null;
            }
            if (x.value() == null && y.typeExpr() instanceof NullTypeExpr) {
                return this.createValue(false, node, current);
            }
            if (y.value() == null && x.typeExpr() instanceof NullTypeExpr) {
                return this.createValue(false, node, current);
            }
            return this.createValue(true, node, current);
        }

        private ReturnValue compareEqualsEqualsOnNull(SyntaxNode node, ActivationContext current, ReturnValue x,
                ReturnValue y, TerminalNode op) {
            if (op.nodeType() != Terminal.EQUAL_EQUAL) {
                return null;
            }
            if (x.value() == null && y.typeExpr() instanceof NullTypeExpr) {
                return this.createValue(true, node, current);
            }
            if (y.value() == null && x.typeExpr() instanceof NullTypeExpr) {
                return this.createValue(true, node, current);
            }
            return this.createValue(false, node, current);
        }

        private ReturnValue numberCompare(SyntaxNode node, ActivationContext current, ReturnValue x, ReturnValue y,
                TerminalNode op) {
            Number xNum = cast(x.value());
            double x1 = xNum.doubleValue();
            Number yNum = cast(y.value());
            double y1 = yNum.doubleValue();
            if (op.nodeType() == Terminal.GREATER) {
                return this.createValue(x1 > y1, node, current);
            }
            if (op.nodeType() == Terminal.GREATER_EQUAL) {
                return this.createValue(x1 >= y1, node, current);
            }
            if (op.nodeType() == Terminal.LESS) {
                return this.createValue(x1 < y1, node, current);
            }
            if (op.nodeType() == Terminal.LESS_EQUAL) {
                return this.createValue(x1 <= y1, node, current);
            }
            return null;
        }

        private boolean orOrResult(ReturnValue x, ReturnValue y) {
            boolean left = cast(x.value());
            boolean right = cast(y.value());
            return left || right;
        }

        private boolean andAndResult(ReturnValue x, ReturnValue y) {
            boolean left = cast(x.value());
            boolean right = cast(y.value());
            return left && right;
        }

        private ReturnValue createValue(Boolean bool, SyntaxNode node, ActivationContext current) {
            return new ReturnValue(current, TypeExprFactory.createBool(node), bool);
        }
    },

    /**
     * 函数调用解释器。
     * <p>执行函数调用操作，包含参数准备、上下文切换和返回值处理。</p>
     */
    FUNC_CALL {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            FunctionCallNode funcCall = ObjectUtils.cast(node);
            ReturnValue function = funcCall.functionName().interpret(env, current);
            Object funcValue = function.value();
            if (!(funcValue instanceof FunctionDeclareNode)) {
                TerminalNode errNode = ObjectUtils.cast(funcCall.functionName());
            }
            List<ReturnValue> argValues = new ArrayList<>();
            for (SyntaxNode arg : funcCall.args()) {
                argValues.add(arg.interpret(env, current));
            }
            ;
            ActivationContext funcContext = function.context();
            if (funcContext != current && funcContext != null) {
                if (current.getThis() != null) {
                    funcContext.putThis(current.getThis());
                    current.removeThis();
                }
            }
            return Tool.interpretFunction(ObjectUtils.cast(function.value()), argValues, funcContext);
        }
    },

    /**
     * 函数声明解释器。
     * <p>注册函数定义到当前作用域，支持闭包捕获上下文变量。</p>
     */
    FUNC_DECLARE {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            ReturnValue value = new ReturnValue(current, node.typeExpr().exactBe(), node);
            TerminalNode name = (ObjectUtils.<FunctionDeclareNode>cast(node)).functionName();
            current.put(name, value);
            return value;
        }
    },

    /**
     * 模式匹配语句解释器。
     * <p>处理 {@code match} 语句的入口，执行模式分支选择。</p>
     */
    MATCH_STATEMENT {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            return BLOCK_STATEMENT.interpret(node, env, current);
        }
    },

    /**
     * 模式变量绑定解释器。
     * <p>处理模式匹配中的变量绑定和解构操作。</p>
     */
    MATCH_VAR {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            ReturnValue value = (ObjectUtils.<MatchStatementNode>cast(
                    node.parent().parent().parent().parent())).matcher().interpret(env, current);
            SyntaxNode var = node.child(0);
            return this.interpret(var, env, value, current);
        }

        private ReturnValue interpret(SyntaxNode var, ASTEnv env, ReturnValue value, ActivationContext current)
                throws OhPanic {
            BoolTypeExpr bool = TypeExprFactory.createBool(var);
            if (var.nodeType() == NonTerminal.TUPLE_UNPACKER) {
                TupleUnPackerNode tuple = ObjectUtils.cast(var);
                boolean matched = true;
                Map cells = ObjectUtils.cast(value.value());
                List<SyntaxNode> members = tuple.items();
                int offset = 0; // match .. in tuple deconstruction
                for (int i = 0; i < members.size(); i++) {
                    SyntaxNode member = members.get(i);
                    String id = "." + (i + offset);
                    if (member.nodeType() == Terminal.DOT_DOT) { // match the tail elements
                        offset = cells.size() - members.size();
                        continue;
                    }
                    ReturnValue v = ObjectUtils.cast(cells.get(id));
                    matched = matched && ObjectUtils.<Boolean>cast(
                            this.interpret(members.get(i), env, v, current).value());
                    if (!matched) {
                        return new ReturnValue(current, bool, false);
                    }
                }
                return new ReturnValue(current, bool, true);
            }
            if (var.nodeType() == Terminal.ID) {
                TerminalNode id = ObjectUtils.cast(var);
                if (!StringUtils.equals(id.lexeme(), Constants.UNDER_LINE)) {
                    current.put(id, value);
                }
                return new ReturnValue(current, bool, true);
            }
            if (var.nodeType() == Terminal.NUMBER) {
                boolean equals = (value.typeExpr() instanceof NumberTypeExpr && value.value()
                        .toString()
                        .equals(var.lexeme()));
                return new ReturnValue(current, bool, equals);
            }
            if (var.nodeType() == Terminal.STRING) {
                String lexeme = var.lexeme();
                boolean equals = value.value().toString().equals(lexeme.substring(1, lexeme.length() - 1));
                return new ReturnValue(current, bool, equals);
            }

            return new ReturnValue(current, bool, false);
        }
    },

    /**
     * 条件分支解释器。
     * <p>处理 {@code if} 和 {@code else if} 分支的条件判断和执行。</p>
     */
    IF_STATEMENT {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            List<SyntaxNode> conditions = (ObjectUtils.<IfNode>cast(node)).branches();
            for (SyntaxNode condition : conditions) {
                ReturnValue value = condition.interpret(env, current);
                if (value != ReturnValue.IGNORE) {
                    if (value == ReturnValue.DECLARED) {
                        return ReturnValue.IGNORE;
                    } else {
                        return value;
                    }
                }
            }
            return ReturnValue.IGNORE;
        }
    },

    /**
     * 条件分支解释器。
     * <p>处理 {@code if} 语句中的单个条件分支逻辑。</p>
     */
    IF_BRANCH {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            return this.interpretBlock(node, env, current, (n, e, c) -> {
                ReturnValue result = ReturnValue.IGNORE;
                SyntaxNode condition = n.child(0);
                SyntaxNode branch = n.child(1);
                if (condition.nodeType() == Terminal.UNIT || ObjectUtils.<Boolean>cast(
                        condition.interpret(e, c).value())) {
                    result = branch.interpret(e, c);
                    if (result == ReturnValue.IGNORE || (!(branch.returnAble()) && result != ReturnValue.BREAK
                            && result != ReturnValue.CONTINUE)) {
                        result = ReturnValue.DECLARED;
                    }
                }
                return result;
            });
        }
    },

    /**
     * 迭代循环解释器。
     * <p>处理 {@code each} 语句，支持数组/集合的迭代和索引绑定。</p>
     */
    EACH_STATEMENT {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            EachNode eachNode = ObjectUtils.cast(node);
            BlockNode body = eachNode.body();
            List<ReturnValue> array = ObjectUtils.cast(eachNode.array().interpret(env, current).value());

            ActivationContext newCurrent = env.push(eachNode.scope(), env, current);
            for (int i = 0; i < array.size(); i++) {
                if (!StringUtils.equals(eachNode.item().lexeme(), Constants.UNDER_LINE)) {
                    newCurrent.put(eachNode.item(), array.get(i));
                }
                if (!StringUtils.equals(eachNode.index().lexeme(), Constants.UNDER_LINE)) {
                    newCurrent.put(eachNode.index(),
                            new ReturnValue(newCurrent, TypeExprFactory.createNumber(node), i));
                }
                ReturnValue value = body.interpret(env, newCurrent);
                if (value == ReturnValue.BREAK) {
                    break;
                }
                if (value == ReturnValue.CONTINUE) {
                    continue;
                }
                if (value != ReturnValue.IGNORE) {
                    return value;
                }
            }
            return ReturnValue.IGNORE;
        }
    },

    /**
     * for循环解释器。
     * <p>处理C风格 {@code for} 循环，支持初始化、条件检测和迭代表达式。</p>
     */
    FOR_STATEMENT {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            ForNode forNode = ObjectUtils.cast(node);
            BlockNode body = forNode.body();
            ActivationContext newCurrent = env.push(forNode.scope(), env, current);
            forNode.initial().interpret(env, newCurrent);
            while (ObjectUtils.cast(forNode.condition().interpret(env, newCurrent).value())) {
                ReturnValue value = body.interpret(env, newCurrent);
                if (value == ReturnValue.BREAK) {
                    break;
                }
                if (value == ReturnValue.CONTINUE) {
                    continue;
                }
                if (value != ReturnValue.IGNORE) {
                    return value;
                }
                forNode.expression().interpret(env, newCurrent);
            }
            return ReturnValue.IGNORE;
        }
    },

    /**
     * while循环解释器。
     * <p>处理前置条件循环，先检查条件再执行循环体。</p>
     */
    WHILE_STATEMENT {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            WhileNode whileNode = ObjectUtils.cast(node);
            SyntaxNode condition = whileNode.condition();
            SyntaxNode body = whileNode.body();
            while (ObjectUtils.cast(condition.interpret(env, current).value())) {
                ReturnValue value = body.interpret(env, current);
                if (value == ReturnValue.BREAK) {
                    break;
                }
                if (value == ReturnValue.CONTINUE) {
                    continue;
                }
                if (value != ReturnValue.IGNORE) {
                    return value;
                }
            }
            return ReturnValue.IGNORE;
        }
    },

    /**
     * do-while循环解释器。
     * <p>处理后置条件循环，先执行循环体再检查条件。</p>
     */
    DO_STATEMENT {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            DoNode whileNode = ObjectUtils.cast(node);
            SyntaxNode condition = whileNode.condition();
            SyntaxNode body = whileNode.body();
            do {
                ReturnValue value = body.interpret(env, current);
                if (value == ReturnValue.BREAK) {
                    break;
                }
                if (value == ReturnValue.CONTINUE) {
                    continue;
                }
                if (value != ReturnValue.IGNORE) {
                    return value;
                }
            } while (ObjectUtils.cast(condition.interpret(env, current).value()));
            return ReturnValue.IGNORE;
        }
    },

    /**
     * 循环控制解释器。
     * <p>处理 {@code break} 和 {@code continue} 语句，改变循环控制流。</p>
     */
    LOOP_CONTROL {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) {
            SyntaxNode control = node.child(0);
            if (control.nodeType() == Terminal.BREAK) {
                return ReturnValue.BREAK;
            } else {
                return ReturnValue.CONTINUE;
            }
        }
    },

    /**
     * 语句集合解释器。
     * <p>按顺序执行多个语句，返回最后一个非忽略值的结果。</p>
     */
    STATEMENTS {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            return BLOCK_STATEMENT.interpret(node, env, current);
        }
    },

    /**
     * 代码块解释器。
     * <p>创建独立作用域，按顺序执行块内语句并返回结果。</p>
     */
    BLOCK_STATEMENT {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            return this.interpretBlock(node, env, current, (n, e, c) -> {
                ReturnValue value = ReturnValue.IGNORE;
                for (SyntaxNode child : n.children()) {
                    ReturnValue tmp = child.interpret(e, c);
                    if (child.returnAble() && tmp != ReturnValue.IGNORE) {
                        value = tmp;
                        break;
                    }
                }
                return value;
            });
        }
    },

    /**
     * 锁同步代码块解释器。
     * <p>通过 {@code ReentrantLock} 实现线程同步，保证：
     * <ul>
     *   <li>同一代码块的原子性执行</li>
     *   <li>避免竞态条件</li>
     * </ul>
     */
    LOCK_BLOCK {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) {
            staticLock.lock();
            ReentrantLock lock = locks.get(node);
            if (lock == null) {
                lock = new ReentrantLock();
                locks.put(node, lock);
            }
            staticLock.unlock();
            try {
                lock.lock();
                locks.put(node, lock);
                return GENERAL.interpret(node, env, current);
            } catch (Exception e) {
                return null;
            } finally {
                lock.unlock();
            }
        }
    },

    /**
     * 异步代码块解释器。
     * <p>使用 {@code CompletableFuture} 实现异步执行，提供以下功能：
     * <ul>
     *   <li>.await() 方法同步获取结果</li>
     *   <li>.then() 方法注册回调函数</li>
     * </ul>
     */
    ASYNC_BLOCK {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) {
            AsyncBlockNode async = ObjectUtils.cast(node);
            BlockNode block = async.block();
            CompletableFuture<ReturnValue> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return block.interpret(env, current);
                } catch (OhPanic e) {
                    throw new ScriptExecutionException(e);
                }
            });
            HashMap<String, ReturnValue> value = new HashMap<>();
            addAwait(node, env, current, future, value);
            addThen(node, env, current, future, value);
            return new ReturnValue(current, node.typeExpr(), value);
        }

        private void addAwait(SyntaxNode node, ASTEnv env, ActivationContext current,
                CompletableFuture<ReturnValue> future, HashMap<String, ReturnValue> value) {
            DoubleFunctionDeclareNode await = new DoubleFunctionDeclareNode(Constants.ASYNC + "_await", 0,
                    (host, args, env1, current1) -> {
                        CompletableFuture<ReturnValue> f = (CompletableFuture<ReturnValue>) host;
                        try {
                            return f.get();
                        } catch (Exception e) {
                            throw new OhPanic(e.getMessage(), Constants.UNKNOWN_ERROR);
                        }
                    }, TypeExprFactory.createFunction());
            await.setHostValue(future);
            await.setAst(node.ast(), env);
            value.put(".await", new ReturnValue(current, await.typeExpr(), await));
        }

        private void addThen(SyntaxNode node, ASTEnv env, ActivationContext current,
                CompletableFuture<ReturnValue> future, HashMap<String, ReturnValue> value) {
            OhFunction ohFunction = (host, args, env1, current1) -> {
                try {
                    FunctionDeclareNode lambda = cast(ObjectUtils.<ReturnValue>cast(args.get(0)).value());
                    ObjectUtils.<CompletableFuture<ReturnValue>>cast(host)
                            .thenAccept(this.interpretAction(current, lambda));
                    return null;
                } catch (Exception e) {
                    throw new ScriptExecutionException(e);
                }
            };
            DoubleFunctionDeclareNode then = new DoubleFunctionDeclareNode(Constants.ASYNC + "_then", 1, ohFunction,
                    TypeExprFactory.createFunction());
            then.setHostValue(future);
            then.setAst(node.ast(), env);
            value.put(".then", new ReturnValue(current, then.typeExpr(), then));
        }

        private Consumer<ReturnValue> interpretAction(ActivationContext current, FunctionDeclareNode lambda) {
            return result -> {
                List<ReturnValue> callbackArgs = new ArrayList<>();
                callbackArgs.add(new ReturnValue(current, TypeExprFactory.createUnknown(), result));
                try {
                    Tool.interpretFunction(lambda, callbackArgs, current);
                } catch (OhPanic e) {
                    throw new ScriptExecutionException(e);
                }
            };
        }
    },

    /**
     * 安全代码块解释器。
     * <p>提供异常安全执行环境，自动捕获 panic 并记录错误码，包含：
     * <ul>
     *   <li>.get() 方法获取执行结果</li>
     *   <li>.panic_code 属性记录错误代码</li>
     * </ul>
     */
    SAFE_BLOCK {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) {
            SafeBlockNode safe = ObjectUtils.cast(node);
            BlockNode block = safe.block();
            HashMap<String, ReturnValue> result = new HashMap<>();
            ReturnValue value = null;
            Integer panicCode = 0;
            try {
                value = block.interpret(env, current);
            } catch (OhPanic e) {
                panicCode = e.code();
            }
            addValue(node, env, current, result, value);
            addPanicCode(node, env, current, result, panicCode);
            return new ReturnValue(current, node.typeExpr(), result);
        }

        private void addPanicCode(SyntaxNode node, ASTEnv env, ActivationContext current,
                HashMap<String, ReturnValue> result, Integer panicCode) {
            DoubleFunctionDeclareNode panic = new DoubleFunctionDeclareNode(Constants.SAFE + "_panic", 0,
                    (host, args, env1, current1) -> panicCode, TypeExprFactory.createFunction());
            panic.setHostValue(panic);
            panic.setAst(node.ast(), env);
            result.put(".panic_code", new ReturnValue(current, panic.typeExpr(), panic));
        }

        private void addValue(SyntaxNode node, ASTEnv env, ActivationContext current,
                HashMap<String, ReturnValue> result, ReturnValue value) {
            DoubleFunctionDeclareNode get = new DoubleFunctionDeclareNode(Constants.SAFE + "_get", 0,
                    (host, args, env1, current1) -> host, TypeExprFactory.createFunction());
            get.setHostValue(value);
            get.setAst(node.ast(), env);
            result.put(".get", new ReturnValue(current, get.typeExpr(), get));
        }
    },

    /**
     * 系统扩展解释器。
     * <p>处理内置系统功能的扩展，注册全局可用的工具方法。</p>
     */
    SYSTEM_EXTENSION {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            ActivationContext newCurrent = env.push(node.scope(), env, current);
            Interpreter.GENERAL.interpret(node, env, newCurrent);
            ReturnValue value = new ReturnValue(newCurrent, node.typeExpr().exactBe(), newCurrent.all());
            env.asfEnv().context().put(node.declaredName(), value);
            return value;
        }
    },

    /**
     * 实体扩展解释器。
     * <p>处理类继承和接口实现，支持基类成员的复用和扩展。</p>
     */
    ENTITY_EXTENSION {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            EntityExtensionNode ext = ObjectUtils.cast(node);
            // 先得到host value，这是主value
            ReturnValue value = ext.host().interpret(env, current);
            // 然后得到扩展value
            HashMap extsValue = ObjectUtils.cast(ENTITY_DECLARE.interpret(node, env, current).value());
            // 再给.base赋值主host value
            ReturnValue baseValue = new ReturnValue(value.context(), node.typeExpr(), value.value());
            baseValue.myMethods().putAll(value.methods());
            ObjectUtils.<ReturnValue>cast(extsValue.get(Constants.DOT + Constants.BASE)).update(baseValue);
            value = value.clone();
            // 然后给host value加扩展
            value.myMethods().putAll(extsValue);

            return value;
        }
    },

    /**
     * 实体声明解释器。
     * <p>处理类/实体的定义，创建包含成员方法和属性的上下文环境。</p>
     */
    ENTITY_DECLARE {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            ActivationContext newCurrent = env.push(node.scope(), env, current);
            Interpreter.GENERAL.interpret(node, env, newCurrent);
            ReturnValue value = new ReturnValue(newCurrent, node.typeExpr().exactBe(), newCurrent.all());
            newCurrent.put(node.declaredName(), value);
            return value;
        }
    },

    /**
     * 元组声明解释器。
     * <p>处理多值组合结构，创建不可变的数据集合。</p>
     */
    TUPLE_DECLARE {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            return ENTITY_DECLARE.interpret(node, env, current);
        }
    },

    /**
     * 元组解包解释器。
     * <p>支持以下解包模式：
     * <ul>
     *   <li>普通元组解包：{@code (a, b) = tuple}</li>
     *   <li>扩展模式匹配：{@code (head, ..tail) = list}</li>
     *   <li>忽略占位符：{@code _}</li>
     * </ul>
     */
    TUPLE_UNPACKER {
        @Override
        public void assignValue(SyntaxNode node, ReturnValue value, ASTEnv env, ActivationContext current)
                throws OhPanic {
            TupleUnPackerNode unpacker = ObjectUtils.cast(node);
            List<SyntaxNode> members = unpacker.items();
            Map<String, ReturnValue> all = (Map<String, ReturnValue>) value.value();
            Map<String, ReturnValue> values = new HashMap<>();
            for (String key : all.keySet()) {
                if (key.indexOf(".") != 0) {
                    continue;
                }
                values.put(key, all.get(key));
            }
            if (value.typeExpr().node() instanceof TupleDeclareNode) { // tuple
                int offset = 0; // match .. in tuple deconstruction
                for (int i = 0; i < members.size(); i++) {
                    SyntaxNode member = members.get(i);
                    String id = "." + (i + offset);
                    if (member.nodeType() == Terminal.DOT_DOT) { // match the tail elements
                        offset = values.size() - members.size();
                        continue;
                    }
                    if (member instanceof TerminalNode) {
                        super.assignValue(member, values.get(id), env, current);
                    } else {
                        TUPLE_UNPACKER.assignValue(member, values.get(id), env, current);
                    }
                }
            } else { // entity
                for (SyntaxNode member : members) {
                    if (member instanceof TerminalNode) {
                        super.assignValue(member, values.get("." + member.lexeme()), env, current);
                    } else {
                        TUPLE_UNPACKER.assignValue(member, values.get(member.lexeme()), env, current);
                    }
                }
            }
        }

        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            return DEFAULT.interpret(node, env, current);
        }
    },

    /**
     * 实体方法调用解释器。
     * <p>处理对象方法的调用，支持以下特性：
     * <ul>
     *   <li>通过 {@code this} 上下文传递对象实例</li>
     *   <li>支持链式方法调用</li>
     * </ul>
     */
    ENTITY_CALL {
        @Override
        public void assignValue(SyntaxNode node, ReturnValue value, ASTEnv env, ActivationContext current)
                throws OhPanic {
            EntityCallNode call = ObjectUtils.cast(node);
            Map<String, ReturnValue> owner = (Map<String, ReturnValue>) call.entity().interpret(env, current).value();
            ReturnValue fieldValue = owner.get(call.member().lexeme());
            if (fieldValue != null) {
                fieldValue.update(value);
            } else {
                RuntimeError.FIELD_NOT_FOUND.raise();
            }
        }

        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            EntityCallNode call = ObjectUtils.cast(node);
            ReturnValue hostValue = call.entity().interpret(env, current);
            String name = call.member().lexeme();
            ReturnValue value = hostValue.get(name);
            current.putThis(hostValue);
            return value;
        }
    },

    /**
     * 映射声明解释器。
     * <p>处理键值对集合的初始化，支持 {@code "key": value} 语法。</p>
     */
    MAP_DECLARE {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            MapDeclareNode map = ObjectUtils.cast(node);
            ActivationContext newCurrent = env.push(node.scope(), env, current);
            Map<String, Object> values = new HashMap<>();
            List<SyntaxNode> items = map.items();
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).nodeType() == Terminal.STRING_COLON) {
                    values.put(items.get(i).lexeme().replaceAll(":|\"", ""), items.get(++i).interpret(env, newCurrent));
                }
            }
            ReturnValue value = new ReturnValue(newCurrent, node.typeExpr(), values);
            newCurrent.put(map.declaredName(), value);
            return value;
        }
    },

    /**
     * 数组声明解释器。
     * <p>处理元素列表初始化，支持多类型元素混合存储。</p>
     */
    ARRAY_DECLARE {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            ArrayDeclareNode array = ObjectUtils.cast(node);
            ActivationContext newCurrent = env.push(node.scope(), env, current);
            List<Object> values = new ArrayList<>();
            for (SyntaxNode item : array.items()) {
                values.add(item.interpret(env, newCurrent));
            }
            ReturnValue value = new ReturnValue(newCurrent, node.typeExpr(), values);
            newCurrent.put(array.declaredName(), value);
            return value;
        }
    },

    /**
     * 数组访问解释器。
     * <p>处理下标访问操作，支持数组越界异常检测。</p>
     */
    ARRAY_ACCESS {
        @Override
        public void assignValue(SyntaxNode node, ReturnValue value, ASTEnv env, ActivationContext current)
                throws OhPanic {
            ArrayAccessNode call = ObjectUtils.cast(node);
            Object owner = call.array().interpret(env, current).value();
            ReturnValue fieldValue = null;
            // array access
            if (owner instanceof List) {
                fieldValue = ((List<ReturnValue>) owner).get(
                        ObjectUtils.cast(call.index().interpret(env, current).value()));
            }
            // map access
            if (owner instanceof Map) {
                fieldValue = ((Map<String, ReturnValue>) owner).get(
                        ObjectUtils.cast(call.index().interpret(env, current).value()));
                if (fieldValue == null) {
                    fieldValue = new ReturnValue(current, value.typeExpr(), null);
                    ((Map<String, ReturnValue>) owner).put(
                            ObjectUtils.cast(call.index().interpret(env, current).value()), fieldValue);
                }
            }
            if (fieldValue != null) {
                fieldValue.update(value);
            } else {
                RuntimeError.FIELD_NOT_FOUND.raise();
            }
        }

        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            ArrayAccessNode call = ObjectUtils.cast(node);
            Object owner = call.array().interpret(env, current).value();
            if (owner instanceof List) {
                return (ObjectUtils.<List<ReturnValue>>cast(owner)).get(
                        ObjectUtils.cast(call.index().interpret(env, current).value()));
            }
            if (owner instanceof Map) {
                return (ObjectUtils.<Map<String, ReturnValue>>cast(owner)).get(
                        ObjectUtils.cast(call.index().interpret(env, current).value()));
            }
            RuntimeError.NOT_MAP_OR_ARRAY.raise();
            return null;
        }
    },

    /**
     * 实体主体解释器。
     * <p>处理类/实体的成员声明，支持继承和组合基类值。</p>
     */
    ENTITY_BODY {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            EntityBodyNode body = ObjectUtils.cast(node);
            if (body.host() == null) {
                return GENERAL.interpret(node, env, current);
            }
            // get base value
            ReturnValue value = body.host().interpret(env, current);
            ActivationContext newCurrent = env.push(body.host().scope(), env, current);
            for (InitialAssignmentNode member : body.members()) {
                member.interpret(env, newCurrent);
            }
            // create new value in terms of base value
            ReturnValue newValue = new ReturnValue(newCurrent, node.typeExpr(), value);
            // add my added and changed methods
            newValue.myMethods().putAll(newCurrent.all());
            // add property .base to invoke the base methods
            newValue.myMethods().put("." + Constants.BASE, newValue.base());
            // update current activation
            newCurrent.putAll(newValue.methods());
            newCurrent.put(body.declaredName(), newValue);
            return newValue;
        }
    },

    /**
     * Java 对象创建解释器
     * <p>通过反射机制实例化外部 Java 类，并完成以下操作：
     * <ul>
     *   <li>自动匹配脚本字段与 Java 类字段</li>
     *   <li>支持方法覆盖和扩展</li>
     * </ul>
     */
    JAVA_NEW {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            JavaNewNode javaNode = ObjectUtils.cast(node);
            Class<?> clazz = javaNode.ast().asf().externalClasses().get(javaNode.javaClass().lexeme());
            try {
                Map<String, ReturnValue> instance = (Map<String, ReturnValue>) javaNode.entity()
                        .interpret(env, current)
                        .value();
                Object realInstance = Tool.createInstance(clazz);
                javaNode.ast().matchEntityFields(instance, realInstance);
                Map<String, ReturnValue> finalValue = (Map<String, ReturnValue>) javaNode.ast()
                        .mockReturnValue(env, realInstance, current);
                override(finalValue, instance);
                return new ReturnValue(current, TypeExprFactory.createExternal(javaNode.ast().start()), finalValue);
            } catch (Exception e) {
                throw new OhPanic(e.getMessage(), Constants.UNKNOWN_ERROR);
            }
        }

        private void override(Map<String, ReturnValue> finalValue, Map<String, ReturnValue> instance) {
            finalValue.forEach((k, v) -> {
                if (instance.containsKey(k)) {
                    finalValue.put(k, instance.get(k));
                }
            });
        }
    },

    /**
     * Java 静态调用解释器。
     * <p>通过反射调用外部Java类的静态方法。</p>
     */
    JAVA_STATIC_CALL {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) {
            Class<?> clazz = node.ast().asf().externalClasses().get(node.child(0).lexeme());
            return null;
        }
    },

    /**
     * 返回语句解释器。
     * <p>处理 {@code return} 关键字，终止当前函数执行并返回值。</p>
     */
    RETURN_STATEMENT {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            if (node.child(0).nodeType() == Terminal.RETURN) {
                return node.child(1).interpret(env, current);
            } else {
                return node.child(0).interpret(env, current);
            }
        }
    },

    /**
     * 变量赋值解释器。
     * <p>处理复合赋值操作（+=、-=等），支持类型自动提升。</p>
     */
    VAR_ASSIGNMENT {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            return INITIAL_ASSIGNMENT.interpret(node, env, current);
        }
    },

    /**
     * 外部数据解释器。
     * <p>加载外部数据源连接，返回特定格式的数据包装对象。</p>
     */
    EXTERNAL_DATA {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) {
            return new ReturnValue(current, TypeExprFactory.createExternal(node),
                    (ObjectUtils.<ExternalDataNode>cast(node)).getData());
        }
    },

    /**
     * 初始化赋值解释器。
     * <p>处理变量声明时的初始化赋值，支持延迟求值。</p>
     */
    INITIAL_ASSIGNMENT {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            SyntaxNode var = node.child(0);
            if (node.childCount() < 3) {
                var.assignValue(new ReturnValue(current, TypeExprFactory.createUnknown(), null), env, current);
            } else {
                TerminalNode operator = ObjectUtils.cast(node.child(1));
                ReturnValue value;
                ReturnValue rightValue = node.child(2).interpret(env, current);
                switch (operator.nodeType()) {
                    case PLUS_EQUAL:
                        value = new NumericOp().op(var.interpret(env, current), Terminal.PLUS, rightValue);
                        break;
                    case MINUS_EQUAL:
                        value = new NumericOp().op(var.interpret(env, current), Terminal.MINUS, rightValue);
                        break;
                    case STAR_EQUAL:
                        value = new NumericOp().op(var.interpret(env, current), Terminal.STAR, rightValue);
                        break;
                    case SLASH_EQUAL:
                        value = new NumericOp().op(var.interpret(env, current), Terminal.SLASH, rightValue);
                        break;
                    default:
                        value = rightValue;
                }
                var.assignValue(value, env, current);
            }
            return ReturnValue.IGNORE;
        }
    },

    /**
     * 系统方法解释器。
     * <p>处理内置系统方法的调用，如类型转换、IO操作等。</p>
     */
    SYS_METHOD {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            return SystemMethodInterpreters.interpret(node, env, current);
        }
    },

    /**
     * 忽略未找到错误解释器。
     * <p>安全忽略符号查找失败的情况，返回忽略标记。</p>
     */
    ERROR_NOT_FOUND_IGNORE {
        @Override
        public ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic {
            return ReturnValue.IGNORE;
        }
    };

    private static Map<Object, ReentrantLock> locks = new ConcurrentHashMap();

    private static ReentrantLock staticLock = new ReentrantLock();

    private static AtomicInteger testCounter = new AtomicInteger();

    /**
     * This method is used to create a number value based on the result of a calculation.
     * It checks if the result has a scale of 0 or if it is a whole number. If so, it returns
     * an integer value. Otherwise, it returns a double value.
     *
     * @param result The result of a calculation.
     * @param node The syntax node representing the number.
     * @param env The current AST environment.
     * @param current The current activation context.
     * @return A ReturnValue object representing the created number value.
     */
    protected static ReturnValue createNumberValue(BigDecimal result, SyntaxNode node, ASTEnv env,
            ActivationContext current) {
        if (result.scale() <= 0 || result.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) == 0) {
            return new ReturnValue(current, TypeExprFactory.createNumber(node), result.intValue());
        } else {
            return new ReturnValue(current, TypeExprFactory.createNumber(node), result.doubleValue());
        }
    }

    /**
     * This method creates a ReturnValue object with the given Boolean value, type expression, and context.
     * It is used to create a ReturnValue object that represents a boolean value.
     *
     * @param bool The Boolean value to be included in the ReturnValue object.
     * @param node The SyntaxNode that provides the type expression for the ReturnValue object.
     * @param env The ASTEnv object that provides the environment for the ReturnValue object.
     * @param current The ActivationContext object that provides the current context for the ReturnValue object.
     * @return A ReturnValue object that represents a boolean value.
     */
    protected static ReturnValue createBoolValue(Boolean bool, SyntaxNode node, ASTEnv env, ActivationContext current) {
        return new ReturnValue(current, TypeExprFactory.createBool(node), bool);
    }

    private static ReturnValue threeAddressOp(SyntaxNode node, ASTEnv env, ThreeAddressOp op, ActivationContext current)
            throws OhPanic {
        int cursor = 0;
        ReturnValue tmp = node.child(0).interpret(env, current);
        while (cursor < node.childCount() - 1) {
            tmp = op.op(tmp, node.child(++cursor).nodeType(), node.child(++cursor).interpret(env, current));
        }
        return tmp;
    }

    /**
     * This method is used to interpret the syntax node and return the corresponding value.
     * It is an abstract method that needs to be implemented by each enum constant.
     *
     * @param node The syntax node to be interpreted.
     * @param env The current AST environment.
     * @param current The current activation context.
     * @return A ReturnValue object representing the value of the interpreted syntax node.
     * @throws OhPanic If an error occurs during the interpretation process.
     */
    public abstract ReturnValue interpret(SyntaxNode node, ASTEnv env, ActivationContext current) throws OhPanic;

    /**
     * This method is used to interpret a block of code. It takes a syntax node, an
     * AST environment, and an activation context,
     * and a handler function that is used to interpret the syntax node. It creates
     * a new activation context for the block and
     * calls the handler function to interpret the syntax node. It then returns the
     * value returned by the handler function.
     *
     * @param node The syntax node to be interpreted.
     * @param env The current AST environment.
     * @param current The current activation context.
     * @param handler The function used to interpret the syntax node.
     * @return The value returned by the handler function.
     * @throws OhPanic if an error occurs during the interpretation of the syntax node.
     */
    protected ReturnValue interpretBlock(SyntaxNode node, ASTEnv env, ActivationContext current,
            TriFunction<SyntaxNode, ASTEnv, ActivationContext, ReturnValue> handler) throws OhPanic {
        ActivationContext newCurrent = env.push(node.scope(), current);
        return handler.apply(node, env, newCurrent);
    }

    /**
     * This method is used to assign a value to a syntax node. It takes a syntax node, a return value,
     * an AST environment, and an activation context as parameters. It checks if the syntax node is an
     * ID or a NUMBER. If it is, it assigns the value to the activation context using the lexeme of the
     * syntax node as the key. If the lexeme is "_", it does nothing. If the syntax node is not an ID
     * or a NUMBER, it raises an error.
     *
     * @param node The syntax node to assign the value to.
     * @param value The value to be assigned to the syntax node.
     * @param env The current AST environment.
     * @param current The current activation context.
     * @throws OhPanic if the syntax node is not an ID or a NUMBER.
     */
    public void assignValue(SyntaxNode node, ReturnValue value, ASTEnv env, ActivationContext current) throws OhPanic {
        final String underLine = "_";
        if (node.nodeType() == Terminal.ID || node.nodeType() == Terminal.NUMBER) {
            TerminalNode terminal = ObjectUtils.cast(node);
            if (terminal.lexeme().equals(underLine)) {
                return;
            }
            current.put(terminal, value.clone()); // value==null?null:value.clone());
        } else {
            RuntimeError.NOT_ASSIGNABLE.raise();
        }
    }
}

/**
 * 函数式接口，对给定的两个参数和操作符，求值结果的操作
 */
@FunctionalInterface
interface ThreeAddressOp {
    /**
     * This method is used to perform an operation on two return values and a terminal operator.
     * It takes a return value, a terminal operator, and another return value as parameters. It
     * performs the operation specified by the terminal operator on the two return values and
     * returns the result.
     *
     * @param x The first return value.
     * @param op The terminal operator to be used in the operation.
     * @param y The second return value.
     * @return The result of the operation on the two return values.
     */
    ReturnValue op(ReturnValue x, Terminal op, ReturnValue y);
}

/**
 * 链式操作
 *
 * @param <T> 返回值类型
 */
interface Chain<T> {
    /**
     * 构建链式操作
     *
     * @param chain 链式操作
     * @return 链式操作
     */
    static <T> Chain<T> build(Chain<T> chain) {
        return chain;
    }

    /**
     * 执行链式操作
     *
     * @return 链式操作的结果
     */
    T handle();

    /**
     * 添加下一个链式操作
     *
     * @param chain 下一个链式操作
     * @return 链式操作
     */
    default Chain<T> next(Chain<T> chain) {
        return () -> Optional.ofNullable(this.handle()).orElseGet(chain::handle);
    }
}

/**
 * 数值型操作
 *
 * @since 1.0
 */
class NumericOp implements ThreeAddressOp {
    @Override
    public ReturnValue op(ReturnValue x, Terminal op, ReturnValue y) {
        boolean hasStr = x.typeExpr() instanceof StringTypeExpr || y.typeExpr() instanceof StringTypeExpr;
        boolean hasDouble = x.value() instanceof Double || y.value() instanceof Double;
        return Chain.build(() -> plus(x, op, y, hasStr, hasDouble))
                .next(() -> minus(x, op, y, hasStr, hasDouble))
                .next(() -> star(x, op, y, hasStr, hasDouble))
                .next(() -> slash(x, op, y, hasStr, hasDouble))
                .next(() -> mod(x, op, y, hasStr, hasDouble))
                .next(() -> {
                    throw new IllegalArgumentException();
                })
                .handle();
    }

    private ReturnValue mod(ReturnValue x, Terminal op, ReturnValue y, boolean hasStr, boolean hasDouble) {
        if (op != Terminal.MOD || hasStr) {
            return null;
        }
        Number result;
        if (hasDouble) {
            result = (ObjectUtils.<Number>cast(x.value())).doubleValue() % (ObjectUtils.<Number>cast(
                    y.value())).doubleValue();
        } else {
            result = (ObjectUtils.<Number>cast(x.value())).intValue() % (ObjectUtils.<Number>cast(
                    y.value())).intValue();
        }
        return new ReturnValue(x.context(), TypeExprFactory.createNumber(x.typeExpr().node()), result);
    }

    private ReturnValue slash(ReturnValue x, Terminal op, ReturnValue y, boolean hasStr, boolean hasDouble) {
        if (op != Terminal.SLASH || hasStr) {
            return null;
        }
        Number result;
        if (hasDouble) {
            result = (ObjectUtils.<Number>cast(x.value())).doubleValue() / (ObjectUtils.<Number>cast(
                    y.value())).doubleValue();
        } else {
            result = (ObjectUtils.<Number>cast(x.value())).intValue() / (ObjectUtils.<Number>cast(
                    y.value())).intValue();
        }
        return new ReturnValue(x.context(), TypeExprFactory.createNumber(x.typeExpr().node()), result);
    }

    private ReturnValue star(ReturnValue x, Terminal op, ReturnValue y, boolean hasStr, boolean hasDouble) {
        if (op != Terminal.STAR || hasStr) {
            return null;
        }
        Number result;
        if (hasDouble) {
            result = (ObjectUtils.<Number>cast(x.value())).doubleValue() * (ObjectUtils.<Number>cast(
                    y.value())).doubleValue();
        } else {
            result = (ObjectUtils.<Number>cast(x.value())).intValue() * (ObjectUtils.<Number>cast(
                    y.value())).intValue();
        }
        return new ReturnValue(x.context(), TypeExprFactory.createNumber(x.typeExpr().node()), result);
    }

    private ReturnValue minus(ReturnValue x, Terminal op, ReturnValue y, boolean hasStr, boolean hasDouble) {
        if (op != Terminal.MINUS || hasStr) {
            return null;
        }
        Number result;
        if (hasDouble) {
            result = (ObjectUtils.<Number>cast(x.value())).doubleValue() - (ObjectUtils.<Number>cast(
                    y.value())).doubleValue();
        } else {
            result = (ObjectUtils.<Number>cast(x.value())).intValue() - (ObjectUtils.<Number>cast(
                    y.value())).intValue();
        }
        return new ReturnValue(x.context(), TypeExprFactory.createNumber(x.typeExpr().node()), result);
    }

    private ReturnValue plus(ReturnValue x, Terminal op, ReturnValue y, boolean hasStr, boolean hasDouble) {
        if (op != Terminal.PLUS) {
            return null;
        }
        Number result;
        if (hasStr) {
            return new ReturnValue(x.context(), TypeExprFactory.createString(x.typeExpr().node()),
                    x.value() + String.valueOf(y.value()));
        } else {
            try {
                if (hasDouble) {
                    result = (ObjectUtils.<Number>cast(x.value())).doubleValue() + (ObjectUtils.<Number>cast(
                            y.value())).doubleValue();
                } else {
                    result = (ObjectUtils.<Number>cast(x.value())).intValue() + (ObjectUtils.<Number>cast(
                            y.value())).intValue();
                }
            } catch (Exception e) {
                return new ReturnValue(x.context(), TypeExprFactory.createString(x.typeExpr().node()),
                        x.value() + String.valueOf(y.value()));
            }
        }
        return new ReturnValue(x.context(), TypeExprFactory.createNumber(x.typeExpr().node()), result);
    }
}