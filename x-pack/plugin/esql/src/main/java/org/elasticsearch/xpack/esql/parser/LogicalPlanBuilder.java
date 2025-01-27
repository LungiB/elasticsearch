/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.parser;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.elasticsearch.Build;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.dissect.DissectException;
import org.elasticsearch.dissect.DissectParser;
import org.elasticsearch.index.IndexMode;
import org.elasticsearch.xpack.esql.VerificationException;
import org.elasticsearch.xpack.esql.common.Failure;
import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.EmptyAttribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Expressions;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.MetadataAttribute;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.core.expression.Order;
import org.elasticsearch.xpack.esql.core.expression.ReferenceAttribute;
import org.elasticsearch.xpack.esql.core.expression.UnresolvedAttribute;
import org.elasticsearch.xpack.esql.core.expression.UnresolvedStar;
import org.elasticsearch.xpack.esql.core.parser.ParserUtils;
import org.elasticsearch.xpack.esql.core.plan.TableIdentifier;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.core.util.Holder;
import org.elasticsearch.xpack.esql.expression.UnresolvedNamePattern;
import org.elasticsearch.xpack.esql.expression.function.UnresolvedFunction;
import org.elasticsearch.xpack.esql.parser.EsqlBaseParser.MetadataOptionContext;
import org.elasticsearch.xpack.esql.plan.logical.Aggregate;
import org.elasticsearch.xpack.esql.plan.logical.Dissect;
import org.elasticsearch.xpack.esql.plan.logical.Drop;
import org.elasticsearch.xpack.esql.plan.logical.Enrich;
import org.elasticsearch.xpack.esql.plan.logical.EsqlAggregate;
import org.elasticsearch.xpack.esql.plan.logical.EsqlUnresolvedRelation;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Explain;
import org.elasticsearch.xpack.esql.plan.logical.Filter;
import org.elasticsearch.xpack.esql.plan.logical.Grok;
import org.elasticsearch.xpack.esql.plan.logical.InlineStats;
import org.elasticsearch.xpack.esql.plan.logical.Keep;
import org.elasticsearch.xpack.esql.plan.logical.Limit;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.Lookup;
import org.elasticsearch.xpack.esql.plan.logical.MvExpand;
import org.elasticsearch.xpack.esql.plan.logical.OrderBy;
import org.elasticsearch.xpack.esql.plan.logical.Rename;
import org.elasticsearch.xpack.esql.plan.logical.Row;
import org.elasticsearch.xpack.esql.plan.logical.meta.MetaFunctions;
import org.elasticsearch.xpack.esql.plan.logical.show.ShowInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.elasticsearch.common.logging.HeaderWarning.addWarning;
import static org.elasticsearch.xpack.esql.core.parser.ParserUtils.source;
import static org.elasticsearch.xpack.esql.core.parser.ParserUtils.typedParsing;
import static org.elasticsearch.xpack.esql.core.parser.ParserUtils.visitList;
import static org.elasticsearch.xpack.esql.plan.logical.Enrich.Mode;
import static org.elasticsearch.xpack.esql.type.EsqlDataTypeConverter.stringToInt;

public class LogicalPlanBuilder extends ExpressionBuilder {

    private int queryDepth = 0;

    /**
     * Maximum number of commands allowed per query
     */
    public static final int MAX_QUERY_DEPTH = 500;

    public LogicalPlanBuilder(QueryParams params) {
        super(params);
    }

    protected LogicalPlan plan(ParseTree ctx) {
        LogicalPlan p = ParserUtils.typedParsing(this, ctx, LogicalPlan.class);
        var errors = this.params.parsingErrors();
        if (errors.hasNext() == false) {
            return p;
        } else {
            StringBuilder message = new StringBuilder();
            int i = 0;

            while (errors.hasNext()) {
                if (i > 0) {
                    message.append("; ");
                }
                message.append(errors.next().getMessage());
                i++;
            }
            throw new ParsingException(message.toString());
        }
    }

    protected List<LogicalPlan> plans(List<? extends ParserRuleContext> ctxs) {
        return ParserUtils.visitList(this, ctxs, LogicalPlan.class);
    }

    @Override
    public LogicalPlan visitSingleStatement(EsqlBaseParser.SingleStatementContext ctx) {
        return plan(ctx.query());
    }

    @Override
    public LogicalPlan visitCompositeQuery(EsqlBaseParser.CompositeQueryContext ctx) {
        queryDepth++;
        if (queryDepth > MAX_QUERY_DEPTH) {
            throw new ParsingException(
                "ESQL statement exceeded the maximum query depth allowed ({}): [{}]",
                MAX_QUERY_DEPTH,
                ctx.getText()
            );
        }
        try {
            LogicalPlan input = plan(ctx.query());
            PlanFactory makePlan = typedParsing(this, ctx.processingCommand(), PlanFactory.class);
            return makePlan.apply(input);
        } finally {
            queryDepth--;
        }
    }

    @Override
    public PlanFactory visitEvalCommand(EsqlBaseParser.EvalCommandContext ctx) {
        return p -> new Eval(source(ctx), p, visitFields(ctx.fields()));
    }

    @Override
    public PlanFactory visitGrokCommand(EsqlBaseParser.GrokCommandContext ctx) {
        return p -> {
            Source source = source(ctx);
            String pattern = visitString(ctx.string()).fold().toString();
            Grok.Parser grokParser = Grok.pattern(source, pattern);
            validateGrokPattern(source, grokParser, pattern);
            Grok result = new Grok(source(ctx), p, expression(ctx.primaryExpression()), grokParser);
            return result;
        };
    }

    private void validateGrokPattern(Source source, Grok.Parser grokParser, String pattern) {
        Map<String, DataType> definedAttributes = new HashMap<>();
        for (Attribute field : grokParser.extractedFields()) {
            String name = field.name();
            DataType type = field.dataType();
            DataType prev = definedAttributes.put(name, type);
            if (prev != null) {
                throw new ParsingException(
                    source,
                    "Invalid GROK pattern [" + pattern + "]: the attribute [" + name + "] is defined multiple times with different types"
                );
            }
        }
    }

    @Override
    public PlanFactory visitDissectCommand(EsqlBaseParser.DissectCommandContext ctx) {
        return p -> {
            String pattern = visitString(ctx.string()).fold().toString();
            Map<String, Object> options = visitCommandOptions(ctx.commandOptions());
            String appendSeparator = "";
            for (Map.Entry<String, Object> item : options.entrySet()) {
                if (item.getKey().equalsIgnoreCase("append_separator") == false) {
                    throw new ParsingException(source(ctx), "Invalid option for dissect: [{}]", item.getKey());
                }
                if (item.getValue() instanceof String == false) {
                    throw new ParsingException(
                        source(ctx),
                        "Invalid value for dissect append_separator: expected a string, but was [{}]",
                        item.getValue()
                    );
                }
                appendSeparator = (String) item.getValue();
            }
            Source src = source(ctx);

            try {
                DissectParser parser = new DissectParser(pattern, appendSeparator);
                Set<String> referenceKeys = parser.referenceKeys();
                if (referenceKeys.size() > 0) {
                    throw new ParsingException(
                        src,
                        "Reference keys not supported in dissect patterns: [%{*{}}]",
                        referenceKeys.iterator().next()
                    );
                }
                List<Attribute> keys = new ArrayList<>();
                for (var x : parser.outputKeys()) {
                    if (x.isEmpty() == false) {
                        keys.add(new ReferenceAttribute(src, x, DataType.KEYWORD));
                    }
                }
                return new Dissect(src, p, expression(ctx.primaryExpression()), new Dissect.Parser(pattern, appendSeparator, parser), keys);
            } catch (DissectException e) {
                throw new ParsingException(src, "Invalid pattern for dissect: [{}]", pattern);
            }
        };
    }

    @Override
    public PlanFactory visitMvExpandCommand(EsqlBaseParser.MvExpandCommandContext ctx) {
        UnresolvedAttribute field = visitQualifiedName(ctx.qualifiedName());
        Source src = source(ctx);
        return child -> new MvExpand(src, child, field, new UnresolvedAttribute(src, field.name()));

    }

    @Override
    public Map<String, Object> visitCommandOptions(EsqlBaseParser.CommandOptionsContext ctx) {
        if (ctx == null) {
            return Map.of();
        }
        Map<String, Object> result = new HashMap<>();
        for (EsqlBaseParser.CommandOptionContext option : ctx.commandOption()) {
            result.put(visitIdentifier(option.identifier()), expression(option.constant()).fold());
        }
        return result;
    }

    @Override
    public LogicalPlan visitRowCommand(EsqlBaseParser.RowCommandContext ctx) {
        return new Row(source(ctx), visitFields(ctx.fields()));
    }

    @Override
    public LogicalPlan visitFromCommand(EsqlBaseParser.FromCommandContext ctx) {
        Source source = source(ctx);
        TableIdentifier table = new TableIdentifier(source, null, visitIndexPattern(ctx.indexPattern()));
        Map<String, Attribute> metadataMap = new LinkedHashMap<>();
        if (ctx.metadata() != null) {
            var deprecatedContext = ctx.metadata().deprecated_metadata();
            MetadataOptionContext metadataOptionContext = null;
            if (deprecatedContext != null) {
                var s = source(deprecatedContext).source();
                addWarning(
                    "Line {}:{}: Square brackets '[]' need to be removed in FROM METADATA declaration",
                    s.getLineNumber(),
                    s.getColumnNumber()
                );
                metadataOptionContext = deprecatedContext.metadataOption();
            } else {
                metadataOptionContext = ctx.metadata().metadataOption();

            }
            for (var c : metadataOptionContext.UNQUOTED_SOURCE()) {
                String id = c.getText();
                Source src = source(c);
                if (MetadataAttribute.isSupported(id) == false) {
                    throw new ParsingException(src, "unsupported metadata field [" + id + "]");
                }
                Attribute a = metadataMap.put(id, MetadataAttribute.create(src, id));
                if (a != null) {
                    throw new ParsingException(src, "metadata field [" + id + "] already declared [" + a.source().source() + "]");
                }
            }
        }
        return new EsqlUnresolvedRelation(source, table, Arrays.asList(metadataMap.values().toArray(Attribute[]::new)), IndexMode.STANDARD);
    }

    @Override
    public PlanFactory visitStatsCommand(EsqlBaseParser.StatsCommandContext ctx) {
        final Stats stats = stats(source(ctx), ctx.grouping, ctx.stats);
        return input -> new EsqlAggregate(source(ctx), input, Aggregate.AggregateType.STANDARD, stats.groupings, stats.aggregates);
    }

    private record Stats(List<Expression> groupings, List<? extends NamedExpression> aggregates) {

    }

    private Stats stats(Source source, EsqlBaseParser.FieldsContext groupingsCtx, EsqlBaseParser.FieldsContext aggregatesCtx) {
        List<NamedExpression> groupings = visitGrouping(groupingsCtx);
        List<NamedExpression> aggregates = new ArrayList<>(visitFields(aggregatesCtx));
        if (aggregates.isEmpty() && groupings.isEmpty()) {
            throw new ParsingException(source, "At least one aggregation or grouping expression required in [{}]", source.text());
        }
        // grouping keys are automatically added as aggregations however the user is not allowed to specify them
        if (groupings.isEmpty() == false && aggregates.isEmpty() == false) {
            var groupNames = new LinkedHashSet<>(Expressions.names(groupings));
            var groupRefNames = new LinkedHashSet<>(Expressions.names(Expressions.references(groupings)));

            for (NamedExpression aggregate : aggregates) {
                Expression e = Alias.unwrap(aggregate);
                if (e.resolved() == false && e instanceof UnresolvedFunction == false) {
                    String name = e.sourceText();
                    if (groupNames.contains(name)) {
                        fail(e, "grouping key [{}] already specified in the STATS BY clause", name);
                    } else if (groupRefNames.contains(name)) {
                        fail(e, "Cannot specify grouping expression [{}] as an aggregate", name);
                    }
                }
            }
        }
        // since groupings are aliased, add refs to it in the aggregates
        for (Expression group : groupings) {
            aggregates.add(Expressions.attribute(group));
        }
        return new Stats(new ArrayList<>(groupings), aggregates);
    }

    private void fail(Expression exp, String message, Object... args) {
        throw new VerificationException(Collections.singletonList(Failure.fail(exp, message, args)));
    }

    @Override
    public PlanFactory visitInlinestatsCommand(EsqlBaseParser.InlinestatsCommandContext ctx) {
        List<NamedExpression> aggregates = new ArrayList<>(visitFields(ctx.stats));
        List<NamedExpression> groupings = visitGrouping(ctx.grouping);
        aggregates.addAll(groupings);
        return input -> new InlineStats(source(ctx), input, new ArrayList<>(groupings), aggregates);
    }

    @Override
    public PlanFactory visitWhereCommand(EsqlBaseParser.WhereCommandContext ctx) {
        Expression expression = expression(ctx.booleanExpression());
        return input -> new Filter(source(ctx), input, expression);
    }

    @Override
    public PlanFactory visitLimitCommand(EsqlBaseParser.LimitCommandContext ctx) {
        Source source = source(ctx);
        int limit = stringToInt(ctx.INTEGER_LITERAL().getText());
        return input -> new Limit(source, new Literal(source, limit, DataType.INTEGER), input);
    }

    @Override
    public PlanFactory visitSortCommand(EsqlBaseParser.SortCommandContext ctx) {
        List<Order> orders = visitList(this, ctx.orderExpression(), Order.class);
        Source source = source(ctx);
        return input -> new OrderBy(source, input, orders);
    }

    @Override
    public Explain visitExplainCommand(EsqlBaseParser.ExplainCommandContext ctx) {
        return new Explain(source(ctx), plan(ctx.subqueryExpression().query()));
    }

    @Override
    public PlanFactory visitDropCommand(EsqlBaseParser.DropCommandContext ctx) {
        List<NamedExpression> removals = visitQualifiedNamePatterns(ctx.qualifiedNamePatterns(), ne -> {
            if (ne instanceof UnresolvedStar) {
                var src = ne.source();
                throw new ParsingException(src, "Removing all fields is not allowed [{}]", src.text());
            }
        });

        return child -> new Drop(source(ctx), child, removals);
    }

    @Override
    public PlanFactory visitRenameCommand(EsqlBaseParser.RenameCommandContext ctx) {
        List<Alias> renamings = ctx.renameClause().stream().map(this::visitRenameClause).toList();
        return child -> new Rename(source(ctx), child, renamings);
    }

    @Override
    public PlanFactory visitKeepCommand(EsqlBaseParser.KeepCommandContext ctx) {
        final Holder<Boolean> hasSeenStar = new Holder<>(false);
        List<NamedExpression> projections = visitQualifiedNamePatterns(ctx.qualifiedNamePatterns(), ne -> {
            if (ne instanceof UnresolvedStar) {
                if (hasSeenStar.get()) {
                    var src = ne.source();
                    throw new ParsingException(src, "Cannot specify [*] more than once", src.text());
                } else {
                    hasSeenStar.set(Boolean.TRUE);
                }
            }
        });

        return child -> new Keep(source(ctx), child, projections);
    }

    @Override
    public LogicalPlan visitShowInfo(EsqlBaseParser.ShowInfoContext ctx) {
        return new ShowInfo(source(ctx));
    }

    @Override
    public LogicalPlan visitMetaFunctions(EsqlBaseParser.MetaFunctionsContext ctx) {
        return new MetaFunctions(source(ctx));
    }

    @Override
    public PlanFactory visitEnrichCommand(EsqlBaseParser.EnrichCommandContext ctx) {
        return p -> {
            var source = source(ctx);
            Tuple<Mode, String> tuple = parsePolicyName(ctx.policyName);
            Mode mode = tuple.v1();
            String policyNameString = tuple.v2();

            NamedExpression matchField = ctx.ON() != null ? visitQualifiedNamePattern(ctx.matchField) : new EmptyAttribute(source);
            if (matchField instanceof UnresolvedNamePattern up) {
                throw new ParsingException(source, "Using wildcards [*] in ENRICH WITH projections is not allowed [{}]", up.pattern());
            }

            List<NamedExpression> keepClauses = visitList(this, ctx.enrichWithClause(), NamedExpression.class);
            return new Enrich(
                source,
                p,
                mode,
                new Literal(source(ctx.policyName), policyNameString, DataType.KEYWORD),
                matchField,
                null,
                Map.of(),
                keepClauses.isEmpty() ? List.of() : keepClauses
            );
        };
    }

    private static Tuple<Mode, String> parsePolicyName(Token policyToken) {
        String stringValue = policyToken.getText();
        int index = stringValue.indexOf(":");
        Mode mode = null;
        if (index >= 0) {
            String modeValue = stringValue.substring(0, index);

            if (modeValue.startsWith("_")) {
                mode = Mode.from(modeValue.substring(1));
            }

            if (mode == null) {
                throw new ParsingException(
                    source(policyToken),
                    "Unrecognized value [{}], ENRICH policy qualifier needs to be one of {}",
                    modeValue,
                    Arrays.stream(Mode.values()).map(s -> "_" + s).toList()
                );
            }
        } else {
            mode = Mode.ANY;
        }

        String policyName = index < 0 ? stringValue : stringValue.substring(index + 1);
        return new Tuple<>(mode, policyName);
    }

    @Override
    public LogicalPlan visitMetricsCommand(EsqlBaseParser.MetricsCommandContext ctx) {
        if (Build.current().isSnapshot() == false) {
            throw new IllegalArgumentException("METRICS command currently requires a snapshot build");
        }
        Source source = source(ctx);
        TableIdentifier table = new TableIdentifier(source, null, visitIndexPattern(ctx.indexPattern()));

        if (ctx.aggregates == null && ctx.grouping == null) {
            return new EsqlUnresolvedRelation(source, table, List.of(), IndexMode.STANDARD);
        }
        final Stats stats = stats(source, ctx.grouping, ctx.aggregates);
        var relation = new EsqlUnresolvedRelation(
            source,
            table,
            List.of(new MetadataAttribute(source, MetadataAttribute.TSID_FIELD, DataType.KEYWORD, false)),
            IndexMode.TIME_SERIES
        );
        return new EsqlAggregate(source, relation, Aggregate.AggregateType.METRICS, stats.groupings, stats.aggregates);
    }

    @Override
    public PlanFactory visitLookupCommand(EsqlBaseParser.LookupCommandContext ctx) {
        if (false == Build.current().isSnapshot()) {
            throw new ParsingException(source(ctx), "LOOKUP is in preview and only available in SNAPSHOT build");
        }
        var source = source(ctx);

        @SuppressWarnings("unchecked")
        List<Attribute> matchFields = (List<Attribute>) (List) visitQualifiedNamePatterns(ctx.qualifiedNamePatterns(), ne -> {
            if (ne instanceof UnresolvedNamePattern || ne instanceof UnresolvedStar) {
                var src = ne.source();
                throw new ParsingException(src, "Using wildcards [*] in LOOKUP ON is not allowed yet [{}]", src.text());
            }
            if ((ne instanceof UnresolvedAttribute) == false) {
                throw new IllegalStateException(
                    "visitQualifiedNamePatterns can only return UnresolvedNamePattern, UnresolvedStar or UnresolvedAttribute"
                );
            }
        });

        Literal tableName = new Literal(source, visitIndexPattern(List.of(ctx.indexPattern())), DataType.KEYWORD);

        return p -> new Lookup(source, p, tableName, matchFields, null /* localRelation will be resolved later*/);
    }

    interface PlanFactory extends Function<LogicalPlan, LogicalPlan> {}
}
