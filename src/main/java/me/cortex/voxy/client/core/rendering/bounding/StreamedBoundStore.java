package me.cortex.voxy.client.core.rendering.bounding;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.util.UnsafeUtil;

import java.util.Arrays;

public final class StreamedBoundStore implements IBoundStore {
    private static final int INITIAL_MAX_SECTION_COUNT = 1 << 12;

    private GlBuffer sectionPositionBuffer = new GlBuffer(INITIAL_MAX_SECTION_COUNT * 8L);
    private int count;
    private boolean changed;
    private int[] visibleSections = new int[INITIAL_MAX_SECTION_COUNT * 2];

    @Override
    public void preRender(Viewport<?> viewport) {
        if (this.count == 0 || !this.changed) {
            return;
        }

        if (this.count * 4L > this.sectionPositionBuffer.size()) {
            this.sectionPositionBuffer.free();
            this.sectionPositionBuffer = new GlBuffer((long) Math.ceil(this.count * 1.25) * 4L);
        }

        long address = UploadStream.INSTANCE.upload(this.sectionPositionBuffer, 0, this.count * 4);
        UnsafeUtil.memcpy(this.visibleSections, this.count, address);
        UploadStream.INSTANCE.commit();
        this.changed = false;
    }

    public void reset() {
        this.count = 0;
        this.changed = true;
    }

    public void put(long pos) {
        pos = IBoundStore.transformBeforeStore(pos);
        this.visibleSections[this.count++] = (int) pos;
        this.visibleSections[this.count++] = (int) (pos >>> 32);

        if (this.count >= this.visibleSections.length - 2) {
            this.visibleSections = Arrays.copyOf(this.visibleSections, (int) (this.visibleSections.length * 1.25));
        }
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
        return this.count >> 1;
    }
}
