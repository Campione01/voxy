package me.cortex.voxy.client.core.rendering.bounding;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.ARBDirectStateAccess.glCopyNamedBufferSubData;

public final class ChunkBoundStore implements IBoundStore {
    private static final int INITIAL_MAX_SECTION_COUNT = 1 << 12;

    private GlBuffer sectionPositionBuffer = new GlBuffer(INITIAL_MAX_SECTION_COUNT * 8L);
    private final Long2IntOpenHashMap positionToIndex = new Long2IntOpenHashMap(INITIAL_MAX_SECTION_COUNT);
    private long[] indexToPosition = new long[INITIAL_MAX_SECTION_COUNT];
    private final LongOpenHashSet addQueue = new LongOpenHashSet();
    private final LongOpenHashSet removeQueue = new LongOpenHashSet();

    public ChunkBoundStore() {
        this.positionToIndex.defaultReturnValue(-1);
    }

    public void addSection(long pos) {
        if (!this.removeQueue.remove(pos)) {
            this.addQueue.add(pos);
        }
    }

    public void removeSection(long pos) {
        if (!this.addQueue.remove(pos)) {
            this.removeQueue.add(pos);
        }
    }

    @Override
    public void preRender(Viewport<?> viewport) {
        if (!this.removeQueue.isEmpty()) {
            this.removeQueue.forEach(this::removePosition);
            this.removeQueue.clear();
        }
    }

    @Override
    public void postRender(Viewport<?> viewport) {
        if (!this.addQueue.isEmpty()) {
            this.addQueue.forEach(this::addPosition);
            this.addQueue.clear();
            UploadStream.INSTANCE.commit();
        }
    }

    private void removePosition(long pos) {
        int index = this.positionToIndex.remove(pos);
        if (index == -1) {
            Logger.warn("Section not in bound store: " + pos);
            return;
        }
        if (index == this.positionToIndex.size()) {
            return;
        }
        if (this.indexToPosition[index] != pos) {
            throw new IllegalStateException("Section bound index map is inconsistent");
        }

        long endPosition = this.indexToPosition[this.positionToIndex.size()];
        if (this.positionToIndex.put(endPosition, index) == -1) {
            throw new IllegalStateException("Section bound replacement was missing");
        }
        this.indexToPosition[index] = endPosition;
        this.put(index, endPosition);
    }

    private void addPosition(long pos) {
        if (this.positionToIndex.containsKey(pos)) {
            Logger.warn("Section already in bound store: " + pos);
            return;
        }

        this.ensureCapacity();
        int index = this.positionToIndex.size();
        this.positionToIndex.put(pos, index);
        this.indexToPosition[index] = pos;
        this.put(index, pos);
    }

    private void ensureCapacity() {
        if (this.positionToIndex.size() < this.indexToPosition.length) {
            return;
        }

        UploadStream.INSTANCE.commit();
        int size = (int) (this.indexToPosition.length * 1.5);
        Logger.info("Resizing section position buffer to: " + size);

        var oldBuffer = this.sectionPositionBuffer;
        this.sectionPositionBuffer = new GlBuffer(size * 8L);
        glCopyNamedBufferSubData(oldBuffer.id, this.sectionPositionBuffer.id, 0, 0, oldBuffer.size());
        oldBuffer.free();

        var oldPositions = this.indexToPosition;
        this.indexToPosition = new long[size];
        System.arraycopy(oldPositions, 0, this.indexToPosition, 0, oldPositions.length);
    }

    private void put(int index, long pos) {
        long pointer = UploadStream.INSTANCE.upload(this.sectionPositionBuffer, 8L * index, 8);
        pos = IBoundStore.transformBeforeStore(pos);
        MemoryUtil.memPutInt(pointer, (int) pos);
        MemoryUtil.memPutInt(pointer + 4, (int) (pos >>> 32));
    }

    public void reset() {
        this.positionToIndex.clear();
    }

    @Override
    public void free() {
        this.sectionPositionBuffer.free();
    }

    @Override
    public GlBuffer getBuffer() {
        return this.sectionPositionBuffer;
    }

    @Override
    public int getCount() {
        return this.positionToIndex.size();
    }
}
