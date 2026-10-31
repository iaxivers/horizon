package dev.horizonlab.platform;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendEquation;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTextureView;
import com.mojang.renderpearl.backend.util.TransientBlockAllocator;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import dev.horizonlab.bridge.Gpu;
import dev.horizonlab.bridge.Natives;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.joml.Vector4fc;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * The headless client's GPU device: a renderpearl backend whose every call is Horizon's wgpu
 * (dev.horizonlab.bridge.Gpu, crates/horizon-jvm/src/gpu/). Vanilla's FrontendGpuDevice sits on
 * it as on its OpenGL and Vulkan backends (checks, GLSL to SPIR-V, reflection), so mods that
 * draw with the GPU (Xaero's minimap, into its picture-in-picture texture) draw for real; the
 * HUD reads what they drew back (HorizonGraphics: textures of this device become images).
 *
 * <p>Textures the headless client never uploaded (HorizonTextures' named views: a mod's PNG, a
 * dynamic texture) are uploaded here the first time a pass samples them.
 */
public final class HorizonGpu implements GpuDeviceBackend {
    private static final boolean DEBUG = "gpu".equals(System.getenv("HORIZON_JAVA_DEBUG"));

    private final DeviceInfo info;
    private final Encoder encoder = new Encoder();
    private static HorizonGpu device;

    private HorizonGpu(DeviceInfo info) {
        this.info = info;
    }

    /**
     * Makes RenderSystem's device (once, when there is a GPU): vanilla's frontend over this
     * backend. False with none (mods' GPU drawing then fails as before).
     */
    public static synchronized boolean install() {
        if (device != null) return true;
        if ("0".equals(System.getenv("HORIZON_JAVA_GPU"))) return false;
        String description;
        try {
            description = Gpu.open();
        } catch (Throwable t) {
            Natives.log("Horizon's client: no GPU: " + t);
            return false;
        }
        if (description == null) return false;
        var d = com.google.gson.JsonParser.parseString(description).getAsJsonObject();
        var limits = new DeviceLimits(16, d.get("uniformAlign").getAsInt(), d.get("maxTexture").getAsInt(),
                d.get("maxBuffer").getAsLong(), 0, Math.min(8, d.get("maxColorAttachments").getAsInt()), 1);
        var features = new DeviceFeatures(false, false, false, false, false, false, true, false);
        var info = new DeviceInfo(d.get("name").getAsString(), "Horizon", d.get("driver").getAsString(), true,
                "Horizon (wgpu " + d.get("backend").getAsString() + ")", 1.0F, limits, features, Set.of(),
                new HintsAndWorkarounds(false, false, false, false), DeviceType.OTHER);
        device = new HorizonGpu(info);
        try {
            com.mojang.blaze3d.systems.RenderSystem.initRenderer(
                    new com.mojang.renderpearl.frontend.FrontendGpuDevice(device));
        } catch (Throwable t) {
            Natives.log("Horizon's client: the GPU device failed to start: " + t);
            device = null;
            return false;
        }
        Natives.log("Horizon's client: GPU " + d.get("name").getAsString() + " (" + d.get("backend").getAsString() + ")");
        return true;
    }

    public static boolean installed() {
        return device != null;
    }

    /** The end of a client frame: transient memory is reused from here. */
    public static void endFrame() {
        if (device == null) return;
        // What Minecraft's frame does at its end: the per-frame uniform rings start over,
        // fenced tasks run, transient memory is reused.
        try {
            com.mojang.blaze3d.systems.RenderSystem.getDynamicUniforms().reset();
            com.mojang.blaze3d.systems.RenderSystem.executePendingTasks();
        } catch (Throwable t) {
            Natives.log("Horizon's client: the GPU frame's end failed: " + t);
        }
        device.encoder.submit();
    }

    // ---- Objects --------------------------------------------------------------------------

    static final class Texture extends BaseGpuTexture {
        final long id;
        private boolean closed;

        Texture(long id, int usage, String label, GpuFormat format, int width, int height, int layers, int mips) {
            super(usage, label, format, width, height, layers, mips);
            this.id = id;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                Gpu.release(id);
            }
        }
    }

    static final class View extends BaseGpuTextureView {
        final long id;
        private boolean closed;

        View(long id, GpuTexture texture, int base, int mips) {
            super(texture, base, mips);
            this.id = id;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                Gpu.release(id);
            }
        }
    }

    record Sampler(long id, AddressMode getAddressModeU, AddressMode getAddressModeV, FilterMode getMinFilter,
                   FilterMode getMagFilter, int getMaxAnisotropy, OptionalDouble getMaxLod) implements GpuSampler {
        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void close() {}
    }

    static class Buffer extends BaseGpuBuffer {
        final long id;
        private boolean closed;

        Buffer(long id, int usage, long size) {
            super(usage, size);
            this.id = id;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                Gpu.release(id);
            }
        }

        @Override
        public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
            ByteBuffer data = MemoryUtil.memCalloc((int) length);
            // The memory's own address: writers move the buffer's position.
            long address = MemoryUtil.memAddress0(data);
            if (read) Gpu.readBuffer(id, offset, address, length);
            return new GpuBufferSlice.MappedView(new GpuBufferSlice(this, offset, length), data, () -> {
                if (write) Gpu.writeBuffer(id, offset, address, length);
                MemoryUtil.memFree(data);
            });
        }
    }

    record Pipeline(long id, String name) implements BackendRenderPipeline {
        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void close() {}
    }

    // ---- The device -----------------------------------------------------------------------

    @Override
    public GpuSurfaceBackend createSurface(long window, BooleanSupplier vsync) {
        throw new UnsupportedOperationException("The headless client has no window to present to");
    }

    @Override
    public CommandEncoderBackend createCommandEncoder() {
        return encoder;
    }

    @Override
    public GpuSampler createSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int anisotropy,
                                    OptionalDouble maxLod) {
        long id = Gpu.sampler(u.ordinal(), v.ordinal(), min.ordinal(), mag.ordinal(), anisotropy, maxLod.orElse(-1.0));
        return new Sampler(id, u, v, min, mag, anisotropy, maxLod);
    }

    @Override
    public GpuTexture createTexture(String label, int usage, GpuFormat format, int width, int height, int layers, int mips) {
        long id = Gpu.texture(format.ordinal(), width, height, layers, mips);
        if (DEBUG) Natives.log("gpu: texture " + id + " " + label + " " + format + " " + width + "x" + height);
        return new Texture(id, usage, label, format, width, height, layers, mips);
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture, int base, int mips) {
        long id = texture instanceof Texture t ? Gpu.view(t.id, base, mips, (texture.usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0) : 0;
        return new View(id, texture, base, mips);
    }

    @Override
    public GpuBuffer createBuffer(Supplier<String> label, int usage, long size) {
        return new Buffer(Gpu.buffer(size), usage, size);
    }

    @Override
    public GpuBuffer createBuffer(Supplier<String> label, int usage, ByteBuffer data) {
        Buffer b = new Buffer(Gpu.buffer(data.remaining()), usage, data.remaining());
        write(b.id, 0, data);
        return b;
    }

    @Override
    public List<String> getLastDebugMessages() {
        return List.of();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return false;
    }

    @Override
    public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo create) {
        JsonObject d = new JsonObject();
        d.addProperty("name", create.name());
        JsonArray buffers = new JsonArray();
        for (var b : create.vertexBuffers()) {
            JsonObject o = new JsonObject();
            o.addProperty("slot", b.bufferSlot());
            o.addProperty("stride", b.stride());
            o.addProperty("step", b.stepRate());
            buffers.add(o);
        }
        d.add("vertexBuffers", buffers);
        JsonArray attribs = new JsonArray();
        for (var a : create.attribBindings()) {
            JsonObject o = new JsonObject();
            o.addProperty("buffer", a.bufferSlot());
            o.addProperty("location", a.location());
            o.addProperty("offset", a.offset());
            o.addProperty("format", a.format().name());
            attribs.add(o);
        }
        d.add("attribs", attribs);
        JsonArray uniforms = new JsonArray();
        for (BindGroupLayout.UniformDescription u : create.uniforms()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", u.name());
            o.addProperty("type", u.type().name());
            uniforms.add(o);
        }
        d.add("uniforms", uniforms);
        d.addProperty("push", create.pushConstantsSize());
        var depth = create.depthStencilState();
        if (depth != null) {
            JsonObject o = new JsonObject();
            o.addProperty("test", depth.depthTest().name());
            o.addProperty("write", depth.writeDepth());
            o.addProperty("biasScale", depth.depthBiasScaleFactor());
            o.addProperty("biasConstant", depth.depthBiasConstant());
            d.add("depth", o);
        }
        d.addProperty("cull", create.cull());
        d.addProperty("topology", create.primitiveTopology().name());
        JsonArray targets = new JsonArray();
        for (ColorTargetState t : create.colorTargetStates()) {
            if (t == null) {
                targets.add(com.google.gson.JsonNull.INSTANCE);
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("format", t.format().name());
            o.addProperty("mask", t.writeMask());
            t.blendFunction().ifPresent(f -> {
                JsonObject blend = new JsonObject();
                blend.add("color", equation(f.color()));
                blend.add("alpha", equation(f.alpha()));
                o.add("blend", blend);
            });
            targets.add(o);
        }
        d.add("targets", targets);
        ByteBuffer vertex = null, fragment = null;
        for (var s : create.shaders()) {
            if (s.module().type() == ShaderType.VERTEX) vertex = s.module().spv();
            else if (s.module().type() == ShaderType.FRAGMENT) fragment = s.module().spv();
        }
        if (fragment == null) return BackendRenderPipeline.Pending.NULL;
        dump(create.name(), "vert", vertex);
        dump(create.name(), "frag", fragment);
        long id = Gpu.pipeline(d.toString(),
                vertex == null ? 0 : MemoryUtil.memAddress(vertex), vertex == null ? 0 : vertex.remaining(),
                MemoryUtil.memAddress(fragment), fragment.remaining());
        if (DEBUG) Natives.log("gpu: pipeline " + create.name() + " -> " + id);
        if (id == 0) return BackendRenderPipeline.Pending.NULL;
        Pipeline pipeline = new Pipeline(id, create.name());
        return () -> pipeline;
    }

    /** HORIZON_JAVA_GPU_DUMP=dir: each pipeline's SPIR-V is written there (crates/horizon-jvm's tests read it). */
    private static void dump(String name, String stage, ByteBuffer spv) {
        String dir = System.getenv("HORIZON_JAVA_GPU_DUMP");
        if (dir == null || spv == null) return;
        try {
            byte[] bytes = new byte[spv.remaining()];
            spv.duplicate().get(bytes);
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(dir));
            java.nio.file.Files.write(java.nio.file.Path.of(dir, name.replaceAll("[^a-zA-Z0-9_.-]", "_") + "." + stage + ".spv"), bytes);
        } catch (java.io.IOException e) {
            Natives.log("gpu: dump failed: " + e);
        }
    }

    private static JsonObject equation(BlendEquation e) {
        JsonObject o = new JsonObject();
        o.addProperty("src", e.sourceFactor().name());
        o.addProperty("dst", e.destFactor().name());
        o.addProperty("op", e.op().name());
        return o;
    }

    @Override
    public void close() {}

    @Override
    public GpuQueryPool createTimestampQueryPool(int size) {
        return new GpuQueryPool() {
            @Override
            public int size() {
                return size;
            }

            @Override
            public OptionalLong getValue(int index) {
                return OptionalLong.empty();
            }

            @Override
            public OptionalLong[] getValues(int index, int count) {
                OptionalLong[] out = new OptionalLong[count];
                java.util.Arrays.fill(out, OptionalLong.empty());
                return out;
            }

            @Override
            public void close() {}
        };
    }

    @Override
    public long getTimestampCalibrationOffset() {
        return 0;
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        return info;
    }

    // ---- Memory ---------------------------------------------------------------------------

    /** Writes a buffer's remaining bytes (copied off the heap first when they are there). */
    static void write(long buffer, long offset, ByteBuffer data) {
        int n = data.remaining();
        if (n == 0) return;
        if (data.isDirect()) {
            Gpu.writeBuffer(buffer, offset, MemoryUtil.memAddress(data), n);
            return;
        }
        ByteBuffer copy = MemoryUtil.memAlloc(n);
        try {
            copy.put(data.duplicate()).flip();
            Gpu.writeBuffer(buffer, offset, MemoryUtil.memAddress(copy), n);
        } finally {
            MemoryUtil.memFree(copy);
        }
    }

    private static long id(GpuTexture t) {
        return t instanceof Texture x ? x.id : 0;
    }

    private static long id(GpuBuffer b) {
        return b instanceof Buffer x ? x.id : 0;
    }

    /** A transient block: a GPU buffer and its host copy. */
    record Block(Buffer buffer, long host, long size) implements TransientBlockAllocator.Allocator.Block {
        @Override
        public boolean suboptimal() {
            return false;
        }
    }

    /** A slice of a transient block, as renderpearl's transient buffers are (not mappable). */
    static final class TransientBuffer extends Buffer {
        TransientBuffer(Buffer block, int usage) {
            super(block.id, usage, block.size());
        }

        @Override
        public void close() {}
    }

    /**
     * Transient memory as renderpearl's OpenGL fallback has it: 512 KiB blocks of GPU buffer, each
     * with host memory written through on close; reused after each submit (queue writes land
     * after the work already submitted).
     */
    static final class Transient implements TransientMemory {
        private static final long BLOCK = 524288L;
        private final TransientBlockAllocator<TransientBlockAllocator.Allocator.CpuBlock> cpu =
                new TransientBlockAllocator<>(BLOCK, 16L, TransientBlockAllocator.Allocator.CpuBlock.memalloc());
        private final TransientBlockAllocator<Block> gpu = new TransientBlockAllocator<>(BLOCK, Long.highestOneBit(Long.MAX_VALUE),
                TransientBlockAllocator.Allocator.create(size -> {
                    long id = Gpu.buffer(size);
                    return new Block(new Buffer(id, 0xff, size), MemoryUtil.nmemAlloc(size), size);
                }, block -> {
                    block.buffer().close();
                    MemoryUtil.nmemFree(block.host());
                }));

        void rotate() {
            cpu.rotate().run();
            gpu.rotate().run();
        }

        @Override
        public ByteBuffer allocateCpu(long size, long alignment, long minimum, long elementSize) {
            var alloc = cpu.allocate(size, alignment, minimum, elementSize);
            return MemoryUtil.memByteBuffer(alloc.block().address() + alloc.offset(), (int) alloc.size());
        }

        @Override
        public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, int usage, long minimum, long elementSize) {
            var alloc = gpu.allocate(size, alignment, minimum, elementSize);
            var slice = new GpuBufferSlice(new TransientBuffer(alloc.block().buffer(), usage), alloc.offset(), alloc.size());
            long host = alloc.block().host() + alloc.offset();
            ByteBuffer data = MemoryUtil.memByteBuffer(host, (int) alloc.size());
            return new GpuBufferSlice.MappedView(slice, data,
                    () -> Gpu.writeBuffer(alloc.block().buffer().id, alloc.offset(), host, alloc.size()));
        }

        @Override
        public GpuBufferSlice allocateGpu(long size, long alignment, int usage, long minimum, long elementSize) {
            var alloc = gpu.allocate(size, alignment, minimum, elementSize);
            return new GpuBufferSlice(new TransientBuffer(alloc.block().buffer(), usage), alloc.offset(), alloc.size());
        }

        @Override
        public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, int usage, long minimum, long elementSize) {
            return allocateStaging(size, alignment, usage, minimum, elementSize);
        }

        @Override
        public GpuBufferSlice uploadStaging(List<ByteBuffer> data, long alignment, int usage, long minimum, long elementSize) {
            return uploadGpu(data, alignment, usage, minimum, elementSize);
        }

        @Override
        public GpuBufferSlice uploadGpu(List<ByteBuffer> data, long alignment, int usage, long minimum, long elementSize) {
            long total = 0;
            for (ByteBuffer b : data) {
                total += b.remaining();
                total = roundUp(total, alignment);
            }
            try (var mapped = allocateStaging(total, alignment, usage, minimum, elementSize)) {
                long offset = 0;
                for (ByteBuffer b : data) {
                    int n = (int) Math.min(mapped.slice().length() - offset, b.remaining());
                    mapped.data().put((int) offset, b, b.position(), n);
                    offset = roundUp(offset + b.remaining(), alignment);
                    if (offset >= mapped.slice().length()) break;
                }
                return mapped.slice();
            }
        }

        @Override
        public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, int usage) {
            return multiUploadGpu(data, alignment, usage);
        }

        @Override
        public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, int usage) {
            List<GpuBufferSlice> out = new ArrayList<>(data.size());
            for (ByteBuffer b : data) out.add(uploadGpu(List.of(b), alignment, usage));
            return out;
        }

        private static long roundUp(long value, long alignment) {
            return alignment <= 1 ? value : (value + alignment - 1) / alignment * alignment;
        }
    }

    // ---- Commands -------------------------------------------------------------------------

    final class Encoder implements CommandEncoderBackend {
        private final Transient transientMemory = new Transient();

        @Override
        public void submit() {
            transientMemory.rotate();
        }

        @Override
        public TransientMemory transientMemory() {
            return transientMemory;
        }

        @Override
        public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
            var colors = descriptor.colorAttachments();
            long[] views = new long[colors.size()];
            double[] clears = new double[colors.size() * 4];
            java.util.Arrays.fill(clears, Double.NaN);
            for (int i = 0; i < colors.size(); i++) {
                var a = colors.get(i);
                if (a == null) continue;
                views[i] = view(a.textureView());
                if (a.clearValue().isPresent()) {
                    Vector4fc c = a.clearValue().get();
                    clears[i * 4] = c.x();
                    clears[i * 4 + 1] = c.y();
                    clears[i * 4 + 2] = c.z();
                    clears[i * 4 + 3] = c.w();
                }
            }
            long depth = 0;
            double depthClear = Double.NaN;
            if (descriptor.depthAttachment() != null) {
                depth = view(descriptor.depthAttachment().textureView());
                if (descriptor.depthAttachment().clearValue().isPresent()) {
                    depthClear = descriptor.depthAttachment().clearValue().getAsDouble();
                }
            }
            var area = descriptor.renderArea();
            Gpu.beginPass(views, clears, depth, depthClear, area.x(), area.y(), area.width(), area.height());
            return new Pass();
        }

        @Override
        public void submitRenderPass() {
            Gpu.endPass();
        }

        @Override
        public void clearColorTexture(GpuTexture texture, Vector4fc c) {
            Gpu.clear(id(texture), c.x(), c.y(), c.z(), c.w(), 0, 0, 0);
        }

        @Override
        public void clearColorAndDepthTextures(GpuTexture color, Vector4fc c, GpuTexture depth, double d) {
            Gpu.clear(id(color), c.x(), c.y(), c.z(), c.w(), id(depth), d, 0);
        }

        /** A region's clear clears its whole mip level (no region clears in wgpu). */
        @Override
        public void clearColorAndDepthTextures(GpuTexture color, Vector4fc c, GpuTexture depth, double d,
                                               int x, int y, int width, int height, int mip) {
            Gpu.clear(id(color), c.x(), c.y(), c.z(), c.w(), id(depth), d, mip);
        }

        @Override
        public void clearDepthTexture(GpuTexture depth, double d) {
            Gpu.clear(0, 0, 0, 0, 0, id(depth), d, 0);
        }

        @Override
        public void writeToBuffer(GpuBufferSlice target, ByteBuffer data) {
            write(id(target.buffer()), target.offset(), data);
        }

        @Override
        public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
            Gpu.copyBuffer(id(source.buffer()), source.offset(), id(target.buffer()), target.offset(),
                    Math.min(source.length(), target.length()));
        }

        @Override
        public void writeToTexture(GpuTexture texture, ByteBuffer data, int mip, int layer, int x, int y, int width, int height) {
            ByteBuffer direct = data;
            boolean copied = false;
            int expand = texture.getFormat().componentCount() == 3 && texture.getFormat().componentType().byteSize() == 1 ? 1 : 0;
            if (!data.isDirect()) {
                direct = MemoryUtil.memAlloc(data.remaining());
                direct.put(data.duplicate()).flip();
                copied = true;
            }
            try {
                Gpu.writeTexture(id(texture), MemoryUtil.memAddress(direct), direct.remaining(), width, mip, layer, x, y, width, height);
            } finally {
                if (copied) MemoryUtil.memFree(direct);
            }
            if (expand != 0 && DEBUG) Natives.log("gpu: an RGB8 texture write (no such wgpu format)");
        }

        @Override
        public void copyBufferToTexture(GpuBufferSlice source, int sx, int sy, int sw, int sh, GpuTexture texture,
                                        int dx, int dy, int width, int height, int mip, int layer) {
            int block = texture.getFormat().blockSize();
            long start = source.offset() + ((long) sy * sw + sx) * block;
            long length = ((long) (height - 1) * sw + width) * block;
            ByteBuffer bytes = MemoryUtil.memAlloc((int) length);
            try {
                Gpu.readBuffer(id(source.buffer()), start, MemoryUtil.memAddress(bytes), length);
                Gpu.writeTexture(id(texture), MemoryUtil.memAddress(bytes), length, sw, mip, layer, dx, dy, width, height);
            } finally {
                MemoryUtil.memFree(bytes);
            }
        }

        @Override
        public void copyTextureToBuffer(GpuTexture texture, GpuBuffer buffer, long offset, Runnable done, int mip) {
            copyTextureToBuffer(texture, buffer, offset, done, mip, 0, 0, texture.getWidth(mip), texture.getHeight(mip));
        }

        @Override
        public void copyTextureToBuffer(GpuTexture texture, GpuBuffer buffer, long offset, Runnable done, int mip,
                                        int x, int y, int width, int height) {
            byte[] bytes = Gpu.readTexture(id(texture), mip, x, y, width, height);
            if (bytes != null && bytes.length > 0) {
                ByteBuffer direct = MemoryUtil.memAlloc(bytes.length);
                try {
                    direct.put(bytes).flip();
                    Gpu.writeBuffer(id(buffer), offset, MemoryUtil.memAddress(direct), bytes.length);
                } finally {
                    MemoryUtil.memFree(direct);
                }
            }
            done.run();
        }

        @Override
        public void copyTextureToTexture(GpuTexture source, GpuTexture target, int mip, int dx, int dy, int sx, int sy,
                                         int width, int height) {
            Gpu.copyTexture(id(source), id(target), mip, dx, dy, sx, sy, width, height);
        }

        @Override
        public GpuFence createFence() {
            // Every call here is ordered on the queue, and readbacks wait for it.
            return new GpuFence() {
                @Override
                public void close() {}

                @Override
                public boolean awaitCompletion(long timeout) {
                    return true;
                }
            };
        }

        @Override
        public void writeTimestamp(GpuQueryPool pool, int index) {}
    }

    final class Pass implements RenderPassBackend {
        @Override
        public void pushDebugGroup(Supplier<String> label) {}

        @Override
        public void popDebugGroup() {}

        @Override
        public void setPipeline(BackendRenderPipeline pipeline) {
            Gpu.setPipeline(pipeline instanceof Pipeline p ? p.id() : 0);
        }

        @Override
        public void setUniform(int index, Object value) {
            if (value instanceof GpuBufferSlice slice) {
                Gpu.setUniform(index, 1, id(slice.buffer()), slice.offset(), slice.length());
            } else if (value instanceof TextureViewAndSampler pair) {
                long sampler = pair.sampler() instanceof Sampler s ? s.id() : 0;
                Gpu.setUniform(index, 2, view(pair.view()), sampler, 0);
            } else {
                Gpu.setUniform(index, 0, 0, 0, 0);
            }
        }

        @Override
        public void pushConstants(ByteBuffer data) {
            if (DEBUG) Natives.log("gpu: push constants aren't supported");
        }

        @Override
        public void enableScissor(int x, int y, int width, int height) {
            Gpu.scissor(x, y, width, height);
        }

        @Override
        public void disableScissor() {
            Gpu.scissor(0, 0, 0, 0);
        }

        @Override
        public void setVertexBuffer(int slot, GpuBufferSlice slice) {
            if (slice != null) Gpu.setVertex(slot, id(slice.buffer()), slice.offset(), slice.length());
        }

        @Override
        public void setIndexBuffer(GpuBuffer buffer, IndexType type) {
            Gpu.setIndex(id(buffer), type == IndexType.INT);
        }

        @Override
        public void drawIndexed(int count, int instances, int first, int baseVertex, int firstInstance) {
            Gpu.drawIndexed(count, instances, first, baseVertex, firstInstance);
        }

        @Override
        public void multiDrawIndexed(IntBuffer parameters, int instances, int firstInstance, int draws) {
            // Interleaved (firstIndex, indexCount, vertexOffset) triples are what renderpearl's
            // GL backend reads; multi draws aren't offered (DeviceFeatures), so none come here.
        }

        @Override
        public void multiDrawIndexed(PointerBuffer firsts, IntBuffer counts, IntBuffer bases, int draws) {}

        @Override
        public void drawIndexedIndirect(GpuBufferSlice slice, int draws) {}

        @Override
        public void draw(int count, int instances, int first, int firstInstance) {
            Gpu.draw(count, instances, first, firstInstance);
        }

        @Override
        public void multiDraw(IntBuffer parameters, int instances, int draws, int stride) {}

        @Override
        public void multiDraw(IntBuffer firsts, IntBuffer counts, int draws) {}

        @Override
        public void drawIndirect(GpuBufferSlice slice, int draws) {}

        @Override
        public void writeTimestamp(GpuQueryPool pool, int index) {}
    }

    // ---- Textures the headless client never uploaded --------------------------------------

    /** Uploaded copies of named views (by name), with the version they hold. */
    private static final Map<String, Object[]> UPLOADED = new ConcurrentHashMap<>();
    private static View white;

    /** A view's id for a pass: this device's, else a named view's upload, else white. */
    static long view(GpuTextureView view) {
        if (view instanceof View v) return v.id;
        if (view instanceof HorizonTextures.View named) {
            View up = upload(named);
            if (up != null) return up.id;
        }
        return white().id;
    }

    private static View white() {
        if (white == null) {
            var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
            var t = device.createTexture("white", 5, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
            ByteBuffer px = MemoryUtil.memAlloc(4);
            px.putInt(0, -1);
            Gpu.writeTexture(id(t), MemoryUtil.memAddress(px), 4, 1, 0, 0, 0, 0, 1, 1);
            MemoryUtil.memFree(px);
            white = (View) device.createTextureView(t);
        }
        return white;
    }

    private static View upload(HorizonTextures.View named) {
        int version = HorizonTextures.version(named.name());
        Object[] have = UPLOADED.get(named.name());
        if (have != null && (int) have[1] == version) return (View) have[0];
        int[] size = new int[2];
        ByteBuffer rgba = HorizonTextures.rgba(named.name(), size);
        if (rgba == null) {
            UPLOADED.put(named.name(), new Object[] {null, version});
            return null;
        }
        try {
            View view = have != null ? (View) have[0] : null;
            if (view == null || view.texture().getWidth(0) != size[0] || view.texture().getHeight(0) != size[1]) {
                var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
                var t = device.createTexture(named.name(), 5, GpuFormat.RGBA8_UNORM, size[0], size[1], 1, 1);
                view = (View) device.createTextureView(t);
            }
            Gpu.writeTexture(((Texture) view.texture()).id, MemoryUtil.memAddress(rgba), rgba.remaining(), size[0], 0, 0, 0, 0,
                    size[0], size[1]);
            UPLOADED.put(named.name(), new Object[] {view, version});
            return view;
        } finally {
            MemoryUtil.memFree(rgba);
        }
    }

    /**
     * Whether a view is of a texture drawn into (a render target: a picture in picture), the
     * kind the HUD sends as an image; an atlas isn't (screens' sprites are Horizon's).
     */
    static boolean drawnInto(GpuTextureView view) {
        var t = view.texture();
        return t != null && (t.usage() & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0 && t.getWidth(0) <= 1024 && t.getHeight(0) <= 1024;
    }

    /**
     * The pixels of a texture of this device, as RGBA rows (row 0 first: renderpearl's rows,
     * which its GUI blits flip with their UVs), or null.
     */
    static byte[] pixels(GpuTextureView view) {
        if (!(view instanceof View v) || v.isClosed() || !(v.texture() instanceof Texture t)) return null;
        if (t.getFormat() != GpuFormat.RGBA8_UNORM) return null;
        int mip = v.baseMipLevel();
        return Gpu.readTexture(t.id, mip, 0, 0, t.getWidth(mip), t.getHeight(mip));
    }
}
