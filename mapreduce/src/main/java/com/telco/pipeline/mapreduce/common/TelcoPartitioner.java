package com.telco.pipeline.mapreduce.common;

import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.Partitioner;

/**
 * Custom partitioner that ensures all records for the same tower
 * are routed to the same reducer, regardless of sector or time bucket.
 * This enables per-tower aggregation and temporal analysis.
 */
public class TelcoPartitioner<V extends Writable>
        extends Partitioner<TelcoWritables.TowerSectorKey, V> {

    @Override
    public int getPartition(TelcoWritables.TowerSectorKey key, V value,
                            int numPartitions) {
        // Partition by tower ID only, so all sectors/time buckets for the same
        // tower go to the same reducer
        int hash = key.getTowerId().hashCode();
        return (hash & Integer.MAX_VALUE) % numPartitions;
    }
}
