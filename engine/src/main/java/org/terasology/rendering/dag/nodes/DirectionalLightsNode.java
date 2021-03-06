/*
 * Copyright 2016 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.rendering.dag.nodes;

import org.lwjgl.opengl.GL13;
import org.terasology.assets.ResourceUrn;
import org.terasology.math.geom.Vector3f;
import org.terasology.monitoring.PerformanceMonitor;
import org.terasology.registry.In;
import org.terasology.rendering.assets.material.Material;
import org.terasology.rendering.backdrop.BackdropProvider;
import org.terasology.rendering.cameras.Camera;
import org.terasology.rendering.dag.AbstractNode;
import org.terasology.rendering.dag.stateChanges.DisableDepthTest;
import org.terasology.rendering.logic.LightComponent;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_COLOR;
import static org.terasology.rendering.opengl.DefaultDynamicFBOs.READ_ONLY_GBUFFER;
import static org.terasology.rendering.opengl.DefaultDynamicFBOs.WRITE_ONLY_GBUFFER;
import org.terasology.rendering.opengl.FBO;
import org.terasology.rendering.opengl.FBOConfig;
import static org.terasology.rendering.opengl.ScalingFactors.FULL_SCALE;
import org.terasology.rendering.opengl.fbms.DisplayResolutionDependentFBOs;
import org.terasology.rendering.world.WorldRenderer;
import static org.terasology.rendering.opengl.OpenGLUtils.bindDisplay;
import static org.terasology.rendering.opengl.OpenGLUtils.renderFullscreenQuad;
import static org.terasology.rendering.opengl.OpenGLUtils.setViewportToSizeOf;

/**
 * TODO: Break this node into several nodes
 * TODO: For doing that worldRenderer.renderLightComponent must be eliminated somehow
 */
public class DirectionalLightsNode extends AbstractNode {
    private static final ResourceUrn REFRACTIVE_REFLECTIVE = new ResourceUrn("engine:sceneReflectiveRefractive");

    @In
    private BackdropProvider backdropProvider;

    @In
    private WorldRenderer worldRenderer;

    @In
    private DisplayResolutionDependentFBOs displayResolutionDependentFBOs;

    // TODO: Review this? (What are we doing with a component not attached to an entity?)
    private LightComponent mainDirectionalLight = new LightComponent();

    private Camera playerCamera;

    private Material lightGeometryShader;
    private Material lightBufferPass;

    private FBO sceneReflectiveRefractive;

    @Override
    public void initialise() {
        playerCamera = worldRenderer.getActiveCamera();
        lightGeometryShader = worldRenderer.getMaterial("engine:prog.lightGeometryPass");
        lightBufferPass = worldRenderer.getMaterial("engine:prog.lightBufferPass");
        requiresFBO(new FBOConfig(REFRACTIVE_REFLECTIVE, FULL_SCALE, FBO.Type.HDR).useNormalBuffer(), displayResolutionDependentFBOs);

        addDesiredStateChange(new DisableDepthTest());

        initMainDirectionalLight();
    }

    // TODO: one day the main light (sun/moon) should be just another light in the scene.
    private void initMainDirectionalLight() {
        mainDirectionalLight.lightType = LightComponent.LightType.DIRECTIONAL;
        mainDirectionalLight.lightAmbientIntensity = 0.75f;
        mainDirectionalLight.lightDiffuseIntensity = 0.75f;
        mainDirectionalLight.lightSpecularPower = 100f;
    }

    @Override
    public void process() {
        PerformanceMonitor.startActivity("rendering/mainlight");
        READ_ONLY_GBUFFER.bind();
        READ_ONLY_GBUFFER.setRenderBufferMask(false, false, true); // Only write to the light buffer

        glDisable(GL_DEPTH_TEST);

        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_COLOR);

        Vector3f sunlightWorldPosition = new Vector3f(backdropProvider.getSunDirection(true));
        sunlightWorldPosition.scale(50000f);
        sunlightWorldPosition.add(playerCamera.getPosition());
        // TODO: find a more elegant way
        // TODO: iterating over RenderSystems for rendering multiple lights
        worldRenderer.renderLightComponent(mainDirectionalLight, sunlightWorldPosition, lightGeometryShader, false);

        // TODO: Investigate these might be redundant
        glDisable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glEnable(GL_DEPTH_TEST);

        READ_ONLY_GBUFFER.setRenderBufferMask(true, true, true);
        bindDisplay();

        applyLightBufferPass();
        PerformanceMonitor.endActivity();
    }

    /**
     * Part of the deferred lighting technique, this method applies lighting through screen-space
     * calculations to the previously flat-lit world rendering stored in the primary FBO.   // TODO: rename sceneOpaque* FBOs to primaryA/B
     * <p>
     * See http://en.wikipedia.org/wiki/Deferred_shading as a starting point.
     */
    private void applyLightBufferPass() {
        int texId = 0;

        GL13.glActiveTexture(GL13.GL_TEXTURE0 + texId);
        READ_ONLY_GBUFFER.bindTexture();
        lightBufferPass.setInt("texSceneOpaque", texId++, true);

        GL13.glActiveTexture(GL13.GL_TEXTURE0 + texId);
        READ_ONLY_GBUFFER.bindDepthTexture();
        lightBufferPass.setInt("texSceneOpaqueDepth", texId++, true);

        GL13.glActiveTexture(GL13.GL_TEXTURE0 + texId);
        READ_ONLY_GBUFFER.bindNormalsTexture();
        lightBufferPass.setInt("texSceneOpaqueNormals", texId++, true);

        GL13.glActiveTexture(GL13.GL_TEXTURE0 + texId);
        READ_ONLY_GBUFFER.bindLightBufferTexture();
        lightBufferPass.setInt("texSceneOpaqueLightBuffer", texId, true);

        WRITE_ONLY_GBUFFER.bind();
        WRITE_ONLY_GBUFFER.setRenderBufferMask(true, true, true);

        setViewportToSizeOf(WRITE_ONLY_GBUFFER);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT); // TODO: verify this is necessary

        renderFullscreenQuad();

        bindDisplay();     // TODO: verify this is necessary
        setViewportToSizeOf(READ_ONLY_GBUFFER); // TODO: verify this is necessary

        sceneReflectiveRefractive = displayResolutionDependentFBOs.get(REFRACTIVE_REFLECTIVE);
        displayResolutionDependentFBOs.swapReadWriteBuffers();
        READ_ONLY_GBUFFER.attachDepthBufferTo(sceneReflectiveRefractive);
    }
}
