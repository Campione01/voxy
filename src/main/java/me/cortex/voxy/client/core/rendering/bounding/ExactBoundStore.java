package me.cortex.voxy.client.core.rendering.bounding;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.util.MemoryBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import org.lwjgl.system.MemoryUtil;

public final class ExactBoundStore implements IBoundStore {
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
        float distanceSquared = searchDistance * searchDistance;
        int blockX = (int) Math.floor(viewport.cameraX);
        int blockY = (int) Math.floor(viewport.cameraY);
        int blockZ = (int) Math.floor(viewport.cameraZ);
        float fractionalX = (float) (viewport.cameraX - blockX);
        float fractionalY = (float) (viewport.cameraY - blockY);
        float fractionalZ = (float) (viewport.cameraZ - blockZ);

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
        long blockPosition = Integer.toUnsignedLong(blockX) | (Integer.toUnsignedLong(blockZ) << 32);
        int minSectionX = ((int) Math.floor(viewport.cameraX - searchDistance) >> 4) - 2;
        int maxSectionX = ((int) Math.ceil(viewport.cameraX + searchDistance) >> 4) + 2;
        int minSectionZ = ((int) Math.floor(viewport.cameraZ - searchDistance) >> 4) - 2;
        int maxSectionZ = ((int) Math.ceil(viewport.cameraZ + searchDistance) >> 4) + 2;

        for (int sectionX = minSectionX; sectionX < maxSectionX; sectionX++) {
            for (int sectionZ = minSectionZ; sectionZ < maxSectionZ; sectionZ++) {
                if (!testXZPosition(blockPosition, fractionalX, fractionalZ, sectionX, sectionZ, distanceSquared)) {
                    continue;
                }

                boolean exposedCorner =
                        !testXZPosition(blockPosition, fractionalX, fractionalZ, sectionX + 1, sectionZ + 1, distanceSquared)
                        || !testXZPosition(blockPosition, fractionalX, fractionalZ, sectionX - 1, sectionZ + 1, distanceSquared)
                        || !testXZPosition(blockPosition, fractionalX, fractionalZ, sectionX + 1, sectionZ - 1, distanceSquared)
                        || !testXZPosition(blockPosition, fractionalX, fractionalZ, sectionX - 1, sectionZ - 1, distanceSquared);

                if (exposedCorner) {
                    for (int sectionY = minSectionY + 1; sectionY < maxSectionY; sectionY++) {
                        if (count++ < capacity && writePointer != 0) {
                            putPosition(writePointer, SectionPos.asLong(sectionX, sectionY, sectionZ));
                            writePointer += 8;
                        }
                    }
                }

                if (maxSectionY != minSectionY) {
                    if (count++ < capacity && writePointer != 0) {
                        putPosition(writePointer, SectionPos.asLong(sectionX, maxSectionY, sectionZ));
                        writePointer += 8;
                    }
                    if (count++ < capacity && writePointer != 0) {
                        putPosition(writePointer, SectionPos.asLong(sectionX, minSectionY, sectionZ));
                        writePointer += 8;
                    }
                } else if (count++ < capacity && writePointer != 0) {
                    putPosition(writePointer, SectionPos.asLong(sectionX, minSectionY, sectionZ));
                    writePointer += 8;
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

    private static boolean testXZPosition(long blockPosition, float fractionalX, float fractionalZ,
                                          int sectionX, int sectionZ, float distanceSquared) {
        int relativeX = sectionX * 16 - (int) blockPosition;
        int relativeZ = sectionZ * 16 - (int) (blockPosition >> 32);
        float deltaX = nearestToZero(relativeX - 1, relativeX + 17) - fractionalX;
        float deltaZ = nearestToZero(relativeZ - 1, relativeZ + 17) - fractionalZ;
        return deltaX * deltaX + deltaZ * deltaZ < distanceSquared;
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
