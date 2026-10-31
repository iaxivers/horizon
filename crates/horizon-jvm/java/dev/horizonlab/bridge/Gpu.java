package dev.horizonlab.bridge;

/**
 * The headless client's GPU in Rust (crates/horizon-jvm/src/gpu/): wgpu objects by id (0: none
 * made). Memory is passed as addresses of direct buffers (LWJGL's MemoryUtil.memAddress), live
 * for the call. The platform's HorizonGpuDevice is the renderpearl backend over these.
 */
public final class Gpu {
    private Gpu() {}

    /** Opens the device (once): its description as JSON, or null with no GPU. */
    public static native String open();

    /** A 2D texture (array) of a renderpearl GpuFormat (by ordinal). */
    public static native long texture(int format, int width, int height, int layers, int mips);

    public static native long view(long texture, int baseMip, int mips, boolean cube);

    /** Address modes and filters by ordinal; maxLod below 0: none. */
    public static native long sampler(int addressU, int addressV, int min, int mag, int anisotropy, double maxLod);

    public static native long buffer(long size);

    /** Forgets any object (a texture is destroyed). */
    public static native void release(long id);

    public static native void writeBuffer(long buffer, long offset, long address, long length);

    /** Reads back (waiting for the GPU) into the memory at address. */
    public static native void readBuffer(long buffer, long offset, long address, long length);

    public static native void copyBuffer(long source, long sourceOffset, long target, long targetOffset, long length);

    /** Rows of rowPixels pixels at address into the texture's region. */
    public static native void writeTexture(long texture, long address, long length, int rowPixels,
                                           int mip, int layer, int x, int y, int width, int height);

    /** The texture's region, tightly packed (waiting for the GPU). */
    public static native byte[] readTexture(long texture, int mip, int x, int y, int width, int height);

    public static native void copyTexture(long source, long target, int mip, int targetX, int targetY,
                                          int sourceX, int sourceY, int width, int height);

    /** Clears a whole mip level of a color texture and/or a depth texture (0: none). */
    public static native void clear(long color, double r, double g, double b, double a, long depth, double depthValue, int mip);

    /** A pipeline from its description (JSON) and SPIR-V (vertex may be 0): 0 when it fails. */
    public static native long pipeline(String description, long vertex, long vertexLength, long fragment, long fragmentLength);

    /** Starts recording a pass: color views (0: unused), their clears (4 each, NaN: load). */
    public static native void beginPass(long[] colors, double[] clears, long depth, double depthClear,
                                        int x, int y, int width, int height);

    public static native void endPass();

    public static native void setPipeline(long pipeline);

    /** kind 1: a buffer slice (a, b offset, c length); 2: a view a and sampler b; 0: unbound. */
    public static native void setUniform(int index, int kind, long a, long b, long c);

    public static native void setVertex(int slot, long buffer, long offset, long length);

    public static native void setIndex(long buffer, boolean ints);

    /** A scissor rectangle; width 0: none. */
    public static native void scissor(int x, int y, int width, int height);

    public static native void draw(int count, int instances, int first, int firstInstance);

    public static native void drawIndexed(int count, int instances, int first, int baseVertex, int firstInstance);
}
