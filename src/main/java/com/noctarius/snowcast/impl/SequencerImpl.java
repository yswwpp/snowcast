/*
 * Copyright (c) 2014, Christoph Engelbert (aka noctarius) and
 * contributors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.noctarius.snowcast.impl;

import com.noctarius.snowcast.SnowcastEpoch;
import com.noctarius.snowcast.SnowcastSequenceState;
import com.noctarius.snowcast.SnowcastSequencer;
import com.noctarius.snowcast.SnowcastStateException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static com.noctarius.snowcast.impl.InternalSequencerUtils.calculateCounterMask;
import static com.noctarius.snowcast.impl.InternalSequencerUtils.calculateLogicalNodeMask;
import static com.noctarius.snowcast.impl.InternalSequencerUtils.calculateLogicalNodeShifting;
import static com.noctarius.snowcast.impl.InternalSequencerUtils.calculateMaxMillisCounter;
import static com.noctarius.snowcast.impl.InternalSequencerUtils.generateSequenceId;
import static com.noctarius.snowcast.impl.SnowcastConstants.INCREMENT_RETRY_TIMEOUT_NANOS;
import static com.noctarius.snowcast.impl.SnowcastConstants.SHIFT_COUNTER;
import static com.noctarius.snowcast.impl.SnowcastConstants.TC_COUNTER_READ_MASK;
import static com.noctarius.snowcast.impl.SnowcastConstants.TC_TIMESTAMP_READ_MASK;

public class SequencerImpl
        implements SnowcastSequencer {

    private static final AtomicReferenceFieldUpdater<SequencerImpl, SnowcastSequenceState> STATE_FIELD_UPDATER;

    private static final AtomicLongFieldUpdater<SequencerImpl> TIMESTAMP_AND_COUNTER_FIELD_UPDATER;

    static {
        STATE_FIELD_UPDATER = AtomicReferenceFieldUpdater.newUpdater(SequencerImpl.class, SnowcastSequenceState.class, "state");
        TIMESTAMP_AND_COUNTER_FIELD_UPDATER = AtomicLongFieldUpdater.newUpdater(SequencerImpl.class, "timestampAndCounter");
    }

    private final SequencerService service;
    private final SequencerDefinition definition;
    private final String sequencerName;
    private final SnowcastEpoch epoch;

    private final int nodeIdShiftFactor;
    private final int maxMillisCounter;

    private final long logicalNodeIdReadMask;
    private final long counterReadMask;

    private volatile SnowcastSequenceState state = SnowcastSequenceState.Detached;

    // Holds the currently assigned logical node id
    private volatile int logicalNodeId;

    // This field is only accessed or written through the field updater
    private volatile long timestampAndCounter;

    public SequencerImpl(SequencerService service, SequencerDefinition definition) {
        this.service = service;
        this.definition = definition;
        this.sequencerName = definition.getSequencerName();
        this.epoch = definition.getEpoch();

        int maxLogicalNodeCount = definition.getMaxLogicalNodeCount();
        this.nodeIdShiftFactor = calculateLogicalNodeShifting(maxLogicalNodeCount);
        this.logicalNodeIdReadMask = calculateLogicalNodeMask(maxLogicalNodeCount, nodeIdShiftFactor);
        this.counterReadMask = calculateCounterMask(maxLogicalNodeCount, nodeIdShiftFactor);
        this.maxMillisCounter = calculateMaxMillisCounter(nodeIdShiftFactor);

        // Just to prevent the "never-written" warning
        this.timestampAndCounter = 0;
    }

    @Override
    public String getSequencerName() {
        return sequencerName;
    }

    @Override
    public long next()
            throws InterruptedException {

        int logicalNodeID = checkStateAndLogicalNodeId();
        long timestamp = epoch.getEpochTimestamp();

        int nextId;
        while (true) {
            checkAndUpdateTimestamp(timestamp);

            nextId = increment(timestamp);
            if (nextId != -1) {
                break;
            }

            TimeUnit.NANOSECONDS.sleep(INCREMENT_RETRY_TIMEOUT_NANOS);
            timestamp = epoch.getEpochTimestamp();
        }

        return generateSequenceId(timestamp, logicalNodeID, nextId, nodeIdShiftFactor);
    }

    @Override
    public SnowcastSequenceState getSequencerState() {
        return state;
    }

    @Override
    public SnowcastSequencer attachLogicalNode() {
        // Will fail if state transition is not allowed
        stateTransition(SnowcastSequenceState.Attached);

        // Request sequencer remote assignment
        logicalNodeId = service.attachSequencer(definition);

        return this;
    }

    @Override
    public SnowcastSequencer detachLogicalNode() {
        // Will fail if state transition is not allowed
        stateTransition(SnowcastSequenceState.Detached);

        int logicalNodeId = this.logicalNodeId;
        this.logicalNodeId = -1;

        // Remove sequencer remote assignment
        service.detachSequencer(sequencerName, logicalNodeId);

        return this;
    }

    @Override
    public long timestampValue(long sequenceId) {
        return InternalSequencerUtils.timestampValue(sequenceId);
    }

    @Override
    public int logicalNodeId(long sequenceId) {
        return InternalSequencerUtils.logicalNodeId(sequenceId, nodeIdShiftFactor, logicalNodeIdReadMask);
    }

    @Override
    public int counterValue(long sequenceId) {
        return InternalSequencerUtils.counterValue(sequenceId, counterReadMask);
    }

    void stateTransition(SnowcastSequenceState newState) {
        while (true) {
            SnowcastSequenceState state = this.state;
            if (state == newState) {
                return;
            }

            if (newState == SnowcastSequenceState.Detached) {
                if (state != SnowcastSequenceState.Attached) {
                    String message = ExceptionMessages.SEQUENCER_WRONG_STATE_CANNOT_DETACH.buildMessage(sequencerName);
                    throw new SnowcastStateException(message);
                }

            } else if (newState == SnowcastSequenceState.Attached) {
                if (state != SnowcastSequenceState.Detached) {
                    String message = ExceptionMessages.SEQUENCER_WRONG_STATE_CANNOT_ATTACH.buildMessage(sequencerName);
                    throw new SnowcastStateException(message);
                }
            } else {
                if (state == SnowcastSequenceState.Destroyed) {
                    return;
                }
            }

            if (STATE_FIELD_UPDATER.compareAndSet(this, state, newState)) {
                return;
            }
        }
    }

    private void checkAndUpdateTimestamp(long timestamp) {
        while (true) {
            long timestampAndCounter = this.timestampAndCounter;
            long lastTimestamp = timestampAndCounter & TC_TIMESTAMP_READ_MASK;
            if (lastTimestamp < timestamp) {
                if (TIMESTAMP_AND_COUNTER_FIELD_UPDATER.compareAndSet(this, timestampAndCounter, timestamp)) {
                    break;
                }
            } else {
                break;
            }
        }
    }

    private int increment(long expectedTimestamp) {
        while (true) {
            long timestampAndCounter = this.timestampAndCounter;

            // Extract values
            long counter = (timestampAndCounter & TC_COUNTER_READ_MASK) >> SHIFT_COUNTER;
            long timestamp = timestampAndCounter & TC_TIMESTAMP_READ_MASK;

            if (expectedTimestamp != timestamp) {
                return -1;
            }

            // Increment the counter
            counter++;

            // Exceeded the counters value for a single millisecond
            if (counter > maxMillisCounter) {
                return -1;
            }

            // Build the new combined timestamp and counter value
            long newTC = timestamp | (counter << SHIFT_COUNTER);
            if (TIMESTAMP_AND_COUNTER_FIELD_UPDATER.compareAndSet(this, timestampAndCounter, newTC)) {
                return (int) counter;
            }
        }
    }

    private int checkStateAndLogicalNodeId() {
        int logicalNodeId = this.logicalNodeId;
        if (logicalNodeId == -1) {
            String message = ExceptionMessages.SEQUENCER_NOT_ASSIGNED.buildMessage(sequencerName);
            throw new SnowcastStateException(message);
        }
        SnowcastSequenceState state = this.state;
        if (state != SnowcastSequenceState.Attached) {
            String message = ExceptionMessages.SEQUENCER_IN_WRONG_STATE
                    .buildMessage(sequencerName, SnowcastSequenceState.Attached, state);
            throw new SnowcastStateException(message);
        }
        return logicalNodeId;
    }
}
