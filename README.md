# Animatio

A glTF animation engine for NeoForge 1.21.1 that allows to load skinned models from `.glb` files, sequenced and parallel animations, cross-fades, blend spaces, events, bone attachments, material overrides with custom shaders and instanced GPU skinning.

## Quick start

1. Put the model at `assets/<modid>/animatio/models/<name>.glb` (Blender: *File → Export → glTF 2.0*, format *glTF Binary*),
   or drop in a Blockbench project as `<name>.bbmodel` (see *Blockbench projects*).
2. Bind it and register a renderer:

```java
// common code (mod constructor or common setup)
AnimatioApi.bindModel(MY_NPC.get(), ResourceLocation.fromNamespaceAndPath("mymod", "npc"));

// client: EntityRenderersEvent.RegisterRenderers
event.registerEntityRenderer(MY_NPC.get(), GltfEntityRenderer::new);
```

3. Play animations from either side. Calls made on the server are synced to every client tracking the entity.

```java
static final AnimationRef JUMP = AnimationRef.of("jump"), FALL = AnimationRef.of("fall"), BLINK = AnimationRef.of("blink");

AnimationController c = AnimatioApi.controller(npc);
c.play(JUMP.thenWaitSecs(10).thenPlay(FALL).thenHoldLastFrame());
```

## Features

| Requirement | How |
|---|---|
| glTF loading | Own GLB/glTF 2.0 parser: node hierarchy, skins, meshes (strided/normalised/sparse accessors, strips, fans), PBR base colour and emissive textures, alpha modes, `KHR_materials_unlit`, `KHR_materials_emissive_strength`, `KHR_mesh_quantization`, STEP/LINEAR/CUBICSPLINE animations. |
| Blockbench | `.bbmodel` projects load directly: groups as bones, cubes (inflate, rotation, box and per-face UV), locators, embedded textures, and animations evaluated with Blockbench's own rules (linear, step, Catmull-Rom, Bézier, Molang values). |
| Compression | `KHR_draco_mesh_compression` (Draco geometry) and `KHR_texture_basisu` (KTX2 textures: Basis Universal ETC1S and UASTC, plus plain 8-bit KTX2, with Zstandard or ZLIB supercompression). Decoded in pure Java when the model loads. |
| Morph targets | POSITION and NORMAL targets, default and per-node weights, `weights` animation channels, target names from `extras.targetNames` (Blender shape keys). Weights blend, cross-fade and layer like bones. `setMorphWeight("smile", 0.8f)` pins a target from code (synced). |
| Cameras and lights | glTF cameras drive the player's view: `startCameraView` follows an animated camera node (position, rotation, roll, field of view), for cutscenes. `KHR_lights_punctual` lights brighten their own model and are available in world space for dynamic-light integrations. |
| Players and runtime models | `AnimatioApi.setModel(entity, model)` gives any entity, players included, a glTF model at runtime (saved, synced). Players can also get one from a client-side choice, `bindModel(EntityType.PLAYER, …)` or a resource pack. Players keep vanilla first-person arms, name tags and `RenderPlayerEvent`s; a state driver plays locomotion, crouch, swim, elytra, ride, sleep, use-item, attack, hurt and death clips. |
| Look-at | A head bone follows where the entity looks (`"look"` in the definition), clamped, applied in model space. |
| Sequences | `thenPlay`, `thenPlayFor`, `thenLoop`, `thenWaitSecs/Ticks`, `thenFire`, `thenHoldLastFrame`, `thenRepeat`; steps blend into each other. |
| Parallel animations | Every `play` is an independent track. Later tracks override earlier ones only on the bones they animate (priority first, then play order). Bone masks and additive tracks are available through `PlayOptions`. |
| Helpers | `pause/resume(anim)`, `pauseAll/resumeAll`, `stop(anim)`, `stopAll`, `reset`, `playAt(anim, secs)`, `isPlaying`, `isPaused`, `clipTime`, `setSpeed`, `setGlobalSpeed`, `crossFade`, and track-id overloads. |
| Events | Keyframe events from code (`AnimationRef.withEvent`), JSON or glTF `extras`: sounds, particles, and custom handlers (`AnimatioApi.registerEventHandler`). They fire on the client, including across loops; seeking never fires them. |
| Bone attachments | `GltfEntityRenderer.withAttachment(bone, …)`: held items, equipped items, vanilla armour pieces, capes, blocks, or any `BoneAttachment`. Named sockets (bone + offset) come from JSON or code. |
| Transitions | A `stop(A)` + `play(B)` in the same tick, replaying the same key, or `crossFade` cross-fades without dipping to the rest pose. Durations come from the call, a pair table (JSON or code), or the default. |
| Blend spaces | `BlendSpace1D` and `BlendSpace2D` (Delaunay, clamped to the hull), phase-synchronised, with optional parameter smoothing. Built-in client parameters: `speed`, `move_x`, `move_z`, `vertical_speed`, `on_ground`, `yaw_delta`, `health`. |
| Materials and shaders | `MaterialOverride` per instance and material: texture, tint, emissive, hidden, custom shader plus 4 per-instance parameters. Shaders build on `#moj_import <animatio:skinning.glsl>`; `animatio:dissolve` is an example. |
| Culling and GPU skinning | Frustum culling with bounds computed over every clip (and the reach of morph targets). Distant models update their pose at a reduced rate. Poses are evaluated on worker threads. Opaque materials are drawn with one instanced draw call per model part, with bones, morph weights and instance data in texture buffers. With a shader pack, models are skinned on the GPU by transform feedback and drawn through the pack's own programs. The CPU fallback batches by render type and skins in parallel. |

Animated objects: custom entities, block entities (`GltfBlockEntityRenderer`), items (`GltfItemRenderer`, with
animation state per item stack), players, any entity given a model at runtime, and existing entity types replaced
from a resource pack (see *Entity overrides*).

## Package layout

| Package | Contents |
|---|---|
| `api` | `AnimatioApi` (entry point), `AnimationController`, `AnimationListener`, `GltfAnimatable`, `ModelConfig` |
| `api.playable` | `AnimationRef`, `Sequence`, `BlendSpace1D`, `BlendSpace2D` (and the sealed `Playable`/`BlendSpace`) |
| `api.playback` | `PlayOptions`, `TransitionConfig`, `TransitionTable`, `Easing`, `BoneMask` |
| `api.event` | `AnimationEvent`, `Events` (built-in events), `EventContext`, `EventMarker` |
| `api.material` | `MaterialOverride` |
| `anim.model` | Skeleton, clips and keyframe sampling, poses, `AnimationSet` (per-model data) |
| `anim.runtime` | The controller implementation, tracks and the sync commands |
| `anim.math` | Matrix, quaternion and Delaunay helpers |
| `client.*` | Renderers (`entity`, `blockentity`, `item`), `attach` (bone attachments), `render` (CPU and GPU paths), `model`, `override` |
| `gltf`, `data`, `event`, `net`, `common` | glTF parser, JSON definitions, event types, networking, attachment/ticking/sync glue |

## API tour

```java
AnimationController c = AnimatioApi.controller(entity);

// parallel: keeps blinking while greeting
if (entity.tickCount % 30 == 0 && !c.isPlaying(BLINK)) c.play(BLINK);
c.play(GREET);

// configurable cross-fade
c.stop(FALL);
c.play(GET_UP);                                   // uses the fall -> get_up pair duration, if defined
c.play(GET_UP, TransitionConfig.of(0.4f));        // or an explicit one

// options
c.play(WAVE, PlayOptions.defaults().priority(5).mask(BoneMask.subtree("arm_r")).speed(1.5f));

// blend space (or declare it in JSON and play AnimationRef.of("locomotion"))
BlendSpace1D LOCOMOTION = BlendSpace1D.builder("locomotion", "speed")
        .sample(0, IDLE).sample(2, WALK).sample(6, RUN).smoothing(0.15f).build();
c.play(LOCOMOTION);

// events from code (network-safe)
AnimationRef STEP_WALK = AnimationRef.of("walk").looping()
        .withEvent(0.25f, Events.sound(SoundEvents.IRON_GOLEM_STEP).atBone("foot_l"))
        .withEvent(0.5f, Events.custom(ResourceLocation.fromNamespaceAndPath("mymod", "dust")));

// model-level configuration in code (wins over JSON; call from common code)
AnimatioApi.configureModel(MODEL, m -> m.loop("idle", true)
        .transition(FALL, GET_UP, TransitionConfig.of(0.3f))
        .socket("right_hand", "hand_r", new Matrix4f().translate(0, 0.05f, 0)));
```

Morph targets, runtime models, cameras and item stacks:

```java
c.setMorphWeight("smile", 0.8f);                  // a shape key, by name (or "<node>/<target>"); synced from the server
c.clearMorphWeight("smile");                      // back to what the animations say

AnimatioApi.setModel(serverPlayer, ResourceLocation.fromNamespaceAndPath("mymod", "knight")); // any entity; null resets
AnimatioApi.startCameraView(serverPlayer, cutsceneEntity, "Camera");  // look through a glTF camera node
AnimatioApi.stopCameraView(serverPlayer);

AnimatioApi.playOnItem(player, player.getMainHandItem(), AnimationRef.of("open")); // per-stack, seen by every client
```

Client-only API (`AnimatioClientApi`):

```java
AnimatioClientApi.setMaterialOverride(entity, MaterialOverride.none().withTint(1f, 0.4f, 0.4f, 1f));
AnimatioClientApi.setMaterialOverride(entity, "body",
        MaterialOverride.none().withShader(GpuShaders.DISSOLVE).withParams(progress, 1f, 0.5f, 0.1f));
AnimatioClientApi.registerShader(ResourceLocation.fromNamespaceAndPath("mymod", "hologram"));

AnimatioClientApi.setPlayerModel(playerUuid, model);          // this client only (cosmetics, previews)
AnimatioClientApi.startCameraView(entity, "Camera");          // client-side cutscene
AnimatioClientApi.lights(entity);                             // world-space glTF lights, e.g. for a dynamic-lights mod
AnimatioClientApi.itemController(stack).play(SPIN);           // one item stack's own controller
```

Materials whose texture is `animatio:player_skin` (in the definition) use the rendered player's skin, so a player
model can be skinned like vanilla. Custom material shaders that replace the vertex shader should call
`animatio_morph(inst, position, normal)` from `skinning.glsl` to support morph targets.

## Model definition (`<name>.anim.json`)

Optional file next to the model. Everything in it is optional:

```json
{
  "clips": {
    "walk": { "loop": true, "speed": 1.0,
              "events": [ { "time": 0.25, "type": "animatio:sound", "sound": "minecraft:block.metal.step", "bone": "foot_l" } ] },
    "jump": { "events": [ { "time": 0.2, "type": "animatio:particle", "particle": { "type": "minecraft:poof" }, "count": 6 } ] },
    "wave": { "events": [ { "time": 0.5, "type": "animatio:custom", "id": "mymod:hearts", "data": "3" } ] }
  },
  "blend_spaces": {
    "locomotion": { "parameter": "speed", "smoothing": 0.15,
                    "samples": [ { "x": 0, "clip": "idle" }, { "x": 2, "clip": "walk" }, { "x": 6, "clip": "run" } ] },
    "move": { "parameters": ["move_x", "move_z"],
              "samples": [ { "x": 0, "y": 0, "clip": "idle" }, { "x": 0, "y": 4, "clip": "walk" }, { "x": 3, "y": 0, "clip": "strafe_r" } ] }
  },
  "transitions": { "default": 0.2, "pairs": [ { "from": "fall", "to": "get_up", "duration": 0.35, "easing": "ease_in_out" } ] },
  "sockets": { "right_hand": { "bone": "hand_r", "offset": [0, 0.05, 0], "rotation": [0, 0, 0], "scale": 1 } },
  "materials": { "eyes": { "render_type": "emissive" }, "glass": { "render_type": "translucent" },
                 "skin": { "texture": "mymod:textures/entity/npc_alt.png", "shader": "mymod:hologram", "tint": [1, 1, 1, 1] } },
  "render": { "scale": 1, "offset": [0, 0, 0], "rotation": [0, 0, 0], "shadow_radius": 0.5,
              "reduced_rate_distance": 32, "minimal_rate_distance": 64 },
  "look": { "bone": "head", "max_yaw": 75, "max_pitch": 60, "weight": 1 },
  "lights": { "Lantern": { "block_light": 12 } }
}
```

`look` turns a bone toward where a living entity looks (head yaw relative to the body, and pitch). `lights` sets how
much a glTF light (by node name) brightens its own model; by default 10 candela of white light or more is full
brightness and directional lights give none. Code can do the same with `ModelConfig.look(...)`.

Render types: `auto` (from the glTF alpha mode), `solid`, `cutout`, `translucent`, `emissive`, `invisible`.

## Blockbench projects

A `.bbmodel` file next to the other models (`assets/<modid>/animatio/models/<name>.bbmodel`) loads like a glTF
model, on both sides, with an optional `<name>.anim.json`:

* Groups become bones with the group's name, pivot and rotation; locators and null objects become bones too, so
  they work as sockets and attachment points. Cubes follow their group rigidly and keep Blockbench's geometry rules
  (inflate, stretch, cube rotation and rescale, box UV with mirroring and its bleed margin, per-face UV rotation,
  flat planes). Textures embedded in the project are used; otherwise a PNG with the texture's name next to the
  model is looked up. Materials are named after their texture (without `.png`) and are double-sided unless the
  texture's render sides are set to front.
* Animations are evaluated the way Blockbench evaluates them (linear, step, Catmull-Rom including loop wrapping,
  Bézier handles, two-sided keyframes, per-bone quaternion interpolation, Molang values with `query.anim_time` and
  `math.*`) and baked at 60 samples per second. A clip whose loop mode is *loop* loops by default.
* Units are pixels (16 per block). Minecraft-style models face north (-Z) in Blockbench, so the model is turned to
  face forward in game; block-format projects keep their 0..16 placement instead.
* Not loaded: mesh elements (export the project as glTF for those), animated textures, and sound, particle and
  timeline keyframes (add events in the definition file instead).

## Entity overrides (resource packs)

`assets/<any>/animatio/entity_overrides/<entity namespace>/<entity path>.json` replaces an existing entity's renderer:

```json
{
  "model": "mypack:zombie",
  "death_flip": false,
  "driver": { "locomotion": "locomotion", "attack": "attack", "hurt": "hurt", "death": "death" },
  "attachments": [ { "bone": "hand_r", "type": "held_item", "arm": "right" },
                   { "bone": "head", "type": "armor", "slot": "head", "piece": "head" } ]
}
```

The driver plays the clips on the client from the entity's state: locomotion looping, plus attack on swing, hurt on
damage, and death held on its last frame. State clips loop while their state lasts, on layers above locomotion:
`crouch`, `swim`, `fly` (elytra), `ride`, `sleep` and `use` (using an item).

`entity_overrides/minecraft/player.json` gives every player a model on that client. Players and entities with a
runtime model but no override file use the conventional clip names above (`locomotion`, `attack`, … — whichever the
model has) and attachments on conventional sockets: `right_hand` / `left_hand` (held items), `head`, `body`,
`right_arm`, `left_arm`, `right_leg`, `left_leg`, `right_foot`, `left_foot` (armour pieces) and `cape` (the player's
cape). Map them to your bones with `sockets` in the model definition.

## How it works

* **Sync.** The controller is a data attachment on both sides. Server calls become commands, batched per tick and
  sent to trackers. Clients that start tracking later get a snapshot, and clients catch up for network lag. The server
  reads clip timings straight from mod jars, so `isPlaying` and sequence timing are correct there. Animation state is
  not saved with the world.
* **Pose evaluation.** At the start of each frame, models that were visible in the previous frame and are still in
  the view frustum have their controllers advanced on the render thread (events fire there). Their poses are then
  evaluated on worker threads while terrain renders, and joined before entities draw. Models that just came into view
  are evaluated inline. This only kicks in when a frame's pose work is worth it (≥0.5 ms, measured at runtime), and
  distant models update their pose at 15 or 5 Hz.
* **Rendering.** Opaque and cutout parts go through the GPU path: one instanced draw per model part, with bones in a
  texture buffer. Everything else uses the CPU path:
  * During world rendering, CPU-skinned models are batched per render type. Workers skin vertices and write them
    straight into vertex buffers in chunks, and the render thread only uploads and draws them.
  * Shader packs, glowing outlines, GUIs and items write immediately, inside the object's render call.
  * Triangle meshes are paired into quads at load time, and primitives sharing a vertex list are compacted.
* **Data layout.** Clips are stored as flat arrays with their channels grouped by shared key times, so the key search
  runs once per time track (constant time for evenly spaced, baked keys); a layer holding a single full-weight clip
  (or a blend space with one active clip) writes straight into the pose, and blend spaces only touch the properties
  their clips animate. The CPU kernel walks runs of vertices that share one rigid joint with the matrix in
  registers, morph targets are applied from sparse per-target vertex lists, and both batchers keep draws in
  parallel primitive arrays (sort keys, counting sort) so a frame allocates nothing once warmed up.
* **Shader packs (Iris/Oculus).** Detected through Iris' public API. While a pack is active, opaque and cutout parts
  are skinned on the GPU by a transform-feedback pass and drawn with the part's render type through the program the
  pack installs (shadow passes included). The pass writes the vertex layout that program was built for: with Iris
  that is its extended entity format, so packs get the entity/block-entity/item ids from their
  `entity.properties`-style mappings (`entityId`, `blockEntityId`, `currentRenderedItemId`), `mc_midTexCoord` and
  `at_tangent` (computed once per mesh with Iris' conventions and skinned with the normal), as for vanilla entities.
  Smooth normals are kept. Translucent materials and emissive layers use the CPU path, which Iris extends itself.
  Custom material shaders (such as the dissolve effect) do not apply under a pack; texture, tint, emissive and hidden
  overrides still do.

## Performance

Measured with 1000 frozen robots in view (about 900 visible), with the default 8 worker threads.
Times are whole frames, including vanilla's per-entity work, so they vary by 10–25% between runs (the integrated server shares the CPU)

| Model | GPU path, parallel poses | GPU path, serial poses | CPU path, batched | CPU path, unbatched |
|---|---|---|---|---|
| Debug robot (11 bones, 288 vertices) | 7.3 ms | 7.5 ms | 9.1 ms | 60 ms |
| Heavy robot (59 bones, 1.4k vertices) | 8.2 ms | 11.5 ms | 20–26 ms | 65–90 ms |

The unbatched CPU path (immediate writes, as used with glowing outlines and in GUIs) is dominated by vanilla's per-entity buffer switching. Steadier per-model costs: evaluating a pose takes about 2.8 µs (debug robot, before 3.1) and 13 µs (heavy robot, before 16), and skinning plus writing the heavy robot's vertices on the CPU takes about 16 µs per instance on one thread (before 21.5).

Client settings (`config/animatio-client.toml`):

| Setting | Default | Meaning |
|---|---|---|
| `gpuSkinning` | `true` | GPU skinning: instanced batches, or transform feedback while a shader pack is active. |
| `parallelPoses` | `true` | Evaluate visible poses on worker threads while terrain renders. |
| `batchCpuPath` | `true` | Batch and parallelise the CPU path during world rendering. |
| `workerThreads` | `-1` | Workers for poses and CPU skinning: `-1` = half the cores (max 8), `0` = none. |
| `lodDistanceScale` | `1.0` | Scales the reduced-update-rate distances; `0` updates every pose every frame. |

At this scale the integrated server is usually the limit: 1000 mobs take 15–30 ms of its 50 ms tick before any AI
runs, and that cost is vanilla's.

## Development

Gradle needs JDK 21.

| Task | What |
|---|---|
| `./gradlew test` | Unit tests: parser, sampling (the flattened sampler against per-channel sampling), sequences, layering, cross-fades, blend spaces, events, replication, network codecs, morph targets, cameras, lights, look-at, Draco (against the uncompressed model) and KTX2 (ETC1S and UASTC against the lossless image). |
| `./gradlew runGameTestServer` | Server-side integration tests with the dev robot (model loading from mod files, sync snapshot, timing, runtime models). |
| `./gradlew runAutotestClient` | Unattended client run. It creates a flat world, takes screenshots (instanced GPU, CPU, transform feedback, morph targets, dissolve, held items, a player drawn with the robot model, the view through a model camera) into `run/screenshots/`, benchmarks GPU/CPU paths with serial vs parallel poses, and quits. `-PperfCount=1000` sets the robot count; `-PheavyRobots=true` uses the 59-bone variant. Results go to `run/logs/latest.log` (`PERF` lines). |
| `./gradlew runIrisAutotestClient` | The same with Iris, Sodium and the test shader pack, in the separate game directory `run-iris` (not in git): put the Iris and Sodium NeoForge 1.21.1 jars in `run-iris/mods` and select `AnimatioTest` in `run-iris/config/iris.properties`. The pack in `src/dev/shaderpacks/AnimatioTest` is copied in before the run; it shades entities normally and casts shadows. The run then turns the pack off (instanced GPU path with Iris and Sodium loaded) and on again, and ends with the pack's `DEBUG_VIEW` option on for two screenshots (`animatio_autotest_iris_debug_gpu/cpu.png`): false-colour stripes of id tint, tangent direction, and mid-texture-coordinate distance with an id code, to compare what the GPU and CPU paths give the pack. These stripes are intentional, not a rendering glitch. |
| `./gradlew test -PincludeBenchmarks --tests "*Benchmark*" -i` | Single-thread throughput of the CPU skinning kernel. |
| `./gradlew runClient` | Normal dev client with the `animatio_dev` content. |
| `./gradlew generateDebugRobot` | Regenerates the dev robot models (normal and 59-bone heavy) and the GameTest structure. |

## Limitations

* Morph targets: TANGENT deltas are ignored. Skinning uses four influences per vertex (`JOINTS_1` is ignored).
* Lights: Minecraft has no dynamic lights, so a glTF light only brightens its own model; other mods can read lights
  through `AnimatioClientApi.lights`. Animating light colour or intensity (`KHR_animation_pointer`) is not supported.
* KTX2: the base mip level of 2D textures is used; UASTC HDR, ASTC/BC/ETC2 `vkFormat`s and 16/32-bit formats are
  not decoded.
* Shader packs: custom material shaders (including the dissolve effect) do not apply; translucent materials and
  emissive layers use the CPU path. A vertex shared by several faces gets the average of their mid texture
  coordinates (box models share none, so they match what Iris computes).
* Item stacks: stacks with an animation identity no longer stack with stacks without one, and item animations fire no
  events (sounds, particles).
* Player models replace the body in the world; first-person arms stay vanilla.

Third-party code embedded in the jar: [Drako](https://github.com/jMonkeyEngine/drako-java) (Draco decoding, MIT) and
[aircompressor](https://github.com/airlift/aircompressor) (Zstandard, Apache 2.0). The KTX2/Basis Universal decoder
is a Java port of parts of the [Basis Universal](https://github.com/BinomialLLC/basis_universal) transcoder
(Apache 2.0).
