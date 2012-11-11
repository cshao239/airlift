package com.facebook.presto.sql.planner;

import com.facebook.presto.metadata.ColumnHandle;
import com.facebook.presto.sql.ExpressionFormatter;
import com.facebook.presto.sql.compiler.Symbol;
import com.facebook.presto.sql.compiler.Type;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.FunctionCall;
import com.facebook.presto.sql.tree.QualifiedNameReference;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;

import java.util.List;
import java.util.Map;

public class PlanPrinter
{
    public void print(PlanNode plan, Map<Symbol, Type> types)
    {
        Visitor visitor = new Visitor(types);
        plan.accept(visitor, 0);
    }

    private void print(int indent, String format, Object... args)
    {
        String value;

        if (args.length == 0) {
            value = format;
        }
        else {
            value = String.format(format, args);
        }

        System.out.println(Strings.repeat("    ", indent) + value);
    }

    private class Visitor
            extends PlanVisitor<Integer, Void>
    {
        private final Map<Symbol, Type> types;

        public Visitor(Map<Symbol, Type> types)
        {
            this.types = types;
        }

        @Override
        public Void visitJoin(JoinNode node, Integer indent)
        {
            print(indent, "- Join[%s] => [%s]", ExpressionFormatter.toString(node.getCriteria()), formatOutputs(node.getOutputSymbols()));
            node.getLeft().accept(this, indent + 1);
            node.getRight().accept(this, indent + 1);

            return null;
        }

        @Override
        public Void visitLimit(LimitNode node, Integer indent)
        {
            print(indent, "- Limit[%s] => [%s]", node.getCount(), formatOutputs(node.getOutputSymbols()));
            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitAggregation(AggregationNode node, Integer indent)
        {
            String type = "";
            if (node.getStep() != AggregationNode.Step.SINGLE) {
                type = String.format("(%s)", node.getStep().toString());
            }
            String key = "";
            if (!node.getGroupBy().isEmpty()) {
                key = node.getGroupBy().toString();
            }

            print(indent, "- Aggregate%s%s => [%s]", type, key, formatOutputs(node.getOutputSymbols()));

            for (Map.Entry<Symbol, FunctionCall> entry : node.getAggregations().entrySet()) {
                print(indent + 2, "%s := %s", entry.getKey(), ExpressionFormatter.toString(entry.getValue()));
            }

            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitTableScan(TableScan node, Integer indent)
        {
            print(indent, "- TableScan[%s] => [%s]", node.getTable(), formatOutputs(node.getOutputSymbols()));
            for (Map.Entry<Symbol, ColumnHandle> entry : node.getAssignments().entrySet()) {
                print(indent + 2, "%s := %s", entry.getKey(), entry.getValue());
            }

            return null;
        }

        @Override
        public Void visitFilter(FilterNode node, Integer indent)
        {
            print(indent, "- Filter[%s] => [%s]", ExpressionFormatter.toString(node.getPredicate()), formatOutputs(node.getOutputSymbols()));
            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitProject(ProjectNode node, Integer indent)
        {
            print(indent, "- Project => [%s]", formatOutputs(node.getOutputSymbols()));
            for (Map.Entry<Symbol, Expression> entry : node.getOutputMap().entrySet()) {
                if (entry.getValue() instanceof QualifiedNameReference && ((QualifiedNameReference) entry.getValue()).getName().equals(entry.getKey().toQualifiedName())) {
                    // skip identity assignments
                    continue;
                }
                print(indent + 2, "%s := %s", entry.getKey(), ExpressionFormatter.toString(entry.getValue()));
            }

            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitOutput(OutputPlan node, Integer indent)
        {
            print(indent, "- Output[%s]", Joiner.on(", ").join(node.getColumnNames()));
            for (int i = 0; i < node.getColumnNames().size(); i++) {
                String name = node.getColumnNames().get(i);
                if (!name.equals(node.getAssignments().get(name).toString())) {
                    print(indent + 2, "%s := %s", name, node.getAssignments().get(name));
                }
            }

            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitTopN(final TopNNode node, Integer indent)
        {
            Iterable<String> keys = Iterables.transform(node.getOrderBy(), new Function<Symbol, String>()
            {
                @Override
                public String apply(Symbol input)
                {
                    return input + " " + node.getOrderings().get(input);
                }
            });

            print(indent, "- TopN[%s by (%s)] => [%s]", node.getCount(), Joiner.on(", ").join(keys), formatOutputs(node.getOutputSymbols()));
            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitExchange(ExchangeNode node, Integer indent)
        {
            print(indent, "- Exchange[%s] => [%s]", node.getSourceFragmentId(), formatOutputs(node.getOutputSymbols()));

            return processChildren(node, indent + 1);
        }

        @Override
        protected Void visitPlan(PlanNode node, Integer context)
        {
            throw new UnsupportedOperationException("not yet implemented");
        }

        private Void processChildren(PlanNode node, int indent)
        {
            for (PlanNode child : node.getSources()) {
                child.accept(this, indent);
            }

            return null;
        }

        private String formatOutputs(List<Symbol> symbols)
        {
            return Joiner.on(", ").join(Iterables.transform(symbols, new Function<Symbol, String>()
            {
                @Override
                public String apply(Symbol input)
                {
                    return input + ":" + types.get(input);
                }
            }));
        }
    }
}