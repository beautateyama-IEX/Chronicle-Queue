/*
 * Copyright 2016 higherfrequencytrading.com
 *
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

package net.openhft.chronicle.queue.impl;

import net.openhft.chronicle.bytes.BytesRingBufferStats;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.Maths;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.time.SystemTimeProvider;
import net.openhft.chronicle.core.time.TimeProvider;
import net.openhft.chronicle.core.util.ObjectUtils;
import net.openhft.chronicle.core.util.StringUtils;
import net.openhft.chronicle.queue.*;
import net.openhft.chronicle.queue.impl.single.RollCycleRetriever;
import net.openhft.chronicle.queue.impl.single.StoreRecoveryFactory;
import net.openhft.chronicle.queue.impl.single.TimedStoreRecovery;
import net.openhft.chronicle.threads.Pauser;
import net.openhft.chronicle.threads.TimeoutPauser;
import net.openhft.chronicle.wire.WireType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import java.io.File;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static net.openhft.chronicle.queue.ChronicleQueue.TEST_BLOCK_SIZE;

/*
 * Created by Peter Lawrey on 05/03/2016.
 */
@SuppressWarnings("ALL")
public abstract class AbstractChronicleQueueBuilder<B extends ChronicleQueueBuilder>
        implements ChronicleQueueBuilder<B> {
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractChronicleQueueBuilder.class);
    public static final String DEFAULT_ROLL_CYCLE_PROPERTY = "net.openhft.queue.builder.defaultRollCycle";
    public static final String DEFAULT_EPOCH_PROPERTY = "net.openhft.queue.builder.defaultEpoch";

    protected final File path;
    protected long blockSize;
    @NotNull
    protected WireType wireType;
    @NotNull
    protected RollCycle rollCycle;
    protected long epoch; // default is 1970-01-01 00:00:00.000 UTC
    protected BufferMode writeBufferMode = BufferMode.None, readBufferMode = BufferMode.None;
    @Nullable
    protected EventLoop eventLoop;
    @NotNull
    protected CycleCalculator cycleCalculator = DefaultCycleCalculator.INSTANCE;
    private long bufferCapacity;
    private int indexSpacing;
    private int indexCount;
    /**
     * by default logs the performance stats of the ring buffer
     */
    @NotNull
    private Consumer<BytesRingBufferStats> onRingBufferStats = NoBytesRingBufferStats.NONE;
    private TimeProvider timeProvider = SystemTimeProvider.INSTANCE;
    private Supplier<Pauser> pauserSupplier = () -> new TimeoutPauser(500_000);
    private long timeoutMS = 10_000; // 10 seconds.
    private WireStoreFactory storeFactory;
    private int sourceId = 0;
    private StoreRecoveryFactory recoverySupplier = TimedStoreRecovery.FACTORY;
    private StoreFileListener storeFileListener;

    private boolean readOnly = false;
    private boolean rollCycleSet = false;
    private boolean progressOnContention = false;

    public AbstractChronicleQueueBuilder(File path) {
        this.rollCycle = loadDefaultRollCycle();


        this.blockSize = OS.is64Bit() ? 64L << 20 : TEST_BLOCK_SIZE;
        this.path = path;
        this.wireType = WireType.BINARY_LIGHT;
        this.epoch = Long.getLong(DEFAULT_EPOCH_PROPERTY, 0L);
        this.bufferCapacity = -1;
        this.indexSpacing = -1;
        this.indexCount = -1;
        storeFileListener = (cycle, file) -> {
            if (Jvm.isDebugEnabled(getClass()))
                Jvm.debug().on(getClass(), "File released " + file);
        };
    }

    private RollCycle loadDefaultRollCycle(){
        if (null == System.getProperty(DEFAULT_ROLL_CYCLE_PROPERTY)) {
            return RollCycles.DAILY;
        }

        String rollCycleProperty = System.getProperty(DEFAULT_ROLL_CYCLE_PROPERTY);
        String[] rollCyclePropertyParts = rollCycleProperty.split(":");
        if(rollCyclePropertyParts.length > 0) {
            try {
                Class rollCycleClass = Class.forName(rollCyclePropertyParts[0]);
                if (Enum.class.isAssignableFrom(rollCycleClass)) {
                    if(rollCyclePropertyParts.length < 2){
                        LOGGER.warn("Default roll cycle configured as enum, but enum value not specified: " + rollCycleProperty);
                    } else {
                        Class<Enum> eClass = (Class<Enum>) rollCycleClass;
                        Object instance = ObjectUtils.valueOf(eClass, rollCyclePropertyParts[1]);
                        if(instance instanceof RollCycle) {
                            return (RollCycle) instance;
                        } else {
                            LOGGER.warn("Configured default rollcycle is not a subclass of RollCycle");
                        }
                    }
                } else {
                    Object instance = ObjectUtils.newInstance(rollCycleClass);
                    if(instance instanceof RollCycle) {
                        return (RollCycle) instance;
                    } else {
                        LOGGER.warn("Configured default rollcycle is not a subclass of RollCycle");
                    }
                }
            } catch (ClassNotFoundException ignored) {
                LOGGER.warn("Default roll cycle class: " + rollCyclePropertyParts[0] + " was not found");
            }
        }

        return RollCycles.DAILY;
    }

    protected Logger getLogger() {
        return LoggerFactory.getLogger(getClass().getName());
    }

    @Override
    @NotNull
    public CycleCalculator cycleCalculator() {
        return cycleCalculator;
    }

    @Override
    public B rollTime(@NotNull final LocalTime time, final ZoneId zoneId) {
        this.epoch = TimeUnit.SECONDS.toMillis(time.toSecondOfDay());
        return (B) this;
    }

    /**
     * consumer will be called every second, also as there is data to report
     *
     * @param onRingBufferStats a consumer of the BytesRingBufferStats
     * @return this
     */
    @Override
    @NotNull
    public B onRingBufferStats(@NotNull Consumer<BytesRingBufferStats> onRingBufferStats) {
        this.onRingBufferStats = onRingBufferStats;
        return (B) this;
    }

    @NotNull
    @Override
    public Consumer<BytesRingBufferStats> onRingBufferStats() {
        return this.onRingBufferStats;
    }

    @Override
    @NotNull
    public File path() {
        return this.path;
    }

    @Override
    @NotNull
    public B blockSize(int blockSize) {
        this.blockSize = Math.max(TEST_BLOCK_SIZE, blockSize);
        return (B) this;
    }

    @Override
    public long blockSize() {
        // can add an index2index & an index in one go.
        long minSize = Math.max(TEST_BLOCK_SIZE, 32L * indexCount());
        return Math.max(minSize, this.blockSize);
    }

    @Override
    @NotNull
    public B wireType(@NotNull WireType wireType) {
        this.wireType = wireType;
        return (B) this;
    }

    @Override
    @NotNull
    public WireType wireType() {
        return this.wireType;
    }

    @Override
    @NotNull
    public B rollCycle(@NotNull RollCycle rollCycle) {
        this.rollCycle = rollCycle;
        rollCycleSet = true;
        return (B) this;
    }

    /**
     * @return ringBufferCapacity in bytes
     */
    @Override
    public long bufferCapacity() {
        return Math.min(blockSize / 4, bufferCapacity == -1 ? 2 << 20 : Math.max(4 << 10, bufferCapacity));
    }

    /**
     * @param bufferCapacity sets the ring buffer capacity in bytes
     * @return this
     */
    @Override
    @NotNull
    public B bufferCapacity(long bufferCapacity) {
        this.bufferCapacity = bufferCapacity;
        return (B) this;
    }

    /**
     * sets epoch offset in milliseconds
     *
     * @param epoch sets an epoch offset as the number of number of milliseconds since January 1,
     *              1970,  00:00:00 GMT
     * @return {@code this}
     */
    @Override
    @NotNull
    public B epoch(long epoch) {
        this.epoch = epoch;
        return (B) this;
    }

    /**
     * @return epoch offset as the number of number of milliseconds since January 1, 1970,  00:00:00
     * GMT
     */
    @Override
    public long epoch() {
        return epoch;
    }

    @Override
    @NotNull
    public RollCycle rollCycle() {
        return this.rollCycle;
    }

    /**
     * when set to {@code true}. uses a ring buffer to buffer appends, excerpts are written to the
     * Chronicle Queue using a background thread
     *
     * @param isBuffered {@code true} if the append is buffered
     * @return this
     */
    @Override
    @NotNull
    @Deprecated
    public B buffered(boolean isBuffered) {
        this.writeBufferMode = isBuffered ? BufferMode.Asynchronous : BufferMode.None;
        return (B) this;
    }

    /**
     * @return if we uses a ring buffer to buffer the appends, the Excerpts are written to the
     * Chronicle Queue using a background thread
     */
    @Override
    @Deprecated
    public boolean buffered() {
        return this.writeBufferMode == BufferMode.Asynchronous;
    }

    /**
     * @return BufferMode to use for writes. Only None is available is the OSS
     */
    @NotNull
    public BufferMode writeBufferMode() {
        return wireType() == WireType.DELTA_BINARY ? BufferMode.None : writeBufferMode;
    }

    public B writeBufferMode(BufferMode writeBufferMode) {
        this.writeBufferMode = writeBufferMode;
        return (B) this;
    }

    /**
     * @return BufferMode to use for reads. Only None is available is the OSS
     */
    public BufferMode readBufferMode() {
        return readBufferMode;
    }

    public B readBufferMode(BufferMode readBufferMode) {
        this.readBufferMode = readBufferMode;
        return (B) this;
    }

    @Override
    @Nullable
    public EventLoop eventLoop() {
        return eventLoop;
    }

    @Override
    @NotNull
    public B eventLoop(EventLoop eventLoop) {
        this.eventLoop = eventLoop;
        return (B) this;
    }

    @Override
    public B indexCount(int indexCount) {
        this.indexCount = Maths.nextPower2(indexCount, 8);
        return (B) this;
    }

    @Override
    public int indexCount() {
        return indexCount <= 0 ? rollCycle.defaultIndexCount() : indexCount;
    }

    @Override
    public B indexSpacing(int indexSpacing) {
        this.indexSpacing = Maths.nextPower2(indexSpacing, 1);
        return (B) this;
    }

    @Override
    public int indexSpacing() {
        return indexSpacing <= 0 ? rollCycle.defaultIndexSpacing() : indexSpacing;
    }

    public TimeProvider timeProvider() {
        return timeProvider;
    }

    public B timeProvider(TimeProvider timeProvider) {
        this.timeProvider = timeProvider;
        return (B) this;
    }

    public Supplier<Pauser> pauserSupplier() {
        return pauserSupplier;
    }

    public B pauserSupplier(Supplier<Pauser> pauser) {
        this.pauserSupplier = pauser;
        return (B) this;
    }

    public B timeoutMS(long timeoutMS) {
        this.timeoutMS = timeoutMS;
        return (B) this;
    }

    public long timeoutMS() {
        return timeoutMS;
    }

    public void storeFactory(WireStoreFactory storeFactory) {
        this.storeFactory = storeFactory;
    }

    @Override
    public WireStoreFactory storeFactory() {
        return storeFactory;
    }

    @Override
    public B storeFileListener(StoreFileListener storeFileListener) {
        this.storeFileListener = storeFileListener;
        return (B) this;
    }

    @Override
    public StoreFileListener storeFileListener() {
        return storeFileListener;
    }

    public B sourceId(int sourceId) {
        if (sourceId < 0)
            throw new IllegalArgumentException("Invalid source Id, must be positive");
        this.sourceId = sourceId;
        return (B) this;
    }

    public int sourceId() {
        return sourceId;
    }

    public StoreRecoveryFactory recoverySupplier() {
        return recoverySupplier;
    }

    public B recoverySupplier(StoreRecoveryFactory recoverySupplier) {
        this.recoverySupplier = recoverySupplier;
        return (B) this;
    }

    @Override
    public boolean readOnly() {
        return readOnly;
    }

    @Override
    public B readOnly(boolean readOnly) {
        if (OS.isWindows() && readOnly) {
            Jvm.warn().on(AbstractChronicleQueueBuilder.class,
                    "Read-only mode is not supported on Windows® platforms, defaulting to read/write.");
            this.readOnly = false;
        } else {
            this.readOnly = readOnly;
        }
        return (B) this;
    }

    @Override
    public boolean progressOnContention() {
        return progressOnContention;
    }

    @Override
    public B progressOnContention(boolean progressOnContention) {
        this.progressOnContention = progressOnContention;
        return (B) this;
    }

    @NotNull
    public AbstractChronicleQueueBuilder encryptSupplier(Supplier<Cipher> encryptSupplier) {
        throw new UnsupportedOperationException("Encryption supported in Chronicle Queue Enterprise");
    }

    @NotNull
    public AbstractChronicleQueueBuilder decryptSupplier(Supplier<Cipher> decryptSupplier) {
        throw new UnsupportedOperationException("Encryption supported in Chronicle Queue Enterprise");
    }

    protected void preBuild() {
        assignRollCycleFromExistingQueueFile();
    }

    private void assignRollCycleFromExistingQueueFile() {
        final Optional<RollCycle> existingRollCycle = RollCycleRetriever.getRollCycle(path.toPath(), wireType, blockSize);
        existingRollCycle.ifPresent(rc -> {
            if (rollCycleSet && rc != rollCycle) {
                throw new IllegalArgumentException(String.format(
                        "Trying to create queue with roll cycle %s, but existing queue files use %s",
                        rollCycle, rc));
            } else if (rc != rollCycle) {
                rollCycle = rc;
            }
        });
    }

    enum NoBytesRingBufferStats implements Consumer<BytesRingBufferStats> {
        NONE;

        @Override
        public void accept(BytesRingBufferStats bytesRingBufferStats) {
        }
    }
}
