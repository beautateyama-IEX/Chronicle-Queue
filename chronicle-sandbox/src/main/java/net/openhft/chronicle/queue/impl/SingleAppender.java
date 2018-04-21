/*
 * Copyright 2015 Higher Frequency Trading
 *
 * http://www.higherfrequencytrading.com
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
package net.openhft.chronicle.queue.impl;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.NativeBytes;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.wire.Wire;
import net.openhft.chronicle.wire.WireOut;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/*
 * Created by peter.lawrey on 30/01/15.
 */
public class SingleAppender implements ExcerptAppender {

    @NotNull
    private final SingleChronicleQueue chronicle;
    private final Bytes buffer;
    private final Wire wire;

    private long lastWrittenIndex = -1;

    public SingleAppender(@NotNull final SingleChronicleQueue chronicle) {
        this.buffer = NativeBytes.nativeBytes();
        this.chronicle = chronicle;
        this.wire = chronicle.createWire(buffer);
    }

    @Nullable
    @Override
    public WireOut wire() {
        return wire;
    }

    @Override
    public void writeDocument(@NotNull WriteMarshallable writer) {
        buffer.clear();
        writer.accept(wire);
        buffer.flip();
        lastWrittenIndex = chronicle.appendDocument(buffer);
    }

    /**
     * @return the last index generated by this appender
     * @throws IllegalStateException if the last index has not been set
     */
    @Override
    public long lastWrittenIndex() {
        if (lastWrittenIndex == -1) {
            throw new IllegalStateException(
                    "No document has been written using this appender, so the "
                            + "lastWrittenIndex() is not available.");
        }

        return lastWrittenIndex;
    }

    @NotNull
    @Override
    public ChronicleQueue chronicle() {
        return chronicle;
    }
}
