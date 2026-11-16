package dev.denismasterherobrine.animatio.dev;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.denismasterherobrine.animatio.api.AnimatioApi;
import net.minecraft.commands.Commands;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.joml.Vector3f;

/**
 * Dev-only content for testing Animatio in the dev runs.
 */
@Mod(AnimatioDev.MODID)
public final class AnimatioDev {
    public static final String MODID = "animatio_dev";
    public static final ResourceLocation ROBOT_MODEL = ResourceLocation.fromNamespaceAndPath(MODID, "robot");

    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    private static final DeferredRegister<net.minecraft.world.level.block.entity.BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<RobotEntity>> ROBOT = ENTITIES.register("robot",
            () -> EntityType.Builder.of(RobotEntity::new, MobCategory.CREATURE).sized(0.6f, 1.95f).clientTrackingRange(10).build("robot"));
    public static final net.neoforged.neoforge.registries.DeferredBlock<RobotStatueBlock> STATUE = BLOCKS.register("robot_statue",
            () -> new RobotStatueBlock(net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().strength(1f).noOcclusion()));
    public static final net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.BlockItem> STATUE_ITEM =
            ITEMS.registerSimpleBlockItem(STATUE);
    public static final net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.Item> ROBOT_EGG = ITEMS.register("robot_spawn_egg",
            () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(ROBOT, 0x3A6EA5, 0x55E0FF, new net.minecraft.world.item.Item.Properties()));
    public static final DeferredHolder<net.minecraft.world.level.block.entity.BlockEntityType<?>, net.minecraft.world.level.block.entity.BlockEntityType<RobotStatueBlockEntity>> STATUE_BE =
            BLOCK_ENTITIES.register("robot_statue", () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder.of(RobotStatueBlockEntity::new, STATUE.get()).build(null));

    public AnimatioDev(IEventBus modBus) {
        ENTITIES.register(modBus);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(AnimatioDev::onAttributes);
        modBus.addListener((net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey() == net.minecraft.world.item.CreativeModeTabs.SPAWN_EGGS) e.accept(ROBOT_EGG.get());
            if (e.getTabKey() == net.minecraft.world.item.CreativeModeTabs.FUNCTIONAL_BLOCKS) e.accept(STATUE_ITEM.get());
        });
        NeoForge.EVENT_BUS.addListener(AnimatioDev::onRegisterCommands);

        // model bindings: robots and statues use assets/animatio_dev/animatio/models/robot.glb
        modBus.addListener((net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent e) -> {
            AnimatioApi.bindModel(ROBOT.get(), ROBOT_MODEL);
            AnimatioApi.bindModel(STATUE_BE.get(), ROBOT_MODEL);
        });
        // a forward-looking camera in the head (the head bone looks backward along -Z, like every glTF camera)
        AnimatioApi.configureModel(ROBOT_MODEL, m -> m.socket("eye_camera", "head",
                new org.joml.Matrix4f().translate(0f, 0.3f, 0.3f).rotateY((float) Math.PI)));

        // handler for the "animatio_dev:hearts" custom event fired by the wave clip (client side)
        AnimatioApi.registerEventHandler(ResourceLocation.fromNamespaceAndPath(MODID, "hearts"), (ctx, data) -> {
            if (ctx.level() == null) return;
            Vector3f pos = new Vector3f();
            ctx.bonePosition("right_hand", pos);
            int count = data.isEmpty() ? 1 : Integer.parseInt(data);
            for (int i = 0; i < count; i++) {
                ctx.level().addParticle(ParticleTypes.HEART, pos.x + (i - count / 2) * 0.2, pos.y + 0.2, pos.z, 0, 0.05, 0);
            }
        });
    }

    private static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(ROBOT.get(), RobotEntity.createAttributes().build());
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("animatio_dev").requires(s -> s.hasPermission(2))
                .then(Commands.literal("spawn")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 2000)).executes(ctx -> {
                            int count = IntegerArgumentType.getInteger(ctx, "count");
                            ServerLevel level = ctx.getSource().getLevel();
                            Vec3 origin = ctx.getSource().getPosition();
                            int side = (int) Math.ceil(Math.sqrt(count));
                            int spawned = 0;
                            for (int i = 0; i < count; i++) {
                                double x = origin.x + (i % side - (side - 1) / 2.0) * 1.5;
                                double z = origin.z + (i / side) * 1.5 + 4;
                                RobotEntity robot = ROBOT.get().create(level);
                                if (robot == null) continue;
                                robot.moveTo(x, origin.y, z, level.random.nextFloat() * 360f, 0f);
                                robot.finalizeSpawn(level, level.getCurrentDifficultyAt(robot.blockPosition()), MobSpawnType.COMMAND, null);
                                level.addFreshEntity(robot);
                                spawned++;
                            }
                            int n = spawned;
                            ctx.getSource().sendSuccess(() -> Component.literal("Spawned " + n + " robots"), true);
                            return spawned;
                        }))));
    }
}
