package com.example;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.kyori.adventure.platform.modcommon.MinecraftServerAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.OminousBottleAmplifier;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class ExampleMod implements ModInitializer {
    public static final String MOD_ID = "chestinspect";

    // This logger is used to write text to the console and the log file.
    // It is considered best practice to use your mod id as the logger's name.
    // That way, it's clear which mod wrote info, warnings, and errors.
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private volatile MinecraftServerAudiences adventure;

    private static int[] lastcheckxyzw = new int[4];
    private static MappedByteBuffer buffer;

    // 预分配写缓存区，避免创建 byte[]
    private static byte[] bCache = new byte[0xF000];
    private static StringBuilder gsb = new StringBuilder(0xF000);

    // 零 GC 字符编码器
    private static CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
    private static ByteBuffer bCacheBuffer = ByteBuffer.wrap(bCache);

    private static void memoryinit() throws IOException {
        File file = new File(System.getProperty("java.io.tmpdir"), "mc_ipc_shm.dat");

        try (RandomAccessFile raf = new RandomAccessFile(file, "rw"); FileChannel channel = raf.getChannel()) {
            // 映射共享内存
            buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, 0x200000 * 32);

            // 1. 将 MappedByteBuffer 转换为 MemorySegment
            MemorySegment segment = MemorySegment.ofBuffer(buffer);

            // 2. 原生填充 0x00
            segment.fill((byte) 0);

            // 3. 重置 Buffer 指针
            buffer.position(0);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private MinecraftServer mcserver = null;

    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // Proceed with mild caution.

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            mcserver = server;
            this.adventure = MinecraftServerAudiences.of(server);

            try {
                memoryinit();

                ServerTickEvents.END_SERVER_TICK.register(this::onTick);
            } catch (Exception e) {
                e.printStackTrace();
            }

        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("findelytra")
                    // 参数：中心点坐标
                    .then(Commands.argument("center", BlockPosArgument.blockPos()).then(Commands.argument("radius", IntegerArgumentType.integer(1, Integer.MAX_VALUE)).executes(context -> {
                        CommandSourceStack source = context.getSource();
                        BlockPos center = BlockPosArgument.getBlockPos(context, "center");
                        int radius = IntegerArgumentType.getInteger(context, "radius");

                        int state = buffer.getInt(0x40000);
                        if (state == 0) {
                            buffer.putInt(0x40004, 2);
                            buffer.putInt(0x40008, center.getX());
                            buffer.putInt(0x4000C, center.getY());
                            buffer.putInt(0x40010, center.getZ());
                            buffer.putInt(0x40014, radius);
                            buffer.putInt(0x40000, 1);
                        }

                        return 1;
                    }))));
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> this.adventure = null);

    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    /**
     * 单个末地城的完整扫描结果
     */
    private static class ScanResult {
        BlockPos pos = null;
        boolean hasShipPiece = false;
        BlockPos shipCenter = null;
        double distance;

        ScanResult(BlockPos pos, double distance) {
            this.pos = pos;
            this.distance = distance;
        }
    }

    public boolean elytrafinding = false;

    private void findElytra(MinecraftServer server) {

        if (elytrafinding) {
            buffer.putInt(0x40000, 2);
            return;
        }

        int state = buffer.getInt(0x40000);

        if (state != 1) return;

        int worldindex = buffer.getInt(0x40004);

        int x = buffer.getInt(0x40008);
        int y = buffer.getInt(0x4000C);
        int z = buffer.getInt(0x40010);
        int radius = buffer.getInt(0x40014);

        if (x == 0 && z == 0) return;
        if (radius == 0) return;

        ServerLevel level;
        if (worldindex == 0) {
            level = null;
        } else if (worldindex == 1) {
            level = null;
        } else if (worldindex == 2) {
            level = server.getLevel(Level.END);
        } else {
            return;
        }

        if (level == null) return;

        var structureRegistry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        var endCityStructure = structureRegistry.getOrThrow(BuiltinStructures.END_CITY);
        HolderSet<Structure> holderSet = HolderSet.direct(endCityStructure);

        StructurePieceSerializationContext context = StructurePieceSerializationContext.fromLevel(level);

        elytrafinding = true;
        buffer.putInt(0x40000, 2);

        BlockPos center = new BlockPos(x, y, z);

        sendMessage(Component.text("开始扫描末地城... 中心: [%d, %d, %d] 半径: %d".formatted(center.getX(), center.getY(), center.getZ(), radius), NamedTextColor.AQUA), null);

        long startTime = System.currentTimeMillis();

        var structuresfind = findMapStructures(level, holderSet, center, radius, false);


        try {

            int totalWithElytra = 0;
            List<ScanResult> results = new ArrayList<>();

            for (var res : structuresfind) {
                double dist = Math.sqrt(center.distSqr(new BlockPos(res.getFirst().getX(), res.getFirst().getY(), res.getFirst().getZ())));

                ScanResult result = new ScanResult(res.getFirst(), dist);

                // 分析结构组件, 检查是否有末地船部分
                boolean hasShipPiece = false;
                BlockPos shipPieceCenter = null;

                for (var piece : res.getSecond().getPieces()) {
                    CompoundTag tag = piece.createTag(context);
                    String templateName = tag.getString("Template").orElse("NULL");

                    BoundingBox pieceBB = piece.getBoundingBox();
                    // 末地船的结构组件名称通常包含 "Ship"
                    if (templateName.toLowerCase().contains("ship")) {
                        hasShipPiece = true;
                        shipPieceCenter = new BlockPos((pieceBB.minX() + pieceBB.maxX()) / 2, (pieceBB.minY() + pieceBB.maxY()) / 2, (pieceBB.minZ() + pieceBB.maxZ()) / 2);
                    }
                }

                result.hasShipPiece = hasShipPiece;
                result.shipCenter = shipPieceCenter;

                if (hasShipPiece) {
                    totalWithElytra++;
                }

                results.add(result);
            }

            long elapsed = System.currentTimeMillis() - startTime;

            sendMessage(Component.text("════════ 末地城鞘翅扫描报告 ════════", NamedTextColor.GOLD), null);
            sendMessage(Component.text("扫描耗时: %dms | 扫描范围: %d | 发现末地城: %d座".formatted(elapsed, radius, structuresfind.size()), NamedTextColor.GRAY), null);

            if (!structuresfind.isEmpty()) {
                // 按距离排序
                results.sort(Comparator.comparingDouble(r -> r.distance));

                int index = 0;
                for (ScanResult result : results) {

                    if (result.hasShipPiece) {
                        index++;

                        // 末地城标题行 (可点击传送)
                        Component cityHeader = Component.text("  #%d │ ".formatted(index), NamedTextColor.WHITE);

                        Component posText = Component.text("[%d, %d, %d]".formatted(result.shipCenter.getX(), result.shipCenter.getY(), result.shipCenter.getZ()), NamedTextColor.AQUA);

                        Component distText = Component.text(" (%.0f格)".formatted(result.distance), NamedTextColor.GRAY);

                        sendMessage(cityHeader.append(posText).append(distText), null);

                        sendMessage(Component.text("      ✅ 含有末地船", NamedTextColor.LIGHT_PURPLE), null);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            elytrafinding = false;
            buffer.putInt(0x40000, 0);
        }

    }

    public List<Pair<BlockPos, StructureStart>> findMapStructures(final ServerLevel level, final HolderSet<Structure> wantedStructures, final BlockPos pos, final int maxSearchRadius, final boolean createReference) {
        if (SharedConstants.DEBUG_DISABLE_FEATURES) {
            return new ArrayList<>();
        } else {
            ChunkGeneratorStructureState generatorState = level.getChunkSource().getGeneratorState();
            Map<StructurePlacement, Set<Holder<Structure>>> placementScans = new Object2ObjectArrayMap();

            for (Holder<Structure> structure : wantedStructures) {
                for (StructurePlacement placement : generatorState.getPlacementsForStructure(structure)) {
                    ((Set) placementScans.computeIfAbsent(placement, (p) -> new ObjectArraySet())).add(structure);
                }
            }

            if (placementScans.isEmpty()) {
                return new ArrayList<>();
            } else {
                List<Pair<BlockPos, StructureStart>> allStructures = new ArrayList<>();
                StructureManager structureManager = level.structureManager();
                List<Map.Entry<StructurePlacement, Set<Holder<Structure>>>> randomSpreadEntries = new ArrayList(placementScans.size());

                for (Map.Entry<StructurePlacement, Set<Holder<Structure>>> entry : placementScans.entrySet()) {
                    StructurePlacement placement = (StructurePlacement) entry.getKey();
                    if (placement instanceof ConcentricRingsStructurePlacement) {
                        ConcentricRingsStructurePlacement rings = (ConcentricRingsStructurePlacement) placement;
                        // 移除用于测距的pos参数，直接获取环状分布的所有结构
                        List<Pair<BlockPos, StructureStart>> generating = this.getGeneratedStructures((Set) entry.getValue(), level, structureManager, createReference, rings);
                        allStructures.addAll(generating);
                    } else if (placement instanceof RandomSpreadStructurePlacement) {
                        randomSpreadEntries.add(entry);
                    }
                }

                if (!randomSpreadEntries.isEmpty()) {
                    int chunkOriginX = SectionPos.blockToSectionCoord(pos.getX());
                    int chunkOriginZ = SectionPos.blockToSectionCoord(pos.getZ());

                    // 完整遍历直到 maxSearchRadius，不再因为 foundSomething = true 而提前 return
                    for (int radius = 0; radius <= maxSearchRadius; ++radius) {
                        for (Map.Entry<StructurePlacement, Set<Holder<Structure>>> entry : randomSpreadEntries) {
                            RandomSpreadStructurePlacement randomPlacement = (RandomSpreadStructurePlacement) entry.getKey();
                            List<Pair<BlockPos, StructureStart>> structurePos = getGeneratedStructures((Set) entry.getValue(), level, structureManager, chunkOriginX, chunkOriginZ, radius, createReference, generatorState.getLevelSeed(), randomPlacement);
                            allStructures.addAll(structurePos);
                        }
                    }
                }

                return allStructures;
            }
        }
    }

    private List<Pair<BlockPos, StructureStart>> getGeneratedStructures(final Set<Holder<Structure>> structures, final ServerLevel level, final StructureManager structureManager, final boolean createReference, final ConcentricRingsStructurePlacement rings) {
        List<ChunkPos> positions = level.getChunkSource().getGeneratorState().getRingPositionsFor(rings);
        if (positions == null) {
            throw new IllegalStateException("Somehow tried to find structures for a placement that doesn't exist");
        } else {
            List<Pair<BlockPos, StructureStart>> foundStructures = new ArrayList<>();

            // 移除距离计算和 closestPos 判断，直接收录所有生成的结构
            for (ChunkPos chunkPos : positions) {
                List<Pair<BlockPos, StructureStart>> generating = getStructureGeneratingAt(structures, level, structureManager, createReference, rings, chunkPos);
                foundStructures.addAll(generating);
            }

            return foundStructures;
        }
    }

    private static List<Pair<BlockPos, StructureStart>> getGeneratedStructures(final Set<Holder<Structure>> structures, final LevelReader level, final StructureManager structureManager, final int chunkOriginX, final int chunkOriginZ, final int radius, final boolean createReference, final long seed, final RandomSpreadStructurePlacement config) {
        int spacing = config.spacing();
        List<Pair<BlockPos, StructureStart>> foundStructures = new ArrayList<>();

        for (int x = -radius; x <= radius; ++x) {
            boolean xEdge = x == -radius || x == radius;

            for (int z = -radius; z <= radius; ++z) {
                boolean zEdge = z == -radius || z == radius;
                if (xEdge || zEdge) {
                    int sectorX = chunkOriginX + spacing * x;
                    int sectorZ = chunkOriginZ + spacing * z;
                    ChunkPos chunkTarget = config.getPotentialStructureChunk(seed, sectorX, sectorZ);
                    List<Pair<BlockPos, StructureStart>> generating = getStructureGeneratingAt(structures, level, structureManager, createReference, config, chunkTarget);
                    // 不再 return 找到的第一个结构，而是全部添加到列表
                    foundStructures.addAll(generating);
                }
            }
        }

        return foundStructures;
    }

    private static List<Pair<BlockPos, StructureStart>> getStructureGeneratingAt(final Set<Holder<Structure>> structures, final LevelReader level, final StructureManager structureManager, final boolean createReference, final StructurePlacement config, final ChunkPos chunkTarget) {
        List<Pair<BlockPos, StructureStart>> foundStructures = new ArrayList<>();

        for (Holder<Structure> structure : structures) {
            StructureCheckResult fastCheckResult = structureManager.checkStructurePresence(chunkTarget, (Structure) structure.value(), config, createReference);
            if (fastCheckResult != StructureCheckResult.START_NOT_PRESENT) {

                ChunkAccess chunk = level.getChunk(chunkTarget.x(), chunkTarget.z(), ChunkStatus.STRUCTURE_STARTS);
                StructureStart start = structureManager.getStartForStructure(SectionPos.bottomOf(chunk), (Structure) structure.value(), chunk);
                if (start != null && start.isValid() && (!createReference || tryAddReference(structureManager, start))) {
                    foundStructures.add(Pair.of(config.getLocatePos(start.getChunkPos()), start));
                }
            }
        }

        return foundStructures;
    }

    private static boolean tryAddReference(final StructureManager manager, final StructureStart start) {
        if (start.canBeReferenced()) {
            manager.addReference(start);
            return true;
        } else {
            return false;
        }
    }

    private CompletableFuture<List<ChunkPos>> predictEndCitiesAsync(ServerLevel level, ChunkPos center, int chunkRadius) {
        return CompletableFuture.supplyAsync(() -> {
            List<ChunkPos> foundChunks = new ArrayList<>();

            ChunkGenerator generator = level.getChunkSource().getGenerator();
            RandomState randomState = level.getChunkSource().randomState();
            long seed = level.getSeed();

            var structureSetRegistry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET);
            var endCitySetHolder = structureSetRegistry.get(BuiltinStructureSets.END_CITIES);

            if (endCitySetHolder.isPresent()) {
                StructureSet structureSet = endCitySetHolder.get().value();

                // 2. 获取该结构集的 Placement 规则
                StructurePlacement placement = structureSet.placement();

                int minX = center.x() - chunkRadius;
                int maxX = center.x() + chunkRadius;
                int minZ = center.z() - chunkRadius;
                int maxZ = center.z() + chunkRadius;

                // 纯数学遍历计算，不触发任何世界加载
                for (int cx = minX; cx <= maxX; cx++) {
                    for (int cz = minZ; cz <= maxZ; cz++) {
                        // 判断当前区块坐标是否满足结构的数学生成分布要求
                        ChunkGeneratorStructureState structureState = level.getChunkSource().getGeneratorState();
                        if (placement.isStructureChunk(structureState, cx, cz)) {
                            foundChunks.add(new ChunkPos(cx, cz));
                        }
                    }
                }
            }

            return foundChunks;
        });
    }

    private void chestInspect(MinecraftServer server) {
        int x = buffer.getInt(0x0);
        int y = buffer.getInt(0x4);
        int z = buffer.getInt(0x8);
        int worldindex = buffer.getInt(0xC);

        if (x == 0 && y == 0 && z == 0) return;

        if (x == lastcheckxyzw[0] && y == lastcheckxyzw[1] && z == lastcheckxyzw[2] && worldindex == lastcheckxyzw[3]) {
            return;
        }

        lastcheckxyzw[0] = x;
        lastcheckxyzw[1] = y;
        lastcheckxyzw[2] = z;
        lastcheckxyzw[3] = worldindex;

        ServerLevel level;
        if (worldindex == 0) {
            level = server.getLevel(Level.OVERWORLD);
        } else if (worldindex == 1) {
            level = server.getLevel(Level.NETHER);
        } else if (worldindex == 2) {
            level = server.getLevel(Level.END);
        } else {
            return;
        }

        if (level == null) return;

        BlockPos pos = new BlockPos(x, y, z);

        getBlockEntityAsync(level, pos).thenAcceptAsync(blockEntity -> {
            if (blockEntity == null) {
                return;
            }

            if (!(blockEntity instanceof Container container)) {
                Component title = Component.text("坐标：", NamedTextColor.GRAY).append(Component.text(x + ", " + y + ", " + z + ", " + worldindex, NamedTextColor.YELLOW)).append(Component.text(" 不是 容器 方块！", NamedTextColor.GRAY));
                sendMessage(title, null);
                return;
            }

            // 获取容器翻译 Key (如 block.minecraft.chest)
            String blockTranslationKey = blockEntity.getBlockState().getBlock().getName().getString();
            Component containerTypeComponent = Component.text(blockTranslationKey).color(NamedTextColor.WHITE);

            Component title = Component.text("开始检查坐标：", NamedTextColor.GRAY).append(Component.text(x + ", " + y + ", " + z, NamedTextColor.YELLOW)).append(Component.text(" 的 ", NamedTextColor.GRAY)).append(containerTypeComponent).append(Component.text(" 内容...", NamedTextColor.GRAY));
            sendMessage(title, null);

            gsb.setLength(0);

            inspectInventory(container, null);
            inspectInventory(container, gsb);

            String s = gsb.toString();

            if (s.isEmpty()) {
                s = "(空)";
            }

            int length = encodeToCache(s);

            // 写入共享内存
            buffer.put(0x200, bCache, 0, length);
            buffer.putInt(0x110, length);
            buffer.putInt(0x100, x);
            buffer.putInt(0x104, y);
            buffer.putInt(0x108, z);
            buffer.putInt(0x10C, worldindex);
        }, level.getServer());

    }

    private String inspectInventory(Container inv, StringBuilder sb) {
        inspectInventoryInternal(inv, sb);
        return sb != null ? sb.toString() : "";
    }

    private void inspectInventoryInternal(Container inv, StringBuilder sb) {

        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            if (item.isEmpty()) {
                continue;
            }

            printItemStack(item, sb);
        }
    }

    private void printItemStack(ItemStack item, StringBuilder sb) {
        Component message = Component.text("", NamedTextColor.GRAY);

        // 1. 识别物品中文名称
        Component itemNameComponent = Component.text(item.getHoverName().getString());

        Component amountComponent = Component.text(" x" + item.getCount(), NamedTextColor.DARK_GRAY);
        sendMessage(message.append(itemNameComponent).append(amountComponent), sb);

        // 2. 处理药水效果 (包括基础效果、自定义效果及不祥瓶组件 DataComponents.POTION_CONTENTS)
        PotionContents potionContents = item.get(DataComponents.POTION_CONTENTS);
        if (potionContents != null) {
            for (MobEffectInstance effect : potionContents.getAllEffects()) {
                int level = effect.getAmplifier() + 1;

                Component effectPrefix = Component.text("  - 效果: ", NamedTextColor.AQUA);
                Component effectName = Component.text(effect.getEffect().value().getDisplayName().getString());
                Component effectLevel = Component.text(" ").append(Component.translatable("enchantment.level." + level));

                String durationStr = formatDuration(effect.getDuration());
                Component durationComp = durationStr.isEmpty() ? Component.empty() : Component.text(" (" + durationStr + ")", NamedTextColor.GRAY);

                sendMessage(effectPrefix.append(effectName).append(effectLevel).append(durationComp), sb);
            }
        }

        if (item.has(DataComponents.OMINOUS_BOTTLE_AMPLIFIER)) {
            OminousBottleAmplifier amplifier = item.get(DataComponents.OMINOUS_BOTTLE_AMPLIFIER);
            int level = (amplifier != null ? amplifier.value() : 0) + 1; // NMS 中的 amplifier 从 0 开始 (0 为 I 级，1 为 II 级...)

            Component effectPrefix = Component.text("  - 效果: ", NamedTextColor.AQUA);
            Component effectName = Component.translatable("effect.minecraft.bad_omen"); // 不祥之兆翻译 Key
            Component effectLevel = Component.text(" ").append(Component.translatable("enchantment.level." + level));

            // 原版不祥之瓶饮用后固定给予 100 分钟 (120000 ticks) 不祥之兆效果
            String durationStr = formatDuration(120000);
            Component durationComp = Component.text(" (" + durationStr + ")", NamedTextColor.GRAY);

            sendMessage(effectPrefix.append(effectName).append(effectLevel).append(durationComp), sb);
        }

        // 3. 处理附魔 (普通附魔 + 附魔书 DataComponents.ENCHANTMENTS & STORED_ENCHANTMENTS)
        Map<Holder<Enchantment>, Integer> allEnchants = new HashMap<>();

        ItemEnchantments enchants = item.get(DataComponents.ENCHANTMENTS);
        if (enchants != null) {
            for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchants.entrySet()) {
                allEnchants.put(entry.getKey(), entry.getIntValue());
            }
        }

        ItemEnchantments storedEnchants = item.get(DataComponents.STORED_ENCHANTMENTS);
        if (storedEnchants != null) {
            for (Object2IntMap.Entry<Holder<Enchantment>> entry : storedEnchants.entrySet()) {
                allEnchants.put(entry.getKey(), entry.getIntValue());
            }
        }

        if (!allEnchants.isEmpty()) {
            for (Map.Entry<Holder<Enchantment>, Integer> entry : allEnchants.entrySet()) {
                Holder<Enchantment> enchant = entry.getKey();
                int level = entry.getValue();

                Component enchantPrefix = Component.text("  - 附魔: ", NamedTextColor.AQUA);
                sendMessage(enchantPrefix.append(Component.text(Enchantment.getFullname(enchant, level).getString())), sb);
            }
        }
    }


    /**
     * 编码至 Cache 零分配内存区
     */
    private int encodeToCache(String s) {
        bCacheBuffer.clear();
        encoder.reset();
        encoder.encode(CharBuffer.wrap(s), bCacheBuffer, true);
        encoder.flush(bCacheBuffer);
        return bCacheBuffer.position();
    }

    /**
     * 统一消息发送：如果是控制台，通过 NMS 系统消息发送
     */
    private void sendMessage(Component component, StringBuilder sb) {

        if (sb != null) {
            sb.append(PlainTextComponentSerializer.plainText().serialize(component)).append("\n");
        } else {
            adventure.console().sendMessage(component);
        }
    }

    private String formatDuration(int ticks) {
        if (ticks <= 0 || ticks == Integer.MAX_VALUE) return "";
        int totalSeconds = ticks / 20;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }

    private int[] lastcenter = new int[3];
    private boolean finding = false;

    private void playerInspect(MinecraftServer server) {

        if (finding) {
            return;
        }

        int blocksize = buffer.getInt(0x10004);

        if (blocksize == 0) {
            return;
        }

        int x = buffer.getInt(0x10008);
        int y = buffer.getInt(0x1000C);
        int z = buffer.getInt(0x10010);

        if (x == 0 && z == 0) {
            return;
        }

        int worldindex = buffer.getInt(0x10000);

        int centerCX = x >> 4;
        int centerCZ = z >> 4;

        if (centerCX == lastcenter[0] && centerCZ == lastcenter[1] && worldindex == lastcenter[2]) {
            return;
        }

        ServerLevel level;

        if (worldindex == 0) {
            level = server.getLevel(Level.OVERWORLD);
        } else if (worldindex == 1) {
            level = server.getLevel(Level.NETHER);
        } else if (worldindex == 2) {
            level = server.getLevel(Level.END);
        } else {
            level = null;
        }

        if (level == null) {
            return;
        }

        finding = true;

        lastcenter[0] = centerCX;
        lastcenter[1] = centerCZ;
        lastcenter[2] = worldindex;

        List<BlockPos> blocks = new ArrayList<>();

        int baseOffset = 0x10100;

        for (int i = 0; i < blocksize; i++) {
            int offset = baseOffset + (i * 12); // 每个坐标占用 12 字节 (x:4, y:4, z:4)

            int blockx = buffer.getInt(offset);
            int blocky = buffer.getInt(offset + 4);
            int blockz = buffer.getInt(offset + 8);

            blocks.add(new BlockPos(blockx, blocky, blockz));
        }

        AtomicInteger bufferOffset = new AtomicInteger(0x30100);
        AtomicInteger matchCount = new AtomicInteger(0);

        processBlockEntitiesBatch(level, blocks).whenCompleteAsync((either, exception) -> {

            try {
                if (!either) return;

                Component startTitle = Component.text("开始扫描：", NamedTextColor.GRAY).append(Component.text(centerCX + ", " + centerCZ + "(" + blocksize + ")", NamedTextColor.WHITE));
                sendMessage(startTitle, null);

                for (BlockPos pos : blocks) {
                    BlockEntity blockEntity = level.getBlockEntity(pos);

                    if (!(blockEntity instanceof Container container)) {
                        continue;
                    }

                    boolean founded = false;

                    for (int slot = 0; slot < container.getContainerSize(); slot++) {
                        ItemStack item = container.getItem(slot);
                        if (item.is(Items.ENCHANTED_GOLDEN_APPLE)) {
                            founded = true;
                            break;
                        }
                    }

                    if (founded) {

                        int currentOffset = bufferOffset.getAndAdd(12);

                        buffer.putInt(currentOffset, pos.getX());
                        buffer.putInt(currentOffset + 4, pos.getY());
                        buffer.putInt(currentOffset + 8, pos.getZ());


                        int count = matchCount.incrementAndGet();

                        Component matchTitle = Component.text("[" + count + "] 附魔金苹果：", NamedTextColor.GOLD).append(Component.text(pos.getX() + ", " + pos.getY() + ", " + pos.getZ(), NamedTextColor.WHITE));
                        sendMessage(matchTitle, null);
                    }

                }


                int finalCount = matchCount.get();
                buffer.putInt(0x30000, worldindex);
                buffer.putInt(0x30004, finalCount);

                Component resultTitle = Component.text("扫描结束，共找到附魔金苹果：", NamedTextColor.GRAY).append(Component.text(finalCount, NamedTextColor.GOLD));
                sendMessage(resultTitle, null);

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                finding = false;
            }

        }, level.getServer());


    }

    /**
     * 批量安全获取 BlockEntity
     */
    private void processBlockEntitiesBatch(ServerLevel level, List<BlockPos> blocks, Consumer<Boolean> callback) {

        if (blocks.isEmpty()) {
            callback.accept(false);
            return;
        }

        // 1. 将 BlockPos 按 ChunkPos 进行分组（去重区块）
        Map<ChunkPos, List<BlockPos>> chunkToBlocksMap = new HashMap<>();
        for (BlockPos pos : blocks) {
            int chunkX = pos.getX() >> 4;
            int chunkZ = pos.getZ() >> 4;
            ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
            chunkToBlocksMap.computeIfAbsent(chunkPos, k -> new ArrayList<>()).add(pos);
        }

        List<CompletableFuture<?>> chunkFutures = new ArrayList<>();

        // 2. 遍历所有需要的区块，只对未加载的区块发起 1 次加载请求
        for (ChunkPos chunkPos : chunkToBlocksMap.keySet()) {
            if (!level.hasChunk(chunkPos.x(), chunkPos.z())) {
                // 异步请求加载区块
                var future = level.getChunkSource().getChunkFuture(chunkPos.x(), chunkPos.z(), ChunkStatus.FULL, true);
                chunkFutures.add(future);
            }
        }

        // 3. 如果所有区块都已经加载了，直接在主线程同步处理
        if (chunkFutures.isEmpty()) {
            callback.accept(true);
            return;
        }

        // 4. 等待所有未加载的区块全部加载完毕后，只切回主线程执行【一次】批量收集任务
        CompletableFuture.allOf(chunkFutures.toArray(new CompletableFuture[0])).thenAcceptAsync(v -> {
            callback.accept(true);
        });
    }

    private CompletableFuture<Boolean> processBlockEntitiesBatch(ServerLevel level, List<BlockPos> blocks) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        processBlockEntitiesBatch(level, blocks, future::complete);
        return future;
    }

    /**
     * 检查区块是否加载；若未加载则异步加载，并在主线程安全地回调获取 BlockEntity
     */

    private void getBlockEntityAsync(ServerLevel level, BlockPos pos, Consumer<BlockEntity> callback) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;

        if (level.hasChunk(chunkX, chunkZ)) {
            BlockEntity be = level.getBlockEntity(pos);
            callback.accept(be);
            return;
        }

        // Mojmap 异步加载区块
        level.getChunkSource().getChunkFuture(chunkX, chunkZ, ChunkStatus.FULL, true).whenCompleteAsync((chunkResult, exception) -> {

            // 发生异常 或 加载失败 时，传入 null 并返回，防止卡死
            if (exception != null || chunkResult == null || !chunkResult.isSuccess()) {
                callback.accept(null);
                return;
            }

            chunkResult.ifSuccess(chunk -> {
                BlockEntity be = chunk.getBlockEntity(pos);
                callback.accept(be);
            });

        }, level.getServer());
    }

    private CompletableFuture<BlockEntity> getBlockEntityAsync(ServerLevel level, BlockPos pos) {
        CompletableFuture<BlockEntity> future = new CompletableFuture<>();
        getBlockEntityAsync(level, pos, future::complete);
        return future;
    }

    private void onTick(MinecraftServer server) {

        chestInspect(server);
        playerInspect(server);
        findElytra(server);
    }

}
