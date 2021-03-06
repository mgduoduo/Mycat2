/**
 * Copyright (C) <2020>  <chen junwen>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.hbt4.executor;

import com.google.common.collect.ImmutableList;
import io.mycat.hbt4.BaseExecutorImplementor;
import io.mycat.hbt4.Executor;
import io.mycat.mpp.Row;
import lombok.SneakyThrows;
import org.apache.calcite.adapter.enumerable.*;
import org.apache.calcite.adapter.enumerable.impl.AggAddContextImpl;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.config.CalciteSystemProperty;
import org.apache.calcite.interpreter.Context;
import org.apache.calcite.interpreter.JaninoRexCompiler;
import org.apache.calcite.interpreter.Scalar;
import org.apache.calcite.linq4j.*;
import org.apache.calcite.linq4j.function.Function0;
import org.apache.calcite.linq4j.function.Function1;
import org.apache.calcite.linq4j.function.Function2;
import org.apache.calcite.linq4j.tree.*;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.impl.AggregateFunctionImpl;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.apache.calcite.util.BuiltInMethod;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;
import org.codehaus.commons.compiler.CompileException;
import org.codehaus.commons.compiler.CompilerFactoryFactory;
import org.codehaus.commons.compiler.IClassBodyEvaluator;
import org.codehaus.commons.compiler.ICompilerFactory;
import org.objenesis.instantiator.util.UnsafeUtils;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class MycatSortAggExecutor implements Executor {
    private final List<Grouping> groups = new ArrayList<>();
    private final ImmutableBitSet unionGroups;
    private final int outputRowLength;
    private final ImmutableList<AccumulatorFactory> accumulatorFactories;
    private Executor input;
    private final Aggregate rel;
    private Iterator<Row> iter;

    public MycatSortAggExecutor(Executor input, Aggregate rel) {
        this.input = input;
        this.rel = rel;
        ImmutableBitSet union = ImmutableBitSet.of();

        if (rel.getGroupSets() != null) {
            for (ImmutableBitSet group : rel.getGroupSets()) {
                union = union.union(group);
                groups.add(new Grouping(group));
            }
        }

        this.unionGroups = union;
        this.outputRowLength = unionGroups.cardinality()
                + rel.getAggCallList().size();

        ImmutableList.Builder<AccumulatorFactory> builder = ImmutableList.builder();
        for (AggregateCall aggregateCall : rel.getAggCallList()) {
            builder.add(getAccumulator(aggregateCall, false));
        }
        accumulatorFactories = builder.build();
    }


    @Override
    public void open() {
        if (iter == null) {
            input.open();
            Enumerable<Row> outer = Linq4j.asEnumerable(input);
            Row row = null;
            RelCollation collation = rel.getTraitSet().getCollation();
            Comparator<Row> comparator = BaseExecutorImplementor.comparator(collation.getFieldCollations());

            //不支持groupSets
            ImmutableBitSet groupSet = rel.getGroupSet();
            int[] ints = groupSet.toArray();
            Function1<Row, Row> keySelector = a0 -> {
                Row row1 = Row.create(ints.length);
                int index = 0;
                for (int anInt : ints) {
                    row1.values[index]=  a0.getObject(anInt);
                    index++;
                }
                return row1;
            };
            Function0<AccumulatorList> accumulatorInitializer = new Function0<AccumulatorList>() {
                @Override
                public AccumulatorList apply() {
                    AccumulatorList list = new AccumulatorList();
                    for (AccumulatorFactory factory : accumulatorFactories) {
                        list.add(factory.get());
                    }
                    return list;
                }
            };
            Function2<AccumulatorList, Row, AccumulatorList> accumulatorAdder = new Function2<AccumulatorList, Row, AccumulatorList>() {
                @Override
                public AccumulatorList apply(AccumulatorList v0, Row v1) {
                    v0.send(v1);
                    return v0;
                }
            };
            final Function2<Row, AccumulatorList, Row> resultSelector = new Function2<Row, AccumulatorList, Row>() {
                @Override
                public Row apply(Row key, AccumulatorList list) {
                    Row rb = Row.create(outputRowLength);
                    int index = 0;
                    for (Integer groupPos : unionGroups) {
                            if (groupSet.get(groupPos)) {
                                rb.set(index, key.getObject(index));
                            }
                        // need to set false when not part of grouping set.
                        index++;
                    }
                    list.end(rb);
                    return rb;
                }
            };
            iter = new AbstractEnumerable<Row>() {
                public Enumerator<Row> enumerator() {
                    return new SortedAggregateEnumerator(
                            outer, keySelector, accumulatorInitializer,
                            accumulatorAdder, resultSelector, comparator);
                }
            }.iterator();
        }
    }

    @Override
    public Row next() {
        if (iter.hasNext()) {
            return iter.next();
        }
        return null;
    }

    @Override
    public void close() {
        input.close();
    }

    @Override
    public boolean isRewindSupported() {
        return true;
    }

    private AccumulatorFactory getAccumulator(final AggregateCall call,
                                              boolean ignoreFilter) {
        if (call.filterArg >= 0 && !ignoreFilter) {
            final AccumulatorFactory factory = getAccumulator(call, true);
            return () -> {
                final Accumulator accumulator = factory.get();
                return new FilterAccumulator(accumulator, call.filterArg);
            };
        }
        if (call.getAggregation() == SqlStdOperatorTable.COUNT) {
            return () -> new CountAccumulator(call);
        } else if (call.getAggregation() == SqlStdOperatorTable.SUM
                || call.getAggregation() == SqlStdOperatorTable.SUM0) {
            final Class<?> clazz;
            switch (call.type.getSqlTypeName()) {
                case DOUBLE:
                case REAL:
                case FLOAT:
                    clazz = DoubleSum.class;
                    break;
                case DECIMAL:
                    clazz = BigDecimalSum.class;
                    break;
                case INTEGER:
                    clazz = IntSum.class;
                    break;
                case BIGINT:
                default:
                    clazz = LongSum.class;
                    break;
            }
            if (call.getAggregation() == SqlStdOperatorTable.SUM) {
                return new UdaAccumulatorFactory(
                        AggregateFunctionImpl.create(clazz), call, true);
            } else {
                return new UdaAccumulatorFactory(
                        AggregateFunctionImpl.create(clazz), call, false);
            }
        } else if (call.getAggregation() == SqlStdOperatorTable.MIN) {
            final Class<?> clazz;
            switch (call.getType().getSqlTypeName()) {
                case INTEGER:
                    clazz = MinInt.class;
                    break;
                case FLOAT:
                    clazz = MinFloat.class;
                    break;
                case DOUBLE:
                case REAL:
                    clazz = MinDouble.class;
                    break;
                case DECIMAL:
                    clazz = MinBigDecimal.class;
                    break;
                case BOOLEAN:
                    clazz = MinBoolean.class;
                    break;
                default:
                    clazz = MinLong.class;
                    break;
            }
            return new UdaAccumulatorFactory(
                    AggregateFunctionImpl.create(clazz), call, true);
        } else if (call.getAggregation() == SqlStdOperatorTable.MAX) {
            final Class<?> clazz;
            switch (call.getType().getSqlTypeName()) {
                case INTEGER:
                    clazz = MaxInt.class;
                    break;
                case FLOAT:
                    clazz = MaxFloat.class;
                    break;
                case DOUBLE:
                case REAL:
                    clazz = MaxDouble.class;
                    break;
                case DECIMAL:
                    clazz = MaxBigDecimal.class;
                    break;
                default:
                    clazz = MaxLong.class;
                    break;
            }
            return new UdaAccumulatorFactory(
                    AggregateFunctionImpl.create(clazz), call, true);
        } else if (call.getAggregation() == SqlStdOperatorTable.AVG) {
            return () -> new AvgAccumulator(call);
        } else {
            final JavaTypeFactory typeFactory =
                    (JavaTypeFactory) rel.getCluster().getTypeFactory();
            int stateOffset = 0;
            final AggImpState agg = new AggImpState(0, call, false);
            int stateSize = agg.state.size();

            final BlockBuilder builder2 = new BlockBuilder();
            final PhysType inputPhysType =
                    PhysTypeImpl.of(typeFactory, rel.getInput().getRowType(),
                            JavaRowFormat.ARRAY);
            final RelDataTypeFactory.Builder builder = typeFactory.builder();
            for (Expression expression : agg.state) {
                builder.add("a",
                        typeFactory.createJavaType((Class) expression.getType()));
            }
            final PhysType accPhysType =
                    PhysTypeImpl.of(typeFactory, builder.build(), JavaRowFormat.ARRAY);
            final ParameterExpression inParameter =
                    Expressions.parameter(inputPhysType.getJavaRowType(), "in");
            final ParameterExpression acc_ =
                    Expressions.parameter(accPhysType.getJavaRowType(), "acc");

            List<Expression> accumulator = new ArrayList<>(stateSize);
            for (int j = 0; j < stateSize; j++) {
                accumulator.add(accPhysType.fieldReference(acc_, j + stateOffset));
            }
            agg.state = accumulator;

            AggAddContext addContext =
                    new AggAddContextImpl(builder2, accumulator) {
                        public List<RexNode> rexArguments() {
                            List<RexNode> args = new ArrayList<>();
                            for (int index : agg.call.getArgList()) {
                                args.add(RexInputRef.of(index, inputPhysType.getRowType()));
                            }
                            return args;
                        }

                        public RexNode rexFilterArgument() {
                            return agg.call.filterArg < 0
                                    ? null
                                    : RexInputRef.of(agg.call.filterArg,
                                    inputPhysType.getRowType());
                        }

                        public RexToLixTranslator rowTranslator() {
                            final SqlConformance conformance =
                                    SqlConformanceEnum.DEFAULT; // TODO: get this from implementor
                            return RexToLixTranslator.forAggregation(typeFactory,
                                    currentBlock(),
                                    new RexToLixTranslator.InputGetterImpl(
                                            Collections.singletonList(
                                                    Pair.of((Expression) inParameter, inputPhysType))),
                                    conformance)
                                    .setNullable(currentNullables());
                        }
                    };

            agg.implementor.implementAdd(agg.context, addContext);

            final ParameterExpression context_ =
                    Expressions.parameter(Context.class, "context");
            final ParameterExpression outputValues_ =
                    Expressions.parameter(Object[].class, "outputValues");
            Scalar addScalar = baz(context_, outputValues_, builder2.toBlock());
            return new ScalarAccumulatorDef(null, addScalar, null,
                    rel.getInput().getRowType().getFieldCount(), stateSize);
        }
    }

    /**
     * Accumulator for calls to the COUNT function.
     */
    private static class CountAccumulator implements Accumulator {
        private final AggregateCall call;
        long cnt;

        CountAccumulator(AggregateCall call) {
            this.call = call;
            cnt = 0;
        }

        public void send(Row row) {
            boolean notNull = true;
            for (Integer i : call.getArgList()) {
                if (row.getObject(i) == null) {
                    notNull = false;
                    break;
                }
            }
            if (notNull) {
                cnt++;
            }
        }

        public Object end() {
            return cnt;
        }
    }

    /**
     * Accumulator for calls to the COUNT function.
     */
    private static class AvgAccumulator implements Accumulator {
        private final AggregateCall call;
        long cnt;
        double sum;

        AvgAccumulator(AggregateCall call) {
            this.call = call;
            cnt = 0;
            sum = 0;
        }

        public void send(Row row) {
            boolean notNull = true;
            Integer integer = call.getArgList().get(0);
            Number object = (Number) row.getObject(integer);
            if (object == null) {
                notNull = false;
            }
            if (notNull) {
                cnt++;
                sum += object.doubleValue();
            }
        }

        public Object end() {
            return sum / cnt;
        }
    }

    /**
     * Creates an {@link Accumulator}.
     */
    private interface AccumulatorFactory extends Supplier<Accumulator> {
    }

    /**
     * Accumulator powered by {@link Scalar} code fragments.
     */
    private static class ScalarAccumulatorDef implements AccumulatorFactory {
        final Scalar initScalar;
        final Scalar addScalar;
        final Scalar endScalar;
        final Context sendContext;
        final Context endContext;
        final int rowLength;
        final int accumulatorLength;

        @SneakyThrows
        private ScalarAccumulatorDef(Scalar initScalar, Scalar addScalar,
                                     Scalar endScalar, int rowLength, int accumulatorLength) {
            this.initScalar = initScalar;
            this.addScalar = addScalar;
            this.endScalar = endScalar;
            this.accumulatorLength = accumulatorLength;
            this.rowLength = rowLength;
            this.sendContext = (Context) UnsafeUtils.getUnsafe().allocateInstance(Context.class);
            this.sendContext.values = new Object[rowLength + accumulatorLength];
            this.endContext = (Context) UnsafeUtils.getUnsafe().allocateInstance(Context.class);
            this.endContext.values = new Object[accumulatorLength];
        }

        public Accumulator get() {
            return new ScalarAccumulator(this, new Object[accumulatorLength]);
        }
    }

    /**
     * Accumulator powered by {@link Scalar} code fragments.
     */
    private static class ScalarAccumulator implements Accumulator {
        final ScalarAccumulatorDef def;
        final Object[] values;

        private ScalarAccumulator(ScalarAccumulatorDef def, Object[] values) {
            this.def = def;
            this.values = values;
        }

        public void send(Row row) {
            System.arraycopy(row.getValues(), 0, def.sendContext.values, 0,
                    def.rowLength);
            System.arraycopy(values, 0, def.sendContext.values, def.rowLength,
                    values.length);
            def.addScalar.execute(def.sendContext, values);
        }

        public Object end() {
            System.arraycopy(values, 0, def.endContext.values, 0, values.length);
            return def.endScalar.execute(def.endContext);
        }
    }

    /**
     * Internal class to track groupings.
     */
    private class Grouping {
        private final ImmutableBitSet grouping;
        private final Map<Row, AccumulatorList> accumulators = new HashMap<>();

        private Grouping(ImmutableBitSet grouping) {
            this.grouping = grouping;
        }

        public void send(Row row) {
            // TODO: fix the size of this row.
            Row builder = Row.create(grouping.cardinality());
            int j = 0;
            for (Integer i : grouping) {
                builder.set(j++, row.getObject(i));
            }
            Row key = builder;

            if (!accumulators.containsKey(key)) {
                AccumulatorList list = new AccumulatorList();
                for (AccumulatorFactory factory : accumulatorFactories) {
                    list.add(factory.get());
                }
                accumulators.put(key, list);
            }

            accumulators.get(key).send(row);
        }

        public Stream<Row> end() {
            return accumulators.entrySet().stream().map(e -> {
                final Row key = e.getKey();
                final AccumulatorList list = e.getValue();
                Row rb = Row.create(outputRowLength);
                int index = 0;
                for (Integer groupPos : unionGroups) {
                    if (grouping.get(groupPos)) {
                        rb.set(index, key.getObject(index));
                    }
                    // need to set false when not part of grouping set.
                    index++;
                }
                list.end(rb);
                return rb;
            });
        }
    }

    /**
     * A list of accumulators used during grouping.
     */
    private static class AccumulatorList extends ArrayList<Accumulator> {
        public void send(Row row) {
            for (Accumulator a : this) {
                a.send(row);
            }
        }

        public void end(Row r) {
            for (int accIndex = 0, rowIndex = r.size() - size();
                 rowIndex < r.size(); rowIndex++, accIndex++) {
                r.set(rowIndex, get(accIndex).end());
            }
        }
    }

    /**
     * Defines function implementation for
     * things like {@code count()} and {@code sum()}.
     */
    private interface Accumulator {
        void send(Row row);

        Object end();
    }

    /**
     * Implementation of {@code SUM} over INTEGER values as a user-defined
     * aggregate.
     */
    public static class IntSum {
        public IntSum() {
        }

        public int init() {
            return 0;
        }

        public int add(int accumulator, int v) {
            return accumulator + v;
        }

        public int merge(int accumulator0, int accumulator1) {
            return accumulator0 + accumulator1;
        }

        public int result(int accumulator) {
            return accumulator;
        }
    }

    /**
     * Implementation of {@code SUM} over BIGINT values as a user-defined
     * aggregate.
     */
    public static class LongSum {
        public LongSum() {
        }

        public long init() {
            return 0L;
        }

        public long add(long accumulator, long v) {
            return accumulator + v;
        }

        public long merge(long accumulator0, long accumulator1) {
            return accumulator0 + accumulator1;
        }

        public long result(long accumulator) {
            return accumulator;
        }
    }

    /**
     * Implementation of {@code SUM} over DOUBLE values as a user-defined
     * aggregate.
     */
    public static class DoubleSum {
        public DoubleSum() {
        }

        public double init() {
            return 0D;
        }

        public double add(double accumulator, double v) {
            return accumulator + v;
        }

        public double merge(double accumulator0, double accumulator1) {
            return accumulator0 + accumulator1;
        }

        public double result(double accumulator) {
            return accumulator;
        }
    }

    /**
     * Implementation of {@code SUM} over BigDecimal values as a user-defined
     * aggregate.
     */
    public static class BigDecimalSum {
        public BigDecimalSum() {
        }

        public BigDecimal init() {
            return new BigDecimal("0");
        }

        public BigDecimal add(BigDecimal accumulator, BigDecimal v) {
            return accumulator.add(v);
        }

        public BigDecimal merge(BigDecimal accumulator0, BigDecimal accumulator01) {
            return add(accumulator0, accumulator01);
        }

        public BigDecimal result(BigDecimal accumulator) {
            return accumulator;
        }
    }

    /**
     * Common implementation of comparison aggregate methods over numeric
     * values as a user-defined aggregate.
     *
     * @param <T> The numeric type
     */
    public static class NumericComparison<T> {
        private final T initialValue;
        private final BiFunction<T, T, T> comparisonFunction;

        public NumericComparison(T initialValue, BiFunction<T, T, T> comparisonFunction) {
            this.initialValue = initialValue;
            this.comparisonFunction = comparisonFunction;
        }

        public T init() {
            return this.initialValue;
        }

        public T add(T accumulator, T value) {
            return this.comparisonFunction.apply(accumulator, value);
        }

        public T merge(T accumulator0, T accumulator1) {
            return add(accumulator0, accumulator1);
        }

        public T result(T accumulator) {
            return accumulator;
        }
    }

    /**
     * Implementation of {@code MIN} function to calculate the minimum of
     * {@code integer} values as a user-defined aggregate.
     */
    public static class MinInt extends NumericComparison<Integer> {
        public MinInt() {
            super(Integer.MAX_VALUE, Math::min);
        }
    }

    /**
     * Implementation of {@code MIN} function to calculate the minimum of
     * {@code long} values as a user-defined aggregate.
     */
    public static class MinLong extends NumericComparison<Long> {
        public MinLong() {
            super(Long.MAX_VALUE, Math::min);
        }
    }

    /**
     * Implementation of {@code MIN} function to calculate the minimum of
     * {@code float} values as a user-defined aggregate.
     */
    public static class MinFloat extends NumericComparison<Float> {
        public MinFloat() {
            super(Float.MAX_VALUE, Math::min);
        }
    }

    /**
     * Implementation of {@code MIN} function to calculate the minimum of
     * {@code double} and {@code real} values as a user-defined aggregate.
     */
    public static class MinDouble extends NumericComparison<Double> {
        public MinDouble() {
            super(Double.MAX_VALUE, Math::min);
        }
    }

    /**
     * Implementation of {@code MIN} function to calculate the minimum of
     * {@code BigDecimal} values as a user-defined aggregate.
     */
    public static class MinBigDecimal extends NumericComparison<BigDecimal> {
        public MinBigDecimal() {
            super(new BigDecimal(Double.MAX_VALUE), MinBigDecimal::min);
        }

        public static BigDecimal min(BigDecimal a, BigDecimal b) {
            return a.min(b);
        }
    }

    /**
     * Implementation of {@code MIN} function to calculate the minimum of
     * {@code boolean} values as a user-defined aggregate.
     */
    public static class MinBoolean {
        public MinBoolean() {
        }

        public Boolean init() {
            return Boolean.TRUE;
        }

        public Boolean add(Boolean accumulator, Boolean value) {
            return accumulator.compareTo(value) < 0 ? accumulator : value;
        }

        public Boolean merge(Boolean accumulator0, Boolean accumulator1) {
            return add(accumulator0, accumulator1);
        }

        public Boolean result(Boolean accumulator) {
            return accumulator;
        }
    }

    /**
     * Implementation of {@code MAX} function to calculate the maximum of
     * {@code integer} values as a user-defined aggregate.
     */
    public static class MaxInt extends NumericComparison<Integer> {
        public MaxInt() {
            super(Integer.MIN_VALUE, Math::max);
        }
    }

    /**
     * Implementation of {@code MAX} function to calculate the maximum of
     * {@code long} values as a user-defined aggregate.
     */
    public static class MaxLong extends NumericComparison<Long> {
        public MaxLong() {
            super(Long.MIN_VALUE, Math::max);
        }
    }

    /**
     * Implementation of {@code MAX} function to calculate the maximum of
     * {@code float} values as a user-defined aggregate.
     */
    public static class MaxFloat extends NumericComparison<Float> {
        public MaxFloat() {
            super(Float.MIN_VALUE, Math::max);
        }
    }

    /**
     * Implementation of {@code MAX} function to calculate the maximum of
     * {@code double} and {@code real} values as a user-defined aggregate.
     */
    public static class MaxDouble extends NumericComparison<Double> {
        public MaxDouble() {
            super(Double.MIN_VALUE, Math::max);
        }
    }

    /**
     * Implementation of {@code MAX} function to calculate the maximum of
     * {@code BigDecimal} values as a user-defined aggregate.
     */
    public static class MaxBigDecimal extends NumericComparison<BigDecimal> {
        public MaxBigDecimal() {
            super(new BigDecimal(Double.MIN_VALUE), MaxBigDecimal::max);
        }

        public static BigDecimal max(BigDecimal a, BigDecimal b) {
            return a.max(b);
        }
    }

    /**
     * Accumulator factory based on a user-defined aggregate function.
     */
    private static class UdaAccumulatorFactory implements AccumulatorFactory {
        final AggregateFunctionImpl aggFunction;
        final int argOrdinal;
        public final Object instance;
        public final boolean nullIfEmpty;

        UdaAccumulatorFactory(AggregateFunctionImpl aggFunction,
                              AggregateCall call, boolean nullIfEmpty) {
            this.aggFunction = aggFunction;
            if (call.getArgList().size() != 1) {
                throw new UnsupportedOperationException("in current implementation, "
                        + "aggregate must have precisely one argument");
            }
            argOrdinal = call.getArgList().get(0);
            if (aggFunction.isStatic) {
                instance = null;
            } else {
                try {
                    final Constructor<?> constructor =
                            aggFunction.declaringClass.getConstructor();
                    instance = constructor.newInstance();
                } catch (InstantiationException | IllegalAccessException
                        | NoSuchMethodException | InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
            }
            this.nullIfEmpty = nullIfEmpty;
        }

        public Accumulator get() {
            return new UdaAccumulator(this);
        }
    }

    /**
     * Accumulator based upon a user-defined aggregate.
     */
    private static class UdaAccumulator implements Accumulator {
        private final UdaAccumulatorFactory factory;
        private Object value;
        private boolean empty;

        UdaAccumulator(UdaAccumulatorFactory factory) {
            this.factory = factory;
            try {
                this.value = factory.aggFunction.initMethod.invoke(factory.instance);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
            this.empty = true;
        }

        public void send(Row row) {
            final Object[] args = {value, row.getValues()[factory.argOrdinal]};
            for (int i = 1; i < args.length; i++) {
                if (args[i] == null) {
                    return; // one of the arguments is null; don't add to the total
                }
            }
            try {
                value = factory.aggFunction.addMethod.invoke(factory.instance, args);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
            empty = false;
        }

        public Object end() {
            if (factory.nullIfEmpty && empty) {
                return null;
            }
            final Object[] args = {value};
            try {
                return factory.aggFunction.resultMethod.invoke(factory.instance, args);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Accumulator that applies a filter to another accumulator.
     * The filter is a BOOLEAN field in the input row.
     */
    private static class FilterAccumulator implements Accumulator {
        private final Accumulator accumulator;
        private final int filterArg;

        FilterAccumulator(Accumulator accumulator, int filterArg) {
            this.accumulator = accumulator;
            this.filterArg = filterArg;
        }

        public void send(Row row) {
            if (row.getValues()[filterArg] == Boolean.TRUE) {
                accumulator.send(row);
            }
        }

        public Object end() {
            return accumulator.end();
        }
    }

    /**
     * Given a method that implements {@link Scalar#execute(Context, Object[])},
     * adds a bridge method that implements {@link Scalar#execute(Context)}, and
     * compiles.
     */
    static Scalar baz(ParameterExpression context_,
                      ParameterExpression outputValues_, BlockStatement block) {
        final List<MemberDeclaration> declarations = new ArrayList<>();

        // public void execute(Context, Object[] outputValues)
        declarations.add(
                Expressions.methodDecl(Modifier.PUBLIC, void.class,
                        BuiltInMethod.SCALAR_EXECUTE2.method.getName(),
                        ImmutableList.of(context_, outputValues_), block));

        // public Object execute(Context)
        final BlockBuilder builder = new BlockBuilder();
        final Expression values_ = builder.append("values",
                Expressions.newArrayBounds(Object.class, 1,
                        Expressions.constant(1)));
        builder.add(
                Expressions.statement(
                        Expressions.call(
                                Expressions.parameter(Scalar.class, "this"),
                                BuiltInMethod.SCALAR_EXECUTE2.method, context_, values_)));
        builder.add(
                Expressions.return_(null,
                        Expressions.arrayIndex(values_, Expressions.constant(0))));
        declarations.add(
                Expressions.methodDecl(Modifier.PUBLIC, Object.class,
                        BuiltInMethod.SCALAR_EXECUTE1.method.getName(),
                        ImmutableList.of(context_), builder.toBlock()));

        final ClassDeclaration classDeclaration =
                Expressions.classDecl(Modifier.PUBLIC, "Buzz", null,
                        ImmutableList.of(Scalar.class), declarations);
        String s = Expressions.toString(declarations, "\n", false);
        if (CalciteSystemProperty.DEBUG.value()) {
            Util.debugCode(System.out, s);
        }
        try {
            return getScalar(classDeclaration, s);
        } catch (CompileException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    static Scalar getScalar(ClassDeclaration expr, String s)
            throws CompileException, IOException {
        ICompilerFactory compilerFactory;
        try {
            compilerFactory = CompilerFactoryFactory.getDefaultCompilerFactory();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to instantiate java compiler", e);
        }
        IClassBodyEvaluator cbe = compilerFactory.newClassBodyEvaluator();
        cbe.setClassName(expr.name);
        cbe.setImplementedInterfaces(new Class[]{Scalar.class});
        cbe.setParentClassLoader(JaninoRexCompiler.class.getClassLoader());
        if (CalciteSystemProperty.DEBUG.value()) {
            // Add line numbers to the generated janino class
            cbe.setDebuggingInformation(true, true, true);
        }
        return (Scalar) cbe.createInstance(new StringReader(s));
    }
}