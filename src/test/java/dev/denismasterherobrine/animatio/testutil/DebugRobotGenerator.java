package dev.denismasterherobrine.animatio.testutil;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.joml.Quaternionf;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates the debug robot used by the dev mod: a box character skinned to a small skeleton, with idle, walk,
 * run, jump, fall, get_up, wave, blink, attack, hurt and death clips.
 * <p>
 * Run with {@code ./gradlew generateDebugRobot}.
 */
public final class DebugRobotGenerator {
    // node indices
    private static final int ROOT = 0, HIPS = 1, TORSO = 2, HEAD = 3, EYES = 4, ARM_R = 5, HAND_R = 6, ARM_L = 7, HAND_L = 8, LEG_R = 9, LEG_L = 10;
    private static final String[] BASE_NAMES = {"root", "hips", "torso", "head", "eyes", "arm_r", "hand_r", "arm_l", "hand_l", "leg_r", "leg_l"};
    private static final int[] BASE_PARENTS = {-1, ROOT, HIPS, TORSO, HEAD, TORSO, ARM_R, TORSO, ARM_L, HIPS, HIPS};
    private static final float[][] BASE_LOCAL = {
            {0, 0, 0}, {0, 0.75f, 0}, {0, 0.05f, 0}, {0, 0.7f, 0}, {0, 0.28f, 0.255f},
            {-0.33f, 0.62f, 0}, {0, -0.62f, 0}, {0.33f, 0.62f, 0}, {0, -0.62f, 0},
            {-0.13f, 0, 0}, {0.13f, 0, 0}};
    private static final float TAIL_SEGMENT = 0.025f;

    /** Skeleton: the base robot plus an optional tail chain behind the hips (a stand-in for big character rigs). */
    private final String[] names;
    private final int[] parents;
    private final float[][] local;
    private final int tailStart;
    private final int tailCount;

    public DebugRobotGenerator(int tailBones) {
        int n = BASE_NAMES.length + tailBones;
        names = new String[n];
        parents = new int[n];
        local = new float[n][];
        System.arraycopy(BASE_NAMES, 0, names, 0, BASE_NAMES.length);
        System.arraycopy(BASE_PARENTS, 0, parents, 0, BASE_PARENTS.length);
        System.arraycopy(BASE_LOCAL, 0, local, 0, BASE_LOCAL.length);
        tailStart = BASE_NAMES.length;
        tailCount = tailBones;
        for (int k = 0; k < tailBones; k++) {
            int i = tailStart + k;
            names[i] = "tail_" + k;
            parents[i] = k == 0 ? HIPS : i - 1;
            local[i] = k == 0 ? new float[]{0, 0f, -0.16f} : new float[]{0, 0, -TAIL_SEGMENT};
        }
    }

    // texture cells (4x4 grid of 4px cells in a 16x16 texture)
    private static final int[] COLORS = {0xFF3A6EA5, 0xFFC8CCD0, 0xFF55E0FF, 0xFF4A4F57, 0xFF2B3A55, 0xFFE0B040};
    private static final int BLUE = 0, GREY = 1, CYAN = 2, DARK = 3, NAVY = 4, GOLD = 5;

    private final List<Float> pos = new ArrayList<>(), nrm = new ArrayList<>(), uv = new ArrayList<>();
    private final List<Integer> joints = new ArrayList<>(), idx = new ArrayList<>(), eyeIdx = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "src/dev/resources/assets/animatio_dev/animatio/models/robot.glb");
        Files.createDirectories(out.getParent());
        Files.write(out, new DebugRobotGenerator(0).build());
        System.out.println("Wrote " + out.toAbsolutePath());
        if (args.length > 2) {
            // the same robot with a 48-bone animated tail: 59 bones, the size of a typical character rig
            Path heavy = Path.of(args[2]);
            Files.write(heavy, new DebugRobotGenerator(48).build());
            System.out.println("Wrote " + heavy.toAbsolutePath());
        }
        if (args.length > 1) {
            // a 4x4x4 structure with a stone floor for the dev GameTests
            Path structure = Path.of(args[1]);
            Files.createDirectories(structure.getParent());
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            tag.putInt("DataVersion", 3955);
            net.minecraft.nbt.ListTag size = new net.minecraft.nbt.ListTag();
            for (int i = 0; i < 3; i++) size.add(net.minecraft.nbt.IntTag.valueOf(4));
            tag.put("size", size);
            net.minecraft.nbt.ListTag palette = new net.minecraft.nbt.ListTag();
            net.minecraft.nbt.CompoundTag stone = new net.minecraft.nbt.CompoundTag();
            stone.putString("Name", "minecraft:stone");
            palette.add(stone);
            tag.put("palette", palette);
            net.minecraft.nbt.ListTag blocks = new net.minecraft.nbt.ListTag();
            for (int x = 0; x < 4; x++) {
                for (int z = 0; z < 4; z++) {
                    net.minecraft.nbt.CompoundTag block = new net.minecraft.nbt.CompoundTag();
                    net.minecraft.nbt.ListTag p = new net.minecraft.nbt.ListTag();
                    p.add(net.minecraft.nbt.IntTag.valueOf(x));
                    p.add(net.minecraft.nbt.IntTag.valueOf(0));
                    p.add(net.minecraft.nbt.IntTag.valueOf(z));
                    block.put("pos", p);
                    block.putInt("state", 0);
                    blocks.add(block);
                }
            }
            tag.put("blocks", blocks);
            tag.put("entities", new net.minecraft.nbt.ListTag());
            net.minecraft.nbt.NbtIo.writeCompressed(tag, structure);
            System.out.println("Wrote " + structure.toAbsolutePath());
        }
    }

    private float[] world(int node) {
        float x = 0, y = 0, z = 0;
        for (int n = node; n >= 0; n = parents[n]) {
            x += local[n][0];
            y += local[n][1];
            z += local[n][2];
        }
        return new float[]{x, y, z};
    }

    /** Axis-aligned box in model space, skinned fully to {@code bone}. */
    private void box(int bone, float x0, float y0, float z0, float x1, float y1, float z1, int color, boolean eyes) {
        float u = ((color % 4) * 4 + 2) / 16f, v = ((color / 4) * 4 + 2) / 16f;
        float[][] faces = {
                {0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
        for (float[] n : faces) {
            float[][] corners = new float[4][];
            if (n[2] != 0) {
                float z = n[2] > 0 ? z1 : z0;
                corners = n[2] > 0 ? new float[][]{{x0, y0, z}, {x1, y0, z}, {x1, y1, z}, {x0, y1, z}}
                        : new float[][]{{x1, y0, z}, {x0, y0, z}, {x0, y1, z}, {x1, y1, z}};
            } else if (n[0] != 0) {
                float x = n[0] > 0 ? x1 : x0;
                corners = n[0] > 0 ? new float[][]{{x, y0, z1}, {x, y0, z0}, {x, y1, z0}, {x, y1, z1}}
                        : new float[][]{{x, y0, z0}, {x, y0, z1}, {x, y1, z1}, {x, y1, z0}};
            } else {
                float y = n[1] > 0 ? y1 : y0;
                corners = n[1] > 0 ? new float[][]{{x0, y, z1}, {x1, y, z1}, {x1, y, z0}, {x0, y, z0}}
                        : new float[][]{{x0, y, z0}, {x1, y, z0}, {x1, y, z1}, {x0, y, z1}};
            }
            int base = pos.size() / 3;
            for (float[] c : corners) {
                pos.add(c[0]);
                pos.add(c[1]);
                pos.add(c[2]);
                nrm.add(n[0]);
                nrm.add(n[1]);
                nrm.add(n[2]);
                uv.add(u);
                uv.add(v);
                joints.add(bone);
            }
            List<Integer> target = eyes ? eyeIdx : idx;
            target.add(base);
            target.add(base + 1);
            target.add(base + 2);
            target.add(base);
            target.add(base + 2);
            target.add(base + 3);
        }
    }

    private byte[] build() throws IOException {
        float[] hips = world(HIPS), torso = world(TORSO), head = world(HEAD), eyes = world(EYES);
        float[] armR = world(ARM_R), armL = world(ARM_L), legR = world(LEG_R), legL = world(LEG_L);
        box(HIPS, -0.27f, hips[1] - 0.05f, -0.15f, 0.27f, hips[1] + 0.08f, 0.15f, DARK, false);
        box(TORSO, -0.27f, torso[1] + 0.08f, -0.16f, 0.27f, torso[1] + 0.7f, 0.16f, BLUE, false);
        box(TORSO, -0.08f, torso[1] + 0.35f, 0.16f, 0.08f, torso[1] + 0.55f, 0.19f, GOLD, false);
        box(HEAD, -0.25f, head[1], -0.25f, 0.25f, head[1] + 0.5f, 0.25f, GREY, false);
        box(HEAD, -0.03f, head[1] + 0.5f, -0.03f, 0.03f, head[1] + 0.68f, 0.03f, DARK, false);
        box(EYES, -0.16f, eyes[1] - 0.04f, eyes[2] - 0.01f, 0.16f, eyes[1] + 0.04f, eyes[2] + 0.01f, CYAN, true);
        box(ARM_R, armR[0] - 0.08f, armR[1] - 0.62f, -0.08f, armR[0] + 0.08f, armR[1] + 0.04f, 0.08f, DARK, false);
        box(ARM_L, armL[0] - 0.08f, armL[1] - 0.62f, -0.08f, armL[0] + 0.08f, armL[1] + 0.04f, 0.08f, DARK, false);
        box(LEG_R, legR[0] - 0.1f, 0f, -0.1f, legR[0] + 0.1f, legR[1], 0.1f, NAVY, false);
        box(LEG_L, legL[0] - 0.1f, 0f, -0.1f, legL[0] + 0.1f, legL[1], 0.1f, NAVY, false);
        for (int k = 0; k < tailCount; k++) {
            float[] w = world(tailStart + k);
            float half = 0.05f - 0.035f * k / Math.max(1, tailCount - 1);
            box(tailStart + k, -half, w[1] - half, w[2] - TAIL_SEGMENT, half, w[1] + half, w[2], k % 2 == 0 ? DARK : GOLD, false);
        }

        GlbBuilder b = new GlbBuilder();
        JsonObject json = new JsonObject();

        JsonArray nodes = new JsonArray();
        for (int i = 0; i < names.length; i++) {
            JsonObject n = new JsonObject();
            n.addProperty("name", names[i]);
            n.add("translation", floats(local[i]));
            JsonArray children = new JsonArray();
            for (int c = 0; c < names.length; c++) if (parents[c] == i) children.add(c);
            if (!children.isEmpty()) n.add("children", children);
            nodes.add(n);
        }
        JsonObject meshNode = new JsonObject();
        meshNode.addProperty("name", "robot_mesh");
        meshNode.addProperty("mesh", 0);
        meshNode.addProperty("skin", 0);
        nodes.add(meshNode);
        json.add("nodes", nodes);
        JsonObject scene = new JsonObject();
        JsonArray sceneNodes = new JsonArray();
        sceneNodes.add(ROOT);
        sceneNodes.add(names.length);
        scene.add("nodes", sceneNodes);
        JsonArray scenes = new JsonArray();
        scenes.add(scene);
        json.add("scenes", scenes);
        json.addProperty("scene", 0);

        // skin: every node is a joint; inverse bind = translation by -world position
        float[] ibm = new float[names.length * 16];
        JsonArray jointList = new JsonArray();
        for (int i = 0; i < names.length; i++) {
            float[] w = world(i);
            float[] m = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, -w[0], -w[1], -w[2], 1};
            System.arraycopy(m, 0, ibm, i * 16, 16);
            jointList.add(i);
        }
        JsonObject skin = new JsonObject();
        skin.add("joints", jointList);
        skin.addProperty("inverseBindMatrices", b.floats("MAT4", ibm));
        JsonArray skins = new JsonArray();
        skins.add(skin);
        json.add("skins", skins);

        int vertexCount = pos.size() / 3;
        int[] jointData = new int[vertexCount * 4];
        float[] weightData = new float[vertexCount * 4];
        for (int v = 0; v < vertexCount; v++) {
            jointData[v * 4] = joints.get(v);
            weightData[v * 4] = 1f;
        }
        int aPos = b.floats("VEC3", toArray(pos));
        int aNrm = b.floats("VEC3", toArray(nrm));
        int aUv = b.floats("VEC2", toArray(uv));
        int aJoints = b.ubytes("VEC4", false, jointData);
        int aWeights = b.floats("VEC4", weightData);
        JsonArray primitives = new JsonArray();
        primitives.add(primitive(b, aPos, aNrm, aUv, aJoints, aWeights, idx, 0));
        primitives.add(primitive(b, aPos, aNrm, aUv, aJoints, aWeights, eyeIdx, 1));
        JsonObject mesh = new JsonObject();
        mesh.addProperty("name", "robot");
        mesh.add("primitives", primitives);
        JsonArray meshes = new JsonArray();
        meshes.add(mesh);
        json.add("meshes", meshes);

        int imageView = b.bytesView(texture());
        JsonObject image = new JsonObject();
        image.addProperty("bufferView", imageView);
        image.addProperty("mimeType", "image/png");
        JsonArray images = new JsonArray();
        images.add(image);
        json.add("images", images);
        JsonObject texture = new JsonObject();
        texture.addProperty("source", 0);
        JsonArray textures = new JsonArray();
        textures.add(texture);
        json.add("textures", textures);
        JsonArray materials = new JsonArray();
        materials.add(material("body", false));
        materials.add(material("eyes", true));
        json.add("materials", materials);

        JsonArray anims = new JsonArray();
        anims.add(idle(b));
        anims.add(walk(b, "walk", 1.0f, 30, 25, 0.03f, 0));
        anims.add(walk(b, "run", 0.6f, 55, 45, 0.06f, 12));
        anims.add(jump(b));
        anims.add(fall(b));
        anims.add(getUp(b));
        anims.add(wave(b));
        anims.add(blink(b));
        anims.add(attack(b));
        anims.add(hurt(b));
        anims.add(death(b));
        json.add("animations", anims);
        return b.build(json);
    }

    // ------------------------------------------------------------------ clips

    private final class Anim {
        final String name;
        final JsonArray samplers = new JsonArray(), channels = new JsonArray();
        final GlbBuilder b;
        float duration;

        Anim(GlbBuilder b, String name) {
            this.b = b;
            this.name = name;
        }

        Anim rot(int node, float[] times, float[][] eulerDeg) {
            float[] values = new float[times.length * 4];
            for (int i = 0; i < times.length; i++) {
                Quaternionf q = new Quaternionf().rotateZYX((float) Math.toRadians(eulerDeg[i][2]), (float) Math.toRadians(eulerDeg[i][1]),
                        (float) Math.toRadians(eulerDeg[i][0]));
                values[i * 4] = q.x;
                values[i * 4 + 1] = q.y;
                values[i * 4 + 2] = q.z;
                values[i * 4 + 3] = q.w;
            }
            return channel(node, "rotation", "VEC4", times, values);
        }

        Anim trans(int node, float[] times, float[][] xyz) {
            float[] values = new float[times.length * 3];
            for (int i = 0; i < times.length; i++) System.arraycopy(xyz[i], 0, values, i * 3, 3);
            return channel(node, "translation", "VEC3", times, values);
        }

        Anim scale(int node, float[] times, float[][] xyz) {
            float[] values = new float[times.length * 3];
            for (int i = 0; i < times.length; i++) System.arraycopy(xyz[i], 0, values, i * 3, 3);
            return channel(node, "scale", "VEC3", times, values);
        }

        private Anim channel(int node, String path, String type, float[] times, float[] values) {
            duration = Math.max(duration, times[times.length - 1]);
            JsonObject sampler = new JsonObject();
            sampler.addProperty("input", b.floats("SCALAR", times));
            sampler.addProperty("output", b.floats(type, values));
            sampler.addProperty("interpolation", "LINEAR");
            samplers.add(sampler);
            JsonObject target = new JsonObject();
            target.addProperty("node", node);
            target.addProperty("path", path);
            JsonObject ch = new JsonObject();
            ch.addProperty("sampler", samplers.size() - 1);
            ch.add("target", target);
            channels.add(ch);
            return this;
        }

        JsonObject json() {
            if (tailCount > 0 && !name.equals("blink")) {
                // every body clip sways the tail: a travelling wave down the chain, one cycle per clip
                float d = Math.max(duration, 0.2f);
                int keys = 9;
                float[] times = new float[keys];
                for (int i = 0; i < keys; i++) times[i] = d * i / (keys - 1);
                for (int k = 0; k < tailCount; k++) {
                    float[][] angles = new float[keys][];
                    for (int i = 0; i < keys; i++) {
                        double phase = 2 * Math.PI * i / (keys - 1) + k * 0.35;
                        angles[i] = e((float) (4 * Math.cos(phase)), (float) (9 * Math.sin(phase)), 0);
                    }
                    rot(tailStart + k, times, angles);
                }
            }
            JsonObject a = new JsonObject();
            a.addProperty("name", name);
            a.add("samplers", samplers);
            a.add("channels", channels);
            return a;
        }
    }

    private static float[] t(float... t) {
        return t;
    }

    private static float[][] v(float[]... v) {
        return v;
    }

    private static float[] e(float x, float y, float z) {
        return new float[]{x, y, z};
    }

    private static float[] hipsAt(float dy) {
        return new float[]{0, 0.75f + dy, 0};
    }

    private JsonObject idle(GlbBuilder b) {
        return new Anim(b, "idle")
                .trans(HIPS, t(0, 1, 2), v(hipsAt(0), hipsAt(-0.015f), hipsAt(0)))
                .rot(HEAD, t(0, 1, 2), v(e(0, -4, 0), e(2, 4, 0), e(0, -4, 0)))
                .rot(ARM_R, t(0, 1, 2), v(e(0, 0, -3), e(0, 0, -5), e(0, 0, -3)))
                .rot(ARM_L, t(0, 1, 2), v(e(0, 0, 3), e(0, 0, 5), e(0, 0, 3)))
                .json();
    }

    private JsonObject walk(GlbBuilder b, String name, float d, float leg, float arm, float bob, float lean) {
        float q = d / 4;
        float[] times = t(0, q, 2 * q, 3 * q, d);
        return new Anim(b, name)
                .rot(LEG_R, times, v(e(leg, 0, 0), e(0, 0, 0), e(-leg, 0, 0), e(0, 0, 0), e(leg, 0, 0)))
                .rot(LEG_L, times, v(e(-leg, 0, 0), e(0, 0, 0), e(leg, 0, 0), e(0, 0, 0), e(-leg, 0, 0)))
                .rot(ARM_R, times, v(e(-arm, 0, -4), e(0, 0, -4), e(arm, 0, -4), e(0, 0, -4), e(-arm, 0, -4)))
                .rot(ARM_L, times, v(e(arm, 0, 4), e(0, 0, 4), e(-arm, 0, 4), e(0, 0, 4), e(arm, 0, 4)))
                .trans(HIPS, times, v(hipsAt(0), hipsAt(bob), hipsAt(0), hipsAt(bob), hipsAt(0)))
                .rot(TORSO, t(0, d), v(e(lean, 0, 0), e(lean, 0, 0)))
                .json();
    }

    private JsonObject jump(GlbBuilder b) {
        return new Anim(b, "jump")
                .trans(HIPS, t(0, 0.15f, 0.3f, 0.6f), v(hipsAt(0), hipsAt(-0.18f), hipsAt(0.05f), hipsAt(0.02f)))
                .rot(LEG_R, t(0, 0.15f, 0.3f), v(e(0, 0, 0), e(-35, 0, 0), e(10, 0, 0)))
                .rot(LEG_L, t(0, 0.15f, 0.3f), v(e(0, 0, 0), e(-35, 0, 0), e(10, 0, 0)))
                .rot(ARM_R, t(0, 0.15f, 0.35f), v(e(0, 0, -4), e(30, 0, -10), e(-160, 0, -10)))
                .rot(ARM_L, t(0, 0.15f, 0.35f), v(e(0, 0, 4), e(30, 0, 10), e(-160, 0, 10)))
                .json();
    }

    private JsonObject fall(GlbBuilder b) {
        return new Anim(b, "fall")
                .rot(ARM_R, t(0, 0.25f, 0.5f), v(e(-160, 0, -10), e(-150, 0, -40), e(-160, 0, -10)))
                .rot(ARM_L, t(0, 0.25f, 0.5f), v(e(-160, 0, 10), e(-150, 0, 40), e(-160, 0, 10)))
                .rot(LEG_R, t(0, 0.5f), v(e(-15, 0, 0), e(-15, 0, 0)))
                .rot(LEG_L, t(0, 0.5f), v(e(10, 0, 0), e(10, 0, 0)))
                .json();
    }

    private JsonObject getUp(GlbBuilder b) {
        return new Anim(b, "get_up")
                .trans(HIPS, t(0, 0.4f, 1f), v(hipsAt(-0.3f), hipsAt(-0.25f), hipsAt(0)))
                .rot(TORSO, t(0, 0.4f, 1f), v(e(45, 0, 0), e(30, 0, 0), e(0, 0, 0)))
                .rot(LEG_R, t(0, 0.4f, 1f), v(e(-70, 0, 0), e(-60, 0, 0), e(0, 0, 0)))
                .rot(LEG_L, t(0, 0.4f, 1f), v(e(-70, 0, 0), e(-60, 0, 0), e(0, 0, 0)))
                .rot(ARM_R, t(0, 1f), v(e(-30, 0, -10), e(0, 0, -4)))
                .rot(ARM_L, t(0, 1f), v(e(-30, 0, 10), e(0, 0, 4)))
                .json();
    }

    private JsonObject wave(GlbBuilder b) {
        return new Anim(b, "wave")
                .rot(ARM_R, t(0, 0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f),
                        v(e(0, 0, -4), e(0, 0, -150), e(0, 0, -130), e(0, 0, -165), e(0, 0, -130), e(0, 0, -150), e(0, 0, -4)))
                .rot(HEAD, t(0, 0.3f, 1.2f, 1.5f), v(e(0, 0, 0), e(0, 0, -10), e(0, 0, -10), e(0, 0, 0)))
                .json();
    }

    private JsonObject blink(GlbBuilder b) {
        return new Anim(b, "blink")
                .scale(EYES, t(0, 0.06f, 0.12f, 0.2f), v(e(1, 1, 1), e(1, 0.1f, 1), e(1, 0.1f, 1), e(1, 1, 1)))
                .json();
    }

    private JsonObject attack(GlbBuilder b) {
        return new Anim(b, "attack")
                .rot(ARM_R, t(0, 0.2f, 0.32f, 0.5f), v(e(0, 0, -4), e(-150, 0, -10), e(-40, 0, -4), e(0, 0, -4)))
                .rot(TORSO, t(0, 0.2f, 0.32f, 0.5f), v(e(0, 0, 0), e(-5, -20, 0), e(10, 15, 0), e(0, 0, 0)))
                .json();
    }

    private JsonObject hurt(GlbBuilder b) {
        return new Anim(b, "hurt")
                .rot(TORSO, t(0, 0.08f, 0.3f), v(e(0, 0, 0), e(-18, 0, 5), e(0, 0, 0)))
                .rot(HEAD, t(0, 0.08f, 0.3f), v(e(0, 0, 0), e(-15, 10, 0), e(0, 0, 0)))
                .json();
    }

    private JsonObject death(GlbBuilder b) {
        return new Anim(b, "death")
                .rot(ROOT, t(0, 0.2f, 0.8f, 1f), v(e(0, 0, 0), e(-10, 0, 0), e(-85, 0, 0), e(-90, 0, 0)))
                .rot(HEAD, t(0, 0.8f, 1f), v(e(0, 0, 0), e(0, 0, 20), e(0, 0, 25)))
                .rot(ARM_R, t(0, 1f), v(e(0, 0, -4), e(-20, 0, -60)))
                .rot(ARM_L, t(0, 1f), v(e(0, 0, 4), e(-20, 0, 60)))
                .json();
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject primitive(GlbBuilder b, int pos, int nrm, int uv, int joints, int weights, List<Integer> indices, int material) {
        int[] data = new int[indices.size()];
        for (int i = 0; i < data.length; i++) data[i] = indices.get(i);
        JsonObject attrs = new JsonObject();
        attrs.addProperty("POSITION", pos);
        attrs.addProperty("NORMAL", nrm);
        attrs.addProperty("TEXCOORD_0", uv);
        attrs.addProperty("JOINTS_0", joints);
        attrs.addProperty("WEIGHTS_0", weights);
        JsonObject prim = new JsonObject();
        prim.add("attributes", attrs);
        prim.addProperty("indices", b.ushorts("SCALAR", data));
        prim.addProperty("material", material);
        return prim;
    }

    private static JsonObject material(String name, boolean emissive) {
        JsonObject m = new JsonObject();
        m.addProperty("name", name);
        JsonObject pbr = new JsonObject();
        JsonObject tex = new JsonObject();
        tex.addProperty("index", 0);
        pbr.add("baseColorTexture", tex);
        m.add("pbrMetallicRoughness", pbr);
        if (emissive) {
            JsonArray f = new JsonArray();
            f.add(1);
            f.add(1);
            f.add(1);
            m.add("emissiveFactor", f);
        }
        return m;
    }

    private static byte[] texture() throws IOException {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int c = 0; c < COLORS.length; c++) {
            int cx = (c % 4) * 4, cy = (c / 4) * 4;
            for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) img.setRGB(cx + x, cy + y, COLORS[c]);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static float[] toArray(List<Float> list) {
        float[] a = new float[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    private static JsonArray floats(float[] values) {
        JsonArray a = new JsonArray();
        for (float v : values) a.add(v);
        return a;
    }
}
