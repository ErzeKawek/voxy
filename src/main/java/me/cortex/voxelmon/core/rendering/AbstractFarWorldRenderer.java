package me.cortex.voxelmon.core.rendering;

//NOTE: an idea on how to do it is so that any render section, we _keep_ aquired (yes this will be very memory intensive)
// could maybe tosomething else

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxelmon.core.gl.GlBuffer;
import me.cortex.voxelmon.core.gl.shader.Shader;
import me.cortex.voxelmon.core.gl.shader.ShaderType;
import me.cortex.voxelmon.core.rendering.building.BuiltSectionGeometry;
import me.cortex.voxelmon.core.rendering.util.UploadStream;
import me.cortex.voxelmon.core.world.other.BiomeColour;
import me.cortex.voxelmon.core.world.other.BlockStateColour;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import static org.lwjgl.opengl.ARBMultiDrawIndirect.glMultiDrawElementsIndirect;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_SHORT;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL42.GL_COMMAND_BARRIER_BIT;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL43.*;

//can make it so that register the key of the sections we have rendered, then when a section changes and is registered,
// dispatch an update to the render section data builder which then gets consumed by the render system and updates
// the rendered data

//Contains all the logic to render the world and manage gpu memory
// processes section load,unload,update render data and renders the world each frame


//Todo: tinker with having the compute shader where each thread is a position to render? maybe idk
public abstract class AbstractFarWorldRenderer {
    protected final int vao = glGenVertexArrays();

    protected final GlBuffer uniformBuffer;
    protected final GeometryManager geometry;

    private final ConcurrentLinkedDeque<BlockStateColour> stateUpdateQueue = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<BiomeColour> biomeUpdateQueue = new ConcurrentLinkedDeque<>();
    protected final GlBuffer stateDataBuffer;
    protected final GlBuffer biomeDataBuffer;
    protected final GlBuffer light = null;


    //Current camera base level section position
    protected int sx;
    protected int sy;
    protected int sz;

    protected FrustumIntersection frustum;

    public AbstractFarWorldRenderer() {
        this.uniformBuffer  = new GlBuffer(1024, 0);
        //TODO: make these both dynamically sized
        this.stateDataBuffer  = new GlBuffer((1<<16)*28, 0);//Capacity for 1<<16 entries
        this.biomeDataBuffer  = new GlBuffer(512*4*2, 0);//capacity for 1<<9 entries
        this.geometry = new GeometryManager();
    }

    protected abstract void setupVao();

    public void setupRender(Frustum frustum, Camera camera) {
        this.frustum = frustum.frustumIntersection;

        this.sx = camera.getBlockPos().getX() >> 5;
        this.sy = camera.getBlockPos().getY() >> 5;
        this.sz = camera.getBlockPos().getZ() >> 5;

        //TODO: move this to a render function that is only called
        // once per frame when using multi viewport mods
        //it shouldent matter if its called multiple times a frame however, as its synced with fences
        UploadStream.INSTANCE.tick();


        this.geometry.uploadResults();
        //Upload any block state changes
        while (!this.stateUpdateQueue.isEmpty()) {
            var stateUpdate = this.stateUpdateQueue.pop();
            long ptr = UploadStream.INSTANCE.upload(this.stateDataBuffer, stateUpdate.id()*28L, 28);
            MemoryUtil.memPutInt(ptr, stateUpdate.biomeTintMsk()); ptr+=4;
            for (int faceColour : stateUpdate.faceColours()) {
                MemoryUtil.memPutInt(ptr, faceColour); ptr+=4;
            }
        }
        //Upload any biome changes
        while (!this.biomeUpdateQueue.isEmpty()) {
            var biomeUpdate = this.biomeUpdateQueue.pop();
            long ptr = UploadStream.INSTANCE.upload(this.biomeDataBuffer, biomeUpdate.id()*8L, 8);
            MemoryUtil.memPutInt(ptr, biomeUpdate.foliageColour()); ptr+=4;
            MemoryUtil.memPutInt(ptr, biomeUpdate.waterColour()); ptr+=4;
        }
    }

    public abstract void renderFarAwayOpaque(MatrixStack stack, double cx, double cy, double cz);

    public void enqueueUpdate(BlockStateColour stateColour) {
        this.stateUpdateQueue.add(stateColour);
    }

    public void enqueueUpdate(BiomeColour biomeColour) {
        this.biomeUpdateQueue.add(biomeColour);
    }

    public void enqueueResult(BuiltSectionGeometry result) {
        this.geometry.enqueueResult(result);
    }

    public void addDebugData(List<String> debug) {

    }

    public void shutdown() {
        glDeleteVertexArrays(this.vao);
        this.geometry.free();
        this.uniformBuffer.free();
        this.stateDataBuffer.free();
        this.biomeDataBuffer.free();
    }
}