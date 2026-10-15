package dev.eiriksb.stad.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.eiriksb.stad.citizen.CitizenEntity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.HashMap;
import java.util.Map;

/**
 * A citizen's face, drawn over the front of their head from their face sheet ({@link CitizenSkins}): their nose and
 * marks, their eyes (looking at you when you're near and in front of them, else ahead; blinking now and then; half shut
 * when sleepy or looking down), their brows (raised, cross or sad with how they feel) and their mouth (speaking:
 * {@link LipSync}; else smiling, frowning or still with how they feel). The face is laid out on the head's front at
 * twice the skin's resolution, 16 by 16. Render thread.
 */
public final class CitizenFace extends RenderLayer<CitizenEntity, PlayerModel<CitizenEntity>> {
    /** where each piece goes on the face (16x16) */
    private static final int LEFT_EYE_X = 1, RIGHT_EYE_X = 9, EYE_Y = 7, BROW_Y = 4, MOUTH_X = 4, MOUTH_Y = 12;
    /** how far away the eyes still follow you (blocks), and the angle off straight ahead they glance at */
    private static final double FOLLOW = 10, GLANCE = 10;

    private static final class State {
        long blinkAt, blinkUntil;
        int seen;
    }

    private final Map<Integer, State> states = new HashMap<>();
    private int frames;

    public CitizenFace(RenderLayerParent<CitizenEntity, PlayerModel<CitizenEntity>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, CitizenEntity citizen, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        if (citizen.isInvisible()) {
            return;
        }
        CitizenSkins.Textures t = CitizenSkins.get(citizen.shownLooks(), citizen.isFemale(), citizen.shownJob(), citizen.isBaby());
        if (t.face() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        State st = states.computeIfAbsent(citizen.getId(), k -> new State());
        st.seen = frames;
        if (++frames % 600 == 0) {
            states.values().removeIf(s -> frames - s.seen > 600);
        }

        pose.pushPose();
        if (getParentModel().young) { // as the model draws a child's head
            pose.scale(0.75f, 0.75f, 0.75f);
            pose.translate(0, 1.0f, 0);
        }
        getParentModel().head.translateAndRotate(pose);

        // where you are, as seen from their head: the camera is at the origin of the pose's space
        Vector4f cam = new Matrix4f(pose.last().pose()).invert().transform(new Vector4f(0, 0, 0, 1));
        double dx = cam.x, dy = cam.y + 4.5 / 16, dz = cam.z + 4 / 16.0;
        int lookX = 0;
        boolean down = false;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        String feeling = LipSync.feeling(citizen.getUUID());
        if (dz < 0 && dist < FOLLOW && !citizen.isSleeping()) {
            double yaw = Math.toDegrees(Math.atan2(dx, -dz)), pitch = Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            if (Math.abs(yaw) < 75) {
                lookX = yaw > GLANCE ? 1 : yaw < -GLANCE ? -1 : 0;
                down = pitch > 20;
            }
        }
        // blinking: every few seconds, for a moment
        if (now > st.blinkUntil && now > st.blinkAt) {
            st.blinkAt = now + 2000 + citizen.getRandom().nextInt(4000);
            st.blinkUntil = st.blinkAt + 130;
        }
        boolean shut = citizen.isSleeping() || now >= st.blinkAt && now < st.blinkUntil;
        boolean half = down || feeling.equals("sleepy") || feeling.equals("flirty") && (now / 1500) % 3 == 0;

        int brow = switch (feeling) {
            case "surprised", "scared", "excited", "confused" -> CitizenSkins.BROW_RAISED;
            case "angry", "annoyed" -> CitizenSkins.BROW_CROSS;
            case "sad" -> CitizenSkins.BROW_SAD;
            default -> CitizenSkins.BROW_NEUTRAL;
        };
        int mouth = LipSync.now(citizen.getUUID());
        if (mouth < 0) {
            mouth = switch (feeling) {
                case "happy", "excited", "flirty" -> LipSync.SMILE;
                case "laugh" -> LipSync.GRIN;
                case "sad", "angry" -> LipSync.FROWN;
                case "annoyed" -> LipSync.PRESSED;
                case "surprised", "scared" -> LipSync.OO;
                default -> citizen.isSleeping() ? LipSync.SMALL : LipSync.REST;
            };
        }

        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucent(t.face()));
        int overlay = LivingEntityRenderer.getOverlayCoords(citizen, 0);
        int eye = shut ? CitizenSkins.EYE_SHUT : CitizenSkins.eyeFrame(half, down, lookX);
        int otherEye = shut ? CitizenSkins.EYE_SHUT : CitizenSkins.eyeFrame(half, down, -lookX); // drawn mirrored
        quad(vc, pose, light, overlay, 0, 0, 16, 16, 0, 0, false, 0.010f);
        quad(vc, pose, light, overlay, LEFT_EYE_X, EYE_Y, CitizenSkins.EYE_W, CitizenSkins.EYE_H,
                CitizenSkins.EYES_X + eye * CitizenSkins.EYE_W, 0, false, 0.015f);
        quad(vc, pose, light, overlay, RIGHT_EYE_X, EYE_Y, CitizenSkins.EYE_W, CitizenSkins.EYE_H,
                CitizenSkins.EYES_X + otherEye * CitizenSkins.EYE_W, 0, true, 0.015f);
        quad(vc, pose, light, overlay, LEFT_EYE_X, BROW_Y, CitizenSkins.BROW_W, CitizenSkins.BROW_H,
                CitizenSkins.BROWS_X + brow * CitizenSkins.BROW_W, CitizenSkins.BROWS_Y, false, 0.020f);
        quad(vc, pose, light, overlay, RIGHT_EYE_X, BROW_Y, CitizenSkins.BROW_W, CitizenSkins.BROW_H,
                CitizenSkins.BROWS_X + brow * CitizenSkins.BROW_W, CitizenSkins.BROWS_Y, true, 0.020f);
        quad(vc, pose, light, overlay, MOUTH_X, MOUTH_Y, CitizenSkins.MOUTH_W, CitizenSkins.MOUTH_H,
                CitizenSkins.MOUTHS_X + mouth * CitizenSkins.MOUTH_W, CitizenSkins.MOUTHS_Y, false, 0.020f);
        pose.popPose();
    }

    /**
     * A piece of the face sheet ({@code u}, {@code v}, w by h) on the face at ({@code x}, {@code y}), in face pixels
     * (half a skin pixel), {@code lift} in front of the head (in skin pixels).
     */
    private static void quad(VertexConsumer vc, PoseStack pose, int light, int overlay, int x, int y, int w, int h, int u, int v, boolean mirror,
                             float lift) {
        PoseStack.Pose p = pose.last();
        // the head's front is at z = -4 (skin pixels), from x -4 (the texture's left) to 4, y -8 (top) to 0
        float x0 = (-4 + x / 2f) / 16, x1 = (-4 + (x + w) / 2f) / 16, y0 = (-8 + y / 2f) / 16, y1 = (-8 + (y + h) / 2f) / 16;
        float z = (-4 - lift) / 16;
        float u0 = (float) u / CitizenSkins.SHEET_W, u1 = (float) (u + w) / CitizenSkins.SHEET_W;
        float v0 = (float) v / CitizenSkins.SHEET_H, v1 = (float) (v + h) / CitizenSkins.SHEET_H;
        if (mirror) {
            float s = u0;
            u0 = u1;
            u1 = s;
        }
        vertex(vc, p, x1, y0, z, u1, v0, light, overlay);
        vertex(vc, p, x0, y0, z, u0, v0, light, overlay);
        vertex(vc, p, x0, y1, z, u0, v1, light, overlay);
        vertex(vc, p, x1, y1, z, u1, v1, light, overlay);
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose p, float x, float y, float z, float u, float v, int light, int overlay) {
        vc.addVertex(p, x, y, z).setColor(255, 255, 255, 255).setUv(u, v).setOverlay(overlay).setLight(light).setNormal(p, 0, 0, -1);
    }
}
