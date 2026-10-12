package cn.cordys.crm.aiagent.service;

import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.NextValExpression;
import net.sf.jsqlparser.expression.UserVariable;
import net.sf.jsqlparser.expression.VariableAssignment;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.TableFunction;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.util.deparser.ExpressionDeParser;
import net.sf.jsqlparser.util.deparser.SelectDeParser;
import net.sf.jsqlparser.util.deparser.StatementDeParser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Shared AST validation for model SQL and server-built SQL. */
final class AiAgentSqlParser {
    private static final Set<String> FUNCTIONS = Set.of(
            "count", "sum", "avg", "min", "max", "coalesce", "ifnull", "nullif", "if",
            "round", "abs", "ceil", "ceiling", "floor", "mod", "power", "sqrt",
            "lower", "upper", "trim", "ltrim", "rtrim", "concat", "concat_ws",
            "length", "char_length", "substring", "substr", "left", "right", "replace", "locate",
            "date", "date_format", "year", "month", "day", "dayofmonth", "quarter", "week",
            "date_add", "date_sub", "datediff", "timestampdiff", "timestampadd", "now",
            "current_date", "current_timestamp", "from_unixtime", "unix_timestamp",
            "current_time", "localtime", "localtimestamp",
            "group_concat", "format", "json_extract", "json_unquote",
            "to_char", "to_date", "to_timestamp", "date_trunc", "string_agg", "nvl", "nvl2",
            "len", "datepart", "dateadd", "getdate", "eomonth", "add_months", "last_day", "trunc",
            "row_number", "rank", "dense_rank", "lag", "lead", "first_value", "last_value", "ntile"
    );

    private AiAgentSqlParser() { }

    static ParsedSql parse(String sql, boolean allowParameters) {
        if (sql == null || sql.isBlank() || sql.length() > 100_000) {
            throw new IllegalArgumentException("SQL is empty or exceeds the size limit");
        }
        Select select;
        try {
            var statements = CCJSqlParserUtil.parseStatements(sql, parser -> parser.withSquareBracketQuotation(true));
            if (statements.size() != 1 || !(statements.get(0) instanceof Select query)) {
                throw new IllegalArgumentException("Only one SELECT query is allowed");
            }
            select = query;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("SQL syntax could not be parsed", e);
        }
        Set<String> ctes = new LinkedHashSet<>();
        List<Table> references = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        ExpressionDeParser expressions = new TraversingExpressions() {
            @Override
            public void visit(Function function) {
                checkFunction(function.getName());
                if (function.getAttribute() != null || function.getKeep() != null) {
                    throw new IllegalArgumentException("Unsupported function extension");
                }
                super.visit(function);
            }

            @Override
            public void visit(AnalyticExpression function) {
                checkFunction(function.getName());
                if (function.getKeep() != null || function.getWindowElement() != null || function.getWindowName() != null
                        || function.getFuncOrderBy() != null && !function.getFuncOrderBy().isEmpty()) {
                    throw new IllegalArgumentException("Unsupported window extension");
                }
                super.visit(function);
            }

            @Override
            public void visit(JdbcNamedParameter parameter) {
                if (!allowParameters) throw new IllegalArgumentException("Model SQL cannot supply bind parameters");
                super.visit(parameter);
            }

            @Override
            public void visit(JdbcParameter parameter) {
                if (!allowParameters) throw new IllegalArgumentException("Model SQL cannot supply bind parameters");
                super.visit(parameter);
            }

            @Override
            public void visit(UserVariable variable) { throw new IllegalArgumentException("SQL variables are not allowed"); }

            @Override
            public void visit(VariableAssignment assignment) { throw new IllegalArgumentException("SQL assignments are not allowed"); }

            @Override
            public void visit(NextValExpression expression) { throw new IllegalArgumentException("Sequence mutations are not allowed"); }

            @Override
            public void visit(net.sf.jsqlparser.expression.NumericBind parameter) {
                throw new IllegalArgumentException("Numeric SQL bindings are not allowed");
            }

            @Override
            public void visit(net.sf.jsqlparser.expression.TimeKeyExpression expression) {
                checkFunction(expression.getStringValue());
                super.visit(expression);
            }

            @Override
            public void visit(net.sf.jsqlparser.statement.select.AllColumns columns) {
                if (columns.getExceptColumns() != null || columns.getReplaceExpressions() != null) {
                    throw new IllegalArgumentException("Wildcard extensions are not allowed");
                }
                super.visit(columns);
            }
        };
        SelectDeParser validator = new SelectDeParser(expressions, buffer) {
            private final Deque<Set<String>> scopes = new ArrayDeque<>();

            private void enter() {
                scopes.push(new LinkedHashSet<>());
            }

            @Override
            public void visit(PlainSelect query) {
                if (query.isForUpdate() || query.getForClause() != null
                        || query.getIntoTables() != null && !query.getIntoTables().isEmpty()
                        || query.isSkipLocked() || query.isNoWait()) {
                    throw new IllegalArgumentException("SELECT INTO and locking queries are not allowed");
                }
                if (query.getWindowDefinitions() != null && !query.getWindowDefinitions().isEmpty()
                        || query.getOracleHint() != null || query.getOracleHierarchical() != null
                        || query.getQualify() != null || query.getKsqlWindow() != null || query.isEmitChanges()
                        || query.getLateralViews() != null && !query.getLateralViews().isEmpty()) {
                    throw new IllegalArgumentException("Unsupported SELECT extension");
                }
                enter();
                super.visit(query);
                scopes.pop();
            }

            @Override
            public void visit(SetOperationList query) {
                enter();
                super.visit(query);
                scopes.pop();
            }

            @Override
            public void visit(ParenthesedSelect query) {
                enter();
                super.visit(query);
                scopes.pop();
            }

            @Override
            public void visit(WithItem item) {
                if (item.isRecursive()) throw new IllegalArgumentException("Recursive CTEs are not allowed");
                ctes.add(identifier(item.getAlias().getName()));
                super.visit(item);
                // Nonrecursive definitions can see earlier CTEs, never their own or later names.
                scopes.peek().add(identifier(item.getAlias().getName()));
            }

            @Override
            public void visit(Table table) {
                String name = identifier(table.getFullyQualifiedName());
                if (scopes.stream().noneMatch(scope -> scope.contains(name))) references.add(table);
                super.visit(table);
            }

            @Override
            public void visit(TableFunction function) { throw new IllegalArgumentException("Table functions are not allowed"); }
        };
        expressions.setSelectVisitor(validator);
        expressions.setBuffer(buffer);
        select.accept(new StatementDeParser(expressions, validator, buffer));
        Set<String> tables = new LinkedHashSet<>();
        references.forEach(table -> tables.add(identifier(table.getFullyQualifiedName())));
        if (tables.isEmpty()) throw new IllegalArgumentException("A configured business table is required");
        return new ParsedSql(select, tables, ctes, references);
    }

    static String identifier(String value) {
        return value.replace("`", "").replace("\"", "").replace("[", "").replace("]", "").toLowerCase(Locale.ROOT);
    }

    private static void checkFunction(String name) {
        if (name == null || !FUNCTIONS.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("SQL function is not allowed: " + name);
        }
    }

    static String allowedFunctionsPrompt() {
        return FUNCTIONS.stream().sorted().collect(java.util.stream.Collectors.joining(", "));
    }

    // JSQLParser 4.7 renders several expression types with toString(), skipping child visitors.
    // Traverse the supported MySQL forms explicitly so validation and permission rewriting see every child.
    static class TraversingExpressions extends ExpressionDeParser {
        @Override
        public void visit(net.sf.jsqlparser.expression.CollateExpression expression) {
            expression.getLeftExpression().accept(this);
            getBuffer().append(" COLLATE ").append(expression.getCollate());
        }

        @Override
        public void visit(net.sf.jsqlparser.expression.operators.relational.IsDistinctExpression expression) {
            expression.getLeftExpression().accept(this);
            getBuffer().append(' ').append(expression.getStringExpression()).append(' ');
            expression.getRightExpression().accept(this);
        }

        @Override
        public void visit(net.sf.jsqlparser.expression.MySQLGroupConcat expression) {
            getBuffer().append("GROUP_CONCAT(");
            if (expression.isDistinct()) getBuffer().append("DISTINCT ");
            expression.getExpressionList().accept(this);
            if (expression.getOrderByElements() != null && !expression.getOrderByElements().isEmpty()) {
                getBuffer().append(" ORDER BY ");
                for (int i = 0; i < expression.getOrderByElements().size(); i++) {
                    if (i > 0) getBuffer().append(", ");
                    var order = expression.getOrderByElements().get(i);
                    order.getExpression().accept(this);
                    getBuffer().append(order.isAsc() ? " ASC" : " DESC");
                }
            }
            if (expression.getSeparator() != null) {
                // MySQL requires a literal separator, rather than a bind marker.
                try {
                    var separator = CCJSqlParserUtil.parseExpression(expression.getSeparator());
                    if (!(separator instanceof net.sf.jsqlparser.expression.StringValue)) {
                        throw new IllegalArgumentException("GROUP_CONCAT separator must be a string literal");
                    }
                    getBuffer().append(" SEPARATOR ").append(separator);
                } catch (net.sf.jsqlparser.JSQLParserException e) {
                    throw new IllegalArgumentException("Invalid GROUP_CONCAT separator", e);
                }
            }
            getBuffer().append(')');
        }

        @Override
        public void visit(net.sf.jsqlparser.expression.JsonExpression expression) { throw unsupported(); }
        @Override
        public void visit(net.sf.jsqlparser.expression.JsonFunction expression) { throw unsupported(); }
        @Override
        public void visit(net.sf.jsqlparser.expression.JsonAggregateFunction expression) { throw unsupported(); }
        @Override
        public void visit(net.sf.jsqlparser.expression.OverlapsCondition expression) { throw unsupported(); }
        @Override
        public void visit(net.sf.jsqlparser.expression.KeepExpression expression) { throw unsupported(); }
        @Override
        public void visit(net.sf.jsqlparser.expression.XMLSerializeExpr expression) { throw unsupported(); }
        @Override
        public void visit(net.sf.jsqlparser.expression.operators.relational.FullTextSearch expression) { throw unsupported(); }

        private IllegalArgumentException unsupported() {
            return new IllegalArgumentException("Unsupported SQL expression extension");
        }
    }

    record ParsedSql(Select select, Set<String> tables, Set<String> ctes, List<Table> tableReferences) { }
}
