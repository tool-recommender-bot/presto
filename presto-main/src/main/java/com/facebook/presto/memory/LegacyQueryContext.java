/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.memory;

import com.facebook.presto.Session;
import com.facebook.presto.execution.TaskId;
import com.facebook.presto.execution.TaskStateMachine;
import com.facebook.presto.memory.context.MemoryReservationHandler;
import com.facebook.presto.memory.context.MemoryTrackingContext;
import com.facebook.presto.operator.TaskContext;
import com.facebook.presto.spi.QueryId;
import com.facebook.presto.spiller.SpillSpaceTracker;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.stats.GcMonitor;
import io.airlift.units.DataSize;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import static com.facebook.presto.ExceededMemoryLimitException.exceededLocalUserMemoryLimit;
import static com.facebook.presto.ExceededSpillLimitException.exceededPerQueryLocalLimit;
import static com.facebook.presto.memory.context.AggregatedMemoryContext.newRootAggregatedMemoryContext;
import static com.facebook.presto.operator.Operator.NOT_BLOCKED;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static io.airlift.units.DataSize.Unit.MEGABYTE;
import static io.airlift.units.DataSize.succinctBytes;
import static java.lang.String.format;
import static java.util.Map.Entry.comparingByValue;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

//TODO Remove this class and QueryContext interface once the system pool is removed completely.
@Deprecated
@ThreadSafe
public class LegacyQueryContext
        implements QueryContext
{
    private static final long GUARANTEED_MEMORY = new DataSize(1, MEGABYTE).toBytes();

    private final QueryId queryId;
    private final GcMonitor gcMonitor;
    private final Executor notificationExecutor;
    private final ScheduledExecutorService yieldExecutor;
    private final long maxSpill;
    private final SpillSpaceTracker spillSpaceTracker;
    private final Map<TaskId, TaskContext> taskContexts = new ConcurrentHashMap();
    private final MemoryPool systemMemoryPool;

    // TODO: This field should be final. However, due to the way QueryContext is constructed the memory limit is not known in advance
    @GuardedBy("this")
    private long maxMemory;

    private final MemoryTrackingContext queryMemoryContext;

    @GuardedBy("this")
    private MemoryPool memoryPool;

    @GuardedBy("this")
    private long spillUsed;

    public LegacyQueryContext(
            QueryId queryId,
            DataSize maxMemory,
            MemoryPool memoryPool,
            MemoryPool systemMemoryPool,
            GcMonitor gcMonitor,
            Executor notificationExecutor,
            ScheduledExecutorService yieldExecutor,
            DataSize maxSpill,
            SpillSpaceTracker spillSpaceTracker)
    {
        this.queryId = requireNonNull(queryId, "queryId is null");
        this.maxMemory = requireNonNull(maxMemory, "maxMemory is null").toBytes();
        this.memoryPool = requireNonNull(memoryPool, "memoryPool is null");
        this.systemMemoryPool = requireNonNull(systemMemoryPool, "systemMemoryPool is null");
        this.gcMonitor = requireNonNull(gcMonitor, "gcMonitor is null");
        this.notificationExecutor = requireNonNull(notificationExecutor, "notificationExecutor is null");
        this.yieldExecutor = requireNonNull(yieldExecutor, "yieldExecutor is null");
        this.maxSpill = requireNonNull(maxSpill, "maxSpill is null").toBytes();
        this.spillSpaceTracker = requireNonNull(spillSpaceTracker, "spillSpaceTracker is null");
        this.queryMemoryContext = new MemoryTrackingContext(
                newRootAggregatedMemoryContext(new QueryMemoryReservationHandler(this::updateUserMemory, this::tryUpdateUserMemory), GUARANTEED_MEMORY),
                newRootAggregatedMemoryContext(new QueryMemoryReservationHandler(this::updateRevocableMemory, this::tryReserveMemoryNotSupported), 0L),
                newRootAggregatedMemoryContext(new QueryMemoryReservationHandler(this::updateSystemMemory, this::tryReserveMemoryNotSupported), 0L));
    }

    // TODO: This method should be removed, and the correct limit set in the constructor. However, due to the way QueryContext is constructed the memory limit is not known in advance
    @Override
    public synchronized void setResourceOvercommit()
    {
        // Allow the query to use the entire pool. This way the worker will kill the query, if it uses the entire local general pool.
        // The coordinator will kill the query if the cluster runs out of memory.
        maxMemory = memoryPool.getMaxBytes();
    }

    private synchronized ListenableFuture<?> updateUserMemory(String allocationTag, long delta)
    {
        if (delta >= 0) {
            enforceUserMemoryLimit(queryMemoryContext.getUserMemory(), delta, maxMemory);
            return memoryPool.reserve(queryId, allocationTag, delta);
        }
        memoryPool.free(queryId, allocationTag, -delta);
        return NOT_BLOCKED;
    }

    //TODO Add tagging support for revocable memory reservations if needed
    private synchronized ListenableFuture<?> updateRevocableMemory(String allocationTag, long delta)
    {
        if (delta >= 0) {
            return memoryPool.reserveRevocable(queryId, delta);
        }
        memoryPool.freeRevocable(queryId, -delta);
        return NOT_BLOCKED;
    }

    private synchronized ListenableFuture<?> updateSystemMemory(String allocationTag, long delta)
    {
        if (delta >= 0) {
            systemMemoryPool.reserve(queryId, allocationTag, delta);
            // Since various operators and the output buffers now support blocking when the system pool is full
            // we return NOT_BLOCKED to prevent them from blocking, which is the legacy behavior.
            return NOT_BLOCKED;
        }
        systemMemoryPool.free(queryId, allocationTag, -delta);
        return NOT_BLOCKED;
    }

    //TODO move spill tracking to the new memory tracking framework
    @Override
    public synchronized ListenableFuture<?> reserveSpill(long bytes)
    {
        checkArgument(bytes >= 0, "bytes is negative");
        if (spillUsed + bytes > maxSpill) {
            throw exceededPerQueryLocalLimit(succinctBytes(maxSpill));
        }
        ListenableFuture<?> future = spillSpaceTracker.reserve(bytes);
        spillUsed += bytes;
        return future;
    }

    private synchronized boolean tryUpdateUserMemory(String allocationTag, long delta)
    {
        if (delta <= 0) {
            ListenableFuture<?> future = updateUserMemory(allocationTag, delta);
            // When delta == 0 and the pool is full the future can still not be done,
            // but, for negative deltas it must always be done.
            if (delta < 0) {
                verify(future.isDone(), "future should be done");
            }
            return true;
        }
        if (queryMemoryContext.getUserMemory() + delta > maxMemory) {
            return false;
        }
        return memoryPool.tryReserve(queryId, allocationTag, delta);
    }

    @Override
    public synchronized void freeSpill(long bytes)
    {
        checkArgument(spillUsed - bytes >= 0, "tried to free more memory than is reserved");
        spillUsed -= bytes;
        spillSpaceTracker.free(bytes);
    }

    @Override
    public synchronized void setMemoryPool(MemoryPool newMemoryPool)
    {
        // This method first acquires the monitor of this instance.
        // After that in this method if we acquire the monitors of the
        // user/revocable memory contexts in the queryMemoryContext instance
        // (say, by calling queryMemoryContext.getUserMemory()) it's possible
        // to have a deadlock. Because, the driver threads running the operators
        // will allocate memory concurrently through the child memory context -> ... ->
        // root memory context -> this.updateUserMemory() calls, and will acquire
        // the monitors of the user/revocable memory contexts in the queryMemoryContext instance
        // first, and then the monitor of this, which may cause deadlocks.
        // That's why instead of calling methods on queryMemoryContext to get the
        // user/revocable memory reservations, we call the MemoryPool to get the same
        // information.
        requireNonNull(newMemoryPool, "newMemoryPool is null");
        if (memoryPool == newMemoryPool) {
            // Don't unblock our tasks and thrash the pools, if this is a no-op
            return;
        }
        ListenableFuture<?> future = memoryPool.moveQuery(queryId, newMemoryPool);
        memoryPool = newMemoryPool;
        future.addListener(() -> {
            // Unblock all the tasks, if they were waiting for memory, since we're in a new pool.
            taskContexts.values().forEach(TaskContext::moreMemoryAvailable);
        }, directExecutor());
    }

    @Override
    public synchronized MemoryPool getMemoryPool()
    {
        return memoryPool;
    }

    @Override
    public TaskContext addTaskContext(TaskStateMachine taskStateMachine, Session session, boolean perOperatorCpuTimerEnabled, boolean cpuTimerEnabled, OptionalInt totalPartitions)
    {
        TaskContext taskContext = TaskContext.createTaskContext(
                this,
                taskStateMachine,
                gcMonitor,
                notificationExecutor,
                yieldExecutor,
                session,
                queryMemoryContext.newMemoryTrackingContext(),
                perOperatorCpuTimerEnabled,
                cpuTimerEnabled,
                totalPartitions);
        taskContexts.put(taskStateMachine.getTaskId(), taskContext);
        return taskContext;
    }

    @Override
    public <C, R> R accept(QueryContextVisitor<C, R> visitor, C context)
    {
        return visitor.visitQueryContext(this, context);
    }

    @Override
    public <C, R> List<R> acceptChildren(QueryContextVisitor<C, R> visitor, C context)
    {
        return taskContexts.values()
                .stream()
                .map(taskContext -> taskContext.accept(visitor, context))
                .collect(toList());
    }

    @Override
    public TaskContext getTaskContextByTaskId(TaskId taskId)
    {
        TaskContext taskContext = taskContexts.get(taskId);
        verify(taskContext != null, "task does not exist");
        return taskContext;
    }

    private static class QueryMemoryReservationHandler
            implements MemoryReservationHandler
    {
        private final BiFunction<String, Long, ListenableFuture<?>> reserveMemoryFunction;
        private final BiPredicate<String, Long> tryReserveMemoryFunction;

        public QueryMemoryReservationHandler(
                BiFunction<String, Long, ListenableFuture<?>> reserveMemoryFunction,
                BiPredicate<String, Long> tryReserveMemoryFunction)
        {
            this.reserveMemoryFunction = requireNonNull(reserveMemoryFunction, "reserveMemoryFunction is null");
            this.tryReserveMemoryFunction = requireNonNull(tryReserveMemoryFunction, "tryReserveMemoryFunction is null");
        }

        @Override
        public ListenableFuture<?> reserveMemory(String allocationTag, long delta)
        {
            return reserveMemoryFunction.apply(allocationTag, delta);
        }

        @Override
        public boolean tryReserveMemory(String allocationTag, long delta)
        {
            return tryReserveMemoryFunction.test(allocationTag, delta);
        }
    }

    private boolean tryReserveMemoryNotSupported(String allocationTag, long bytes)
    {
        throw new UnsupportedOperationException("tryReserveMemory is not supported");
    }

    @GuardedBy("this")
    private void enforceUserMemoryLimit(long allocated, long delta, long maxMemory)
    {
        if (allocated + delta > maxMemory) {
            throw exceededLocalUserMemoryLimit(succinctBytes(maxMemory), getAdditionalFailureInfo(allocated, delta));
        }
    }

    @GuardedBy("this")
    private String getAdditionalFailureInfo(long allocated, long delta)
    {
        Map<String, Long> queryAllocations = memoryPool.getTaggedMemoryAllocations().get(queryId);

        String additionalInfo = format("Allocated: %s, Delta: %s", succinctBytes(allocated), succinctBytes(delta));

        // It's possible that a query tries allocating more than the available memory
        // failing immediately before any allocation of that query is tagged
        if (queryAllocations == null) {
            return additionalInfo;
        }

        String topConsumers = queryAllocations.entrySet().stream()
                .sorted(comparingByValue(Comparator.reverseOrder()))
                .limit(3)
                .collect(toImmutableMap(Entry::getKey, e -> succinctBytes(e.getValue())))
                .toString();

        return format("%s, Top Consumers: %s", additionalInfo, topConsumers);
    }
}
