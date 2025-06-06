/*---------------------------------------------------------------------------------------------
 *  Copyright (c) 2024 Huawei Technologies Co., Ltd. All rights reserved.
 *  This file is a part of the ModelEngine Project.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

package modelengine.fit.ohscript.script.semanticanalyzer.type.expressions.concretes;

import modelengine.fit.ohscript.script.parser.nodes.SyntaxNode;
import modelengine.fit.ohscript.script.parser.nodes.TerminalNode;
import modelengine.fit.ohscript.script.semanticanalyzer.Type;
import modelengine.fit.ohscript.script.semanticanalyzer.type.expressions.base.SimpleTypeExpr;
import modelengine.fit.ohscript.script.semanticanalyzer.type.expressions.base.TypeExpr;

/**
 * undefined 类似javaScript中Undefined，用于处理属性未找到的情况
 *
 * @since 1.0
 */
public class UndefinedTypeExpr extends SimpleTypeExpr {
    /**
     * 无参构造函数，创建一个未定义类型表达式
     */
    public UndefinedTypeExpr() {
        super(null);
    }

    /**
     * 带语法节点的构造函数
     * 
     * @param node 语法节点
     */
    public UndefinedTypeExpr(SyntaxNode node) {
        super(node);
    }

    @Override
    public boolean is(TypeExpr expr) {
        return true;
    }

    @Override
    public TypeExpr duplicate(TerminalNode node) {
        return new UndefinedTypeExpr(node);
    }

    @Override
    public Type type() {
        return Type.UNDEFINED;
    }
}
