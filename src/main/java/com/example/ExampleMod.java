package com.example;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.kyori.adventure.platform.modcommon.MinecraftServerAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
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
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
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
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> this.adventure = null);

    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
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
        Map<BlockPos, List<BlockPos>> chunkToBlocksMap = new HashMap<>();
        for (BlockPos pos : blocks) {
            int chunkX = pos.getX() >> 4;
            int chunkZ = pos.getZ() >> 4;
            BlockPos chunkPos = new BlockPos(chunkX, 0, chunkZ);
            chunkToBlocksMap.computeIfAbsent(chunkPos, k -> new ArrayList<>()).add(pos);
        }

        List<CompletableFuture<?>> chunkFutures = new ArrayList<>();

        // 2. 遍历所有需要的区块，只对未加载的区块发起 1 次加载请求
        for (BlockPos chunkPos : chunkToBlocksMap.keySet()) {
            if (!level.hasChunk(chunkPos.getX(), chunkPos.getZ())) {
                // 异步请求加载区块
                var future = level.getChunkSource().getChunkFuture(chunkPos.getX(), chunkPos.getZ(), ChunkStatus.FULL, true);
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

            level.getServer().execute(() -> {
                // 加载成功，安全获取 BlockEntity
                BlockEntity be = level.getBlockEntity(pos);
                callback.accept(be);
            });

        });
    }

    private CompletableFuture<BlockEntity> getBlockEntityAsync(ServerLevel level, BlockPos pos) {
        CompletableFuture<BlockEntity> future = new CompletableFuture<>();
        getBlockEntityAsync(level, pos, future::complete);
        return future;
    }

    private void onTick(MinecraftServer server) {

        chestInspect(server);
        playerInspect(server);
    }

}
