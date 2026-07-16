package me.cortex.voxy.client.core.rendering.bounding;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.util.MemoryBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import org.lwjgl.system.MemoryUtil;

public final class ColumnStreamedBoundStore implements IBoundStore {
    private static final int INITIAL_MAX_SECTION_COUNT = 1 << 12;

    private GlBuffer sectionPositionBuffer = new GlBuffer(INITIAL_MAX_SECTION_COUNT * 8L);
    private int count;

    @Override
    public void preRender(Viewport<?> viewport) {
        float renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0f;
        long address = UploadStream.INSTANCE.uploadTo(this.sectionPositionBuffer);
        this.count = findEmitBoundingChunks(
                viewport,
                renderDistance,
                (int) (this.sectionPositionBuffer.size() / 8),
                address);
        UploadStream.INSTANCE.commit();

        if (this.count < 0) {
            this.sectionPositionBuffer.free();
            var sectionPositions = new MemoryBuffer(-this.count * 8L);
            this.count = findEmitBoundingChunks(viewport, renderDistance, -this.count, sectionPositions.address);
            if (this.count < 0) {
                sectionPositions.free();
                throw new IllegalStateException("Exact section bound capacity was insufficient: " + this.count);
            }
            this.sectionPositionBuffer = new GlBuffer(sectionPositions);
            sectionPositions.free();
        }
    }

    private static void putPosition(long pointer, long pos) {
        pos = IBoundStore.transformBeforeStore(pos);
        MemoryUtil.memPutInt(pointer, (int) pos);
        MemoryUtil.memPutInt(pointer + 4, (int) (pos >>> 32));
    }

    private static int findEmitBoundingChunks(Viewport<?> viewport, float searchDistance, int capacity, long writePointer) {
        int blockY = (int) Math.floor(viewport.cameraY);
        float fractionalY = (float) (viewport.cameraY - blockY);

        int minSectionY = blockY >> 4;
        int maxSectionY = blockY >> 4;
        while (testYPosition(blockY, fractionalY, minSectionY, searchDistance)) {
            minSectionY--;
        }
        minSectionY++;
        while (testYPosition(blockY, fractionalY, maxSectionY, searchDistance)) {
            maxSectionY++;
        }
        maxSectionY--;

        int count = 0;
        var level = Minecraft.getInstance().level;
        var tracker = level == null ? null : ChunkTrackerHolder.get(level);
        if (tracker != null) {
            var iterator = tracker.getReadyChunks().longIterator();
            while (iterator.hasNext()) {
                long column = iterator.nextLong();
                for (int sectionY = minSectionY + 1; sectionY < maxSectionY; sectionY++) {
                    if (count++ < capacity && writePointer != 0) {
                        putPosition(writePointer, SectionPos.asLong(
                                ChunkPos.getX(column),
                                sectionY,
                                ChunkPos.getZ(column)));
                        writePointer += 8;
                    }
                }
            }
        }

        return count > capacity ? -count : count;
    }

    private static boolean testYPosition(int blockY, float fractionalY, int sectionY, float distance) {
        int relativeY = sectionY * 16 - blockY;
        float deltaY = nearestToZero(relativeY - 1, relativeY + 17) - fractionalY;
        return Math.abs(deltaY) < distance;
    }

    private static int nearestToZero(int min, int max) {
        if (min > 0) {
            return min;
        }
        if (max < 0) {
            return max;
        }
        return 0;
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
        return this.count;
    }
}
