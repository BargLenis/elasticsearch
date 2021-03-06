/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.search.aggregations.bucket.terms;

import org.elasticsearch.Version;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.util.Comparators;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.bucket.BucketsAggregator;
import org.elasticsearch.search.aggregations.bucket.MultiBucketsAggregation;
import org.elasticsearch.search.aggregations.bucket.SingleBucketAggregator;
import org.elasticsearch.search.aggregations.bucket.terms.Terms.Bucket;
import org.elasticsearch.search.aggregations.metrics.NumericMetricsAggregator;
import org.elasticsearch.search.aggregations.support.OrderPath;

import java.io.IOException;
import java.util.*;

/**
 *
 */
class InternalOrder extends Terms.Order {

    private static final byte COUNT_DESC_ID = 1;
    private static final byte COUNT_ASC_ID = 2;
    private static final byte TERM_DESC_ID = 3;
    private static final byte TERM_ASC_ID = 4;

    /**
     * Order by the (higher) count of each term.
     */
    public static final InternalOrder COUNT_DESC = new InternalOrder(COUNT_DESC_ID, "_count", false, new Comparator<Terms.Bucket>() {
        @Override
        public int compare(Terms.Bucket o1, Terms.Bucket o2) {
            return  Long.compare(o2.getDocCount(), o1.getDocCount());
        }
    });

    /**
     * Order by the (lower) count of each term.
     */
    public static final InternalOrder COUNT_ASC = new InternalOrder(COUNT_ASC_ID, "_count", true, new Comparator<Terms.Bucket>() {

        @Override
        public int compare(Terms.Bucket o1, Terms.Bucket o2) {
            return Long.compare(o1.getDocCount(), o2.getDocCount());
        }
    });

    /**
     * Order by the terms.
     */
    public static final InternalOrder TERM_DESC = new InternalOrder(TERM_DESC_ID, "_term", false, new Comparator<Terms.Bucket>() {

        @Override
        public int compare(Terms.Bucket o1, Terms.Bucket o2) {
            return o2.compareTerm(o1);
        }
    });

    /**
     * Order by the terms.
     */
    public static final InternalOrder TERM_ASC = new InternalOrder(TERM_ASC_ID, "_term", true, new Comparator<Terms.Bucket>() {

        @Override
        public int compare(Terms.Bucket o1, Terms.Bucket o2) {
            return o1.compareTerm(o2);
        }
    });

    public static boolean isCountDesc(Terms.Order order) {
        if (order == COUNT_DESC) {
            return true;
        }else if (order instanceof CompoundOrder) {
            // check if its a compound order with count desc and the tie breaker (term asc)
            CompoundOrder compoundOrder = (CompoundOrder) order;
            if (compoundOrder.compoundOrder.size() == 2 && compoundOrder.compoundOrder.get(0) == COUNT_DESC && compoundOrder.compoundOrder.get(1) == TERM_ASC) {
                return true;
            }
        }
        return false;
    }

    final byte id;
    final String key;
    final boolean asc;
    protected final Comparator<Terms.Bucket> comparator;

    InternalOrder(byte id, String key, boolean asc, Comparator<Terms.Bucket> comparator) {
        this.id = id;
        this.key = key;
        this.asc = asc;
        this.comparator = comparator;
    }

    byte id() {
        return id;
    }

    @Override
    protected Comparator<Terms.Bucket> comparator(Aggregator aggregator) {
        return comparator;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        return builder.startObject().field(key, asc ? "asc" : "desc").endObject();
    }

    public static Terms.Order validate(Terms.Order order, Aggregator termsAggregator) {
        if (order instanceof CompoundOrder) {
            for (Terms.Order innerOrder : ((CompoundOrder)order).compoundOrder) {
                validate(innerOrder, termsAggregator);
            }
            return order;
        } else if (!(order instanceof Aggregation)) {
            return order;
        }
        OrderPath path = ((Aggregation) order).path();
        path.validate(termsAggregator);
        return order;
    }

    static class Aggregation extends InternalOrder {

        static final byte ID = 0;

        Aggregation(String key, boolean asc) {
            super(ID, key, asc, new MultiBucketsAggregation.Bucket.SubAggregationComparator<Terms.Bucket>(key, asc));
        }

        OrderPath path() {
            return ((MultiBucketsAggregation.Bucket.SubAggregationComparator) comparator).path();
        }

        @Override
        protected Comparator<Terms.Bucket> comparator(Aggregator termsAggregator) {
            if (termsAggregator == null) {
                return comparator;
            }

            // Internal Optimization:
            //
            // in this phase, if the order is based on sub-aggregations, we need to use a different comparator
            // to avoid constructing buckets for ordering purposes (we can potentially have a lot of buckets and building
            // them will cause loads of redundant object constructions). The "special" comparators here will fetch the
            // sub aggregation values directly from the sub aggregators bypassing bucket creation. Note that the comparator
            // attached to the order will still be used in the reduce phase of the Aggregation.

            OrderPath path = path();
            final Aggregator aggregator = path.resolveAggregator(termsAggregator);
            final String key = path.tokens[path.tokens.length - 1].key;

            if (aggregator instanceof SingleBucketAggregator) {
                assert key == null : "this should be picked up before the aggregation is executed - on validate";
                return new Comparator<Terms.Bucket>() {
                    @Override
                    public int compare(Terms.Bucket o1, Terms.Bucket o2) {
                        int mul = asc ? 1 : -1;
                        int v1 = ((SingleBucketAggregator) aggregator).bucketDocCount(((InternalTerms.Bucket) o1).bucketOrd);
                        int v2 = ((SingleBucketAggregator) aggregator).bucketDocCount(((InternalTerms.Bucket) o2).bucketOrd);
                        return mul * (v1 - v2);
                    }
                };
            }

            // with only support single-bucket aggregators
            assert !(aggregator instanceof BucketsAggregator) : "this should be picked up before the aggregation is executed - on validate";

            if (aggregator instanceof NumericMetricsAggregator.MultiValue) {
                assert key != null : "this should be picked up before the aggregation is executed - on validate";
                return new Comparator<Terms.Bucket>() {
                    @Override
                    public int compare(Terms.Bucket o1, Terms.Bucket o2) {
                        double v1 = ((NumericMetricsAggregator.MultiValue) aggregator).metric(key, ((InternalTerms.Bucket) o1).bucketOrd);
                        double v2 = ((NumericMetricsAggregator.MultiValue) aggregator).metric(key, ((InternalTerms.Bucket) o2).bucketOrd);
                        // some metrics may return NaN (eg. avg, variance, etc...) in which case we'd like to push all of those to
                        // the bottom
                        return Comparators.compareDiscardNaN(v1, v2, asc);
                    }
                };
            }

            // single-value metrics agg
            return new Comparator<Terms.Bucket>() {
                @Override
                public int compare(Terms.Bucket o1, Terms.Bucket o2) {
                    double v1 = ((NumericMetricsAggregator.SingleValue) aggregator).metric(((InternalTerms.Bucket) o1).bucketOrd);
                    double v2 = ((NumericMetricsAggregator.SingleValue) aggregator).metric(((InternalTerms.Bucket) o2).bucketOrd);
                    // some metrics may return NaN (eg. avg, variance, etc...) in which case we'd like to push all of those to
                    // the bottom
                    return Comparators.compareDiscardNaN(v1, v2, asc);
                }
            };
        }
    }

    static class CompoundOrder extends Terms.Order{

        static final byte ID = -1;

        private final List<Terms.Order> compoundOrder;

        public CompoundOrder(List<Terms.Order> compoundOrder) {
            this.compoundOrder = new LinkedList<>(compoundOrder);
        }

        @Override
        byte id() {
            return ID;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startArray();
            for (Terms.Order order : compoundOrder) {
                order.toXContent(builder, params);
            }
            return builder.endArray();
        }

        @Override
        protected Comparator<Bucket> comparator(Aggregator aggregator) {
            return new CompoundOrderComparator(compoundOrder, aggregator);
        }

        public static class CompoundOrderComparator implements Comparator<Terms.Bucket> {

            private List<Terms.Order> compoundOrder;
            private Aggregator aggregator;

            public CompoundOrderComparator(List<Terms.Order> compoundOrder, Aggregator aggregator) {
                this.compoundOrder = compoundOrder;
                this.aggregator = aggregator;
            }

            @Override
            public int compare(Bucket o1, Bucket o2) {
                int result = 0;
                for (Iterator<Terms.Order> itr = compoundOrder.iterator(); itr.hasNext() && result == 0;) {
                    result = itr.next().comparator(aggregator).compare(o1, o2);
                }
                return result;
            }
        }
    }

    public static class Streams {

        public static void writeOrder(Terms.Order order, StreamOutput out) throws IOException {
            out.writeByte(order.id());
            if (order instanceof Aggregation) {
                Aggregation aggregationOrder = (Aggregation) order;
                out.writeBoolean(((MultiBucketsAggregation.Bucket.SubAggregationComparator) aggregationOrder.comparator).asc());
                OrderPath path = ((Aggregation) order).path();
                if (out.getVersion().onOrAfter(Version.V_1_1_0)) {
                    out.writeString(path.toString());
                } else {
                    // prev versions only supported sorting on a single level -> a single token;
                    OrderPath.Token token = path.lastToken();
                    out.writeString(token.name);
                    boolean hasValueName = token.key != null;
                    out.writeBoolean(hasValueName);
                    if (hasValueName) {
                        out.writeString(token.key);
                    }
                }
            } else if (order instanceof CompoundOrder) {
                CompoundOrder compoundOrder = (CompoundOrder) order;
                out.writeVInt(compoundOrder.compoundOrder.size());
                for (Terms.Order innerOrder : compoundOrder.compoundOrder) {
                    Streams.writeOrder(innerOrder, out);
                }
            }
        }

        public static Terms.Order readOrder(StreamInput in) throws IOException {
            byte id = in.readByte();
            switch (id) {
                case COUNT_DESC_ID: return InternalOrder.COUNT_DESC;
                case COUNT_ASC_ID: return InternalOrder.COUNT_ASC;
                case TERM_DESC_ID: return InternalOrder.TERM_DESC;
                case TERM_ASC_ID: return InternalOrder.TERM_ASC;
                case Aggregation.ID:
                    boolean asc = in.readBoolean();
                    String key = in.readString();
                    if (in.getVersion().onOrAfter(Version.V_1_1_0)) {
                        return new InternalOrder.Aggregation(key, asc);
                    }
                    boolean hasValueNmae = in.readBoolean();
                    if (hasValueNmae) {
                        return new InternalOrder.Aggregation(key + "." + in.readString(), asc);
                    }
                    return new InternalOrder.Aggregation(key, asc);
                case CompoundOrder.ID:
                    int size = in.readVInt();
                    List<Terms.Order> compoundOrder = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        compoundOrder.add(Streams.readOrder(in));
                    }
                    return new CompoundOrder(compoundOrder);
                default:
                    throw new RuntimeException("unknown terms order");
            }
        }
    }
}
