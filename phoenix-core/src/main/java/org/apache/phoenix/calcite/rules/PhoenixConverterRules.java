package org.apache.phoenix.calcite.rules;

import java.util.Arrays;
import java.util.logging.Logger;

import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelTrait;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Aggregate.Group;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.Union;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.logical.LogicalSort;
import org.apache.calcite.rel.logical.LogicalUnion;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.trace.CalciteTrace;
import org.apache.phoenix.calcite.CalciteUtils;
import org.apache.phoenix.calcite.rel.PhoenixAbstractAggregate;
import org.apache.phoenix.calcite.rel.PhoenixClientAggregate;
import org.apache.phoenix.calcite.rel.PhoenixClientProject;
import org.apache.phoenix.calcite.rel.PhoenixClientSort;
import org.apache.phoenix.calcite.rel.PhoenixFilter;
import org.apache.phoenix.calcite.rel.PhoenixJoin;
import org.apache.phoenix.calcite.rel.PhoenixLimit;
import org.apache.phoenix.calcite.rel.PhoenixRel;
import org.apache.phoenix.calcite.rel.PhoenixToEnumerableConverter;
import org.apache.phoenix.calcite.rel.PhoenixUnion;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;

/**
 * Rules and relational operators for
 * {@link PhoenixRel#CONVENTION PHOENIX}
 * calling convention.
 */
public class PhoenixConverterRules {
    private PhoenixConverterRules() {}

    protected static final Logger LOGGER = CalciteTrace.getPlannerTracer();

    public static final RelOptRule[] RULES = {
        PhoenixToEnumerableConverterRule.INSTANCE,
        PhoenixSortRule.INSTANCE,
        PhoenixLimitRule.INSTANCE,
        PhoenixFilterRule.INSTANCE,
        PhoenixProjectRule.INSTANCE,
        PhoenixAggregateRule.INSTANCE,
        PhoenixUnionRule.INSTANCE,
        PhoenixJoinRule.INSTANCE,
    };

    /** Base class for planner rules that convert a relational expression to
     * Phoenix calling convention. */
    abstract static class PhoenixConverterRule extends ConverterRule {
        protected final Convention out;
        public PhoenixConverterRule(
                Class<? extends RelNode> clazz,
                RelTrait in,
                Convention out,
                String description) {
            super(clazz, in, out, description);
            this.out = out;
        }
        
        public <R extends RelNode> PhoenixConverterRule(
                Class<R> clazz,
                Predicate<? super R> predicate,
                RelTrait in,
                Convention out,
                String description) {
            super(clazz, predicate, in, out, description);
            this.out = out;
        }
    }

    /**
     * Rule to convert a {@link org.apache.calcite.rel.core.Sort} to a
     * {@link PhoenixClientSort}.
     */
    private static class PhoenixSortRule extends PhoenixConverterRule {
        private static Predicate<LogicalSort> IS_CONVERTIBLE = new Predicate<LogicalSort>() {
            @Override
            public boolean apply(LogicalSort input) {
                return isConvertible(input);
            }            
        };
        private static Predicate<LogicalSort> SORT_ONLY = new Predicate<LogicalSort>() {
            @Override
            public boolean apply(LogicalSort input) {
                return !input.getCollation().getFieldCollations().isEmpty()
                        && input.offset == null
                        && input.fetch == null;
            }            
        };
        
        public static final PhoenixSortRule INSTANCE = new PhoenixSortRule();

        private PhoenixSortRule() {
            super(LogicalSort.class, 
                    Predicates.and(Arrays.asList(SORT_ONLY, IS_CONVERTIBLE)), 
                    Convention.NONE, PhoenixRel.CONVENTION, "PhoenixSortRule");
        }

        public RelNode convert(RelNode rel) {
            final LogicalSort sort = (LogicalSort) rel;
            return PhoenixClientSort.create(
                convert(
                        sort.getInput(), 
                        sort.getInput().getTraitSet().replace(out)),
                sort.getCollation());
        }
    }

    /**
     * Rule to convert a {@link org.apache.calcite.rel.core.Sort} to a
     * {@link PhoenixLimit}.
     */
    private static class PhoenixLimitRule extends PhoenixConverterRule {
        private static Predicate<LogicalSort> IS_CONVERTIBLE = new Predicate<LogicalSort>() {
            @Override
            public boolean apply(LogicalSort input) {
                return isConvertible(input);
            }            
        };
        private static Predicate<LogicalSort> OFFSET_OR_FETCH = new Predicate<LogicalSort>() {
            @Override
            public boolean apply(LogicalSort input) {
                return input.offset != null 
                        || input.fetch != null;
            }            
        };
        
        public static final PhoenixLimitRule INSTANCE = new PhoenixLimitRule();

        private PhoenixLimitRule() {
            super(LogicalSort.class, 
                    Predicates.and(Arrays.asList(OFFSET_OR_FETCH, IS_CONVERTIBLE)), 
                    Convention.NONE, PhoenixRel.CONVENTION, "PhoenixLimitRule");
        }

        public RelNode convert(RelNode rel) {
            final LogicalSort sort = (LogicalSort) rel;
            RelNode input = convert(
                    sort.getInput(), 
                    sort.getInput().getTraitSet().replace(out));
            if (!sort.getCollation().getFieldCollations().isEmpty()) {
                input = PhoenixClientSort.create(input, sort.getCollation());
            }
            return PhoenixLimit.create(
                input,
                sort.offset, 
                sort.fetch);
        }
    }

    /**
     * Rule to convert a {@link org.apache.calcite.rel.logical.LogicalFilter} to a
     * {@link PhoenixFilter}.
     */
    private static class PhoenixFilterRule extends PhoenixConverterRule {
        private static Predicate<LogicalFilter> IS_CONVERTIBLE = new Predicate<LogicalFilter>() {
            @Override
            public boolean apply(LogicalFilter input) {
                return isConvertible(input);
            }            
        };
        
        private static final PhoenixFilterRule INSTANCE = new PhoenixFilterRule();

        private PhoenixFilterRule() {
            super(LogicalFilter.class, IS_CONVERTIBLE, Convention.NONE, 
                    PhoenixRel.CONVENTION, "PhoenixFilterRule");
        }

        public RelNode convert(RelNode rel) {
            final LogicalFilter filter = (LogicalFilter) rel;
            return PhoenixFilter.create(
                convert(
                        filter.getInput(), 
                        filter.getInput().getTraitSet().replace(out)),
                filter.getCondition());
        }
    }

    /**
     * Rule to convert a {@link org.apache.calcite.rel.logical.LogicalProject}
     * to a {@link PhoenixClientProject}.
     */
    private static class PhoenixProjectRule extends PhoenixConverterRule {
        private static Predicate<LogicalProject> IS_CONVERTIBLE = new Predicate<LogicalProject>() {
            @Override
            public boolean apply(LogicalProject input) {
                return isConvertible(input);
            }            
        };
        
        private static final PhoenixProjectRule INSTANCE = new PhoenixProjectRule();

        private PhoenixProjectRule() {
            super(LogicalProject.class, IS_CONVERTIBLE, Convention.NONE, 
                    PhoenixRel.CONVENTION, "PhoenixProjectRule");
        }

        public RelNode convert(RelNode rel) {
            final LogicalProject project = (LogicalProject) rel;
            return PhoenixClientProject.create(
                convert(
                        project.getInput(), 
                        project.getInput().getTraitSet().replace(out)), 
                project.getProjects(),
                project.getRowType());
        }
    }

    /**
     * Rule to convert a {@link org.apache.calcite.rel.logical.LogicalAggregate}
     * to an {@link PhoenixClientAggregate}.
     */
    private static class PhoenixAggregateRule extends PhoenixConverterRule {
        private static Predicate<LogicalAggregate> IS_CONVERTIBLE = new Predicate<LogicalAggregate>() {
            @Override
            public boolean apply(LogicalAggregate input) {
                return isConvertible(input);
            }            
        };
        
        public static final RelOptRule INSTANCE = new PhoenixAggregateRule();

        private PhoenixAggregateRule() {
            super(LogicalAggregate.class, IS_CONVERTIBLE, Convention.NONE, 
                    PhoenixRel.CONVENTION, "PhoenixAggregateRule");
        }

        public RelNode convert(RelNode rel) {
            final LogicalAggregate agg = (LogicalAggregate) rel;
            return PhoenixClientAggregate.create(
                    convert(
                            agg.getInput(), 
                            agg.getInput().getTraitSet().replace(out)),
                    agg.indicator,
                    agg.getGroupSet(),
                    agg.getGroupSets(),
                    agg.getAggCallList());
        }
    }

    /**
     * Rule to convert a {@link org.apache.calcite.rel.core.Union} to a
     * {@link PhoenixUnion}.
     */
    private static class PhoenixUnionRule extends PhoenixConverterRule {
        private static Predicate<LogicalUnion> IS_CONVERTIBLE = new Predicate<LogicalUnion>() {
            @Override
            public boolean apply(LogicalUnion input) {
                return isConvertible(input);
            }            
        };
        
        public static final PhoenixUnionRule INSTANCE = new PhoenixUnionRule();

        private PhoenixUnionRule() {
            super(LogicalUnion.class, IS_CONVERTIBLE, Convention.NONE, 
                    PhoenixRel.CONVENTION, "PhoenixUnionRule");
        }

        public RelNode convert(RelNode rel) {
            final LogicalUnion union = (LogicalUnion) rel;
            return PhoenixUnion.create(
                    convertList(union.getInputs(), out),
                    union.all);
        }
    }

    /**
     * Rule to convert a {@link org.apache.calcite.rel.core.Join} to a
     * {@link PhoenixJoin}.
     */
    private static class PhoenixJoinRule extends PhoenixConverterRule {
        private static Predicate<LogicalJoin> IS_CONVERTIBLE = new Predicate<LogicalJoin>() {
            @Override
            public boolean apply(LogicalJoin input) {
                return isConvertible(input);
            }            
        };
        public static final PhoenixJoinRule INSTANCE = new PhoenixJoinRule();

        private PhoenixJoinRule() {
            super(LogicalJoin.class, IS_CONVERTIBLE, Convention.NONE, 
                    PhoenixRel.CONVENTION, "PhoenixJoinRule");
        }

        public RelNode convert(RelNode rel) {
            final LogicalJoin join = (LogicalJoin) rel;
            return PhoenixJoin.create(
                    convert(
                            join.getLeft(), 
                            join.getLeft().getTraitSet().replace(out)),
                    convert(
                            join.getRight(), 
                            join.getRight().getTraitSet().replace(out)),
                    join.getCondition(),
                    join.getJoinType(),
                    join.getVariablesStopped());
        }
    }

    /**
     * Rule to convert an {@link org.apache.calcite.rel.logical.LogicalIntersect}
     * to an {@link PhoenixIntersectRel}.
     o/
     private static class PhoenixIntersectRule
     extends PhoenixConverterRule {
     private PhoenixIntersectRule(PhoenixConvention out) {
     super(
     LogicalIntersect.class,
     Convention.NONE,
     out,
     "PhoenixIntersectRule");
     }

     public RelNode convert(RelNode rel) {
     final LogicalIntersect intersect = (LogicalIntersect) rel;
     if (intersect.all) {
     return null; // INTERSECT ALL not implemented
     }
     final RelTraitSet traitSet =
     intersect.getTraitSet().replace(out);
     return new PhoenixIntersectRel(
     rel.getCluster(),
     traitSet,
     convertList(intersect.getInputs(), traitSet),
     intersect.all);
     }
     }

     public static class PhoenixIntersectRel
     extends Intersect
     implements PhoenixRel {
     public PhoenixIntersectRel(
     RelOptCluster cluster,
     RelTraitSet traitSet,
     List<RelNode> inputs,
     boolean all) {
     super(cluster, traitSet, inputs, all);
     assert !all;
     }

     public PhoenixIntersectRel copy(
     RelTraitSet traitSet, List<RelNode> inputs, boolean all) {
     return new PhoenixIntersectRel(getCluster(), traitSet, inputs, all);
     }

     public SqlString implement(PhoenixImplementor implementor) {
     return setOpSql(this, implementor, " intersect ");
     }
     }

     /**
     * Rule to convert an {@link org.apache.calcite.rel.logical.LogicalMinus}
     * to an {@link PhoenixMinusRel}.
     o/
     private static class PhoenixMinusRule
     extends PhoenixConverterRule {
     private PhoenixMinusRule(PhoenixConvention out) {
     super(
     LogicalMinus.class,
     Convention.NONE,
     out,
     "PhoenixMinusRule");
     }

     public RelNode convert(RelNode rel) {
     final LogicalMinus minus = (LogicalMinus) rel;
     if (minus.all) {
     return null; // EXCEPT ALL not implemented
     }
     final RelTraitSet traitSet =
     rel.getTraitSet().replace(out);
     return new PhoenixMinusRel(
     rel.getCluster(),
     traitSet,
     convertList(minus.getInputs(), traitSet),
     minus.all);
     }
     }

     public static class PhoenixMinusRel
     extends Minus
     implements PhoenixRel {
     public PhoenixMinusRel(
     RelOptCluster cluster,
     RelTraitSet traitSet,
     List<RelNode> inputs,
     boolean all) {
     super(cluster, traitSet, inputs, all);
     assert !all;
     }

     public PhoenixMinusRel copy(
     RelTraitSet traitSet, List<RelNode> inputs, boolean all) {
     return new PhoenixMinusRel(getCluster(), traitSet, inputs, all);
     }

     public SqlString implement(PhoenixImplementor implementor) {
     return setOpSql(this, implementor, " minus ");
     }
     }
     */

  /*
  public static class PhoenixValuesRule extends PhoenixConverterRule {
    private PhoenixValuesRule() {
      super(Values.class, Convention.NONE, PhoenixRel.CONVENTION, "PhoenixValuesRule");
    }

    @Override public RelNode convert(RelNode rel) {
      Values valuesRel = (Values) rel;
      return new PhoenixValuesRel(
          valuesRel.getCluster(),
          valuesRel.getRowType(),
          valuesRel.getTuples(),
          valuesRel.getTraitSet().plus(out));
    }
  }

  public static class PhoenixValuesRel
      extends Values
      implements PhoenixRel {
    PhoenixValuesRel(
        RelOptCluster cluster,
        RelDataType rowType,
        List<List<RexLiteral>> tuples,
        RelTraitSet traitSet) {
      super(cluster, rowType, tuples, traitSet);
    }

    @Override public RelNode copy(
        RelTraitSet traitSet, List<RelNode> inputs) {
      assert inputs.isEmpty();
      return new PhoenixValuesRel(
          getCluster(), rowType, tuples, traitSet);
    }

    public SqlString implement(PhoenixImplementor implementor) {
      throw new AssertionError(); // TODO:
    }
  }
*/

    /**
     * Rule to convert a relational expression from
     * {@link org.apache.phoenix.calcite.rel.PhoenixRel#CONVENTION} to
     * {@link org.apache.calcite.adapter.enumerable.EnumerableConvention}.
     */
    public static class PhoenixToEnumerableConverterRule extends ConverterRule {
        public static final ConverterRule INSTANCE =
            new PhoenixToEnumerableConverterRule();

        private PhoenixToEnumerableConverterRule() {
            super(RelNode.class, PhoenixRel.CONVENTION, EnumerableConvention.INSTANCE,
                "PhoenixToEnumerableConverterRule");
        }

        @Override public RelNode convert(RelNode rel) {
            return PhoenixToEnumerableConverter.create(rel);
        }
    }
    
    
    //-------------------------------------------------------------------
    // Helper functions that check if a RelNode would be implementable by
    // its corresponding PhoenixRel.
    
    public static boolean isConvertible(Aggregate input) {
        if (PhoenixAbstractAggregate.isSingleValueCheckAggregate(input))
            return true;
        
        if (input.getGroupSets().size() > 1)
            return false;
        
        if (input.containsDistinctCall())
            return false;
        
        if (input.getGroupType() != Group.SIMPLE)
            return false;
        
        for (AggregateCall aggCall : input.getAggCallList()) {
            if (!CalciteUtils.isAggregateFunctionSupported(aggCall.getAggregation())) {
                return false;
            }
        }        
        
        return true;
    }
    
    public static boolean isConvertible(Filter input) {
        return CalciteUtils.isExpressionSupported(input.getCondition());
    }
    
    public static boolean isConvertible(Join input) {
        return CalciteUtils.isExpressionSupported(input.getCondition());
    }
    
    public static boolean isConvertible(Project input) {
        for (RexNode project : input.getProjects()) {
            if (!CalciteUtils.isExpressionSupported(project)) {
                return false;
            }
        }
        
        return true;
    }
    
    public static boolean isConvertible(Sort sort) {
        if (sort.offset != null)
            return false;
        
        if (sort.fetch != null 
                && CalciteUtils.evaluateStatelessExpression(sort.fetch) == null)
            return false;
        
        return true;
    }
    
    public static boolean isConvertible(Union input) {
        // TODO disable for now since PhoenixUnion is not implemented yet.
        return false;
    }
}

// End PhoenixRules.java
