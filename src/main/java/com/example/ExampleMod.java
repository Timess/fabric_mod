package com.example;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.kyori.adventure.platform.modcommon.MinecraftServerAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.OminousBottleAmplifier;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
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

public class ExampleMod implements ModInitializer {
    public static final String MOD_ID = "chestinspect";

    // This logger is used to write text to the console and the log file.
    // It is considered best practice to use your mod id as the logger's name.
    // That way, it's clear which mod wrote info, warnings, and errors.
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private volatile MinecraftServerAudiences adventure;

    private void setupServerTranslations() {
        InputStream is = getClass().getClassLoader().getResourceAsStream("zh_cn.json");

        if (is == null) {
            adventure.console().sendMessage(Component.text("未在 jar 包根目录找到 zh_cn.json，控制台将显示默认英文名称。", NamedTextColor.RED));
            return;
        }

        // 创建并加载自定义翻译器，注册到 GlobalTranslator
        ChineseTranslator translator = new ChineseTranslator();
        translator.load(is);

        GlobalTranslator.translator().addSource(translator);
        adventure.console().sendMessage(Component.text("成功加载服务端中文语言包！", NamedTextColor.GREEN));
    }

    public static int[] lastcheckxyzw = new int[4];
    public static MappedByteBuffer buffer;

    // 预分配写缓存区，避免创建 byte[]
    public static byte[] bCache = new byte[0xF000];
    public static StringBuilder gsb = new StringBuilder(0xF000);

    // 零 GC 字符编码器
    public static CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
    public static ByteBuffer bCacheBuffer = ByteBuffer.wrap(bCache);

    public static void memoryinit() throws IOException {
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

    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // Proceed with mild caution.

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            this.adventure = MinecraftServerAudiences.of(server);

            try {
                setupServerTranslations();
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


    public void chestInspect(MinecraftServer server) {
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

        List<ServerLevel> levels = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            levels.add(level);
        }

        if (worldindex >= levels.size() || worldindex < 0) {
            worldindex = 0;
        }

        if (levels.isEmpty()) return;
        ServerLevel level = levels.get(worldindex);

        BlockPos pos = new BlockPos(x, y, z);
        // NMS 直取 BlockEntity，避开 Bukkit getState() 的深拷贝开销
        BlockEntity blockEntity = level.getBlockEntity(pos);


        if (!(blockEntity instanceof RandomizableContainerBlockEntity lootable)) {
            Component title = Component.text("坐标：", NamedTextColor.GRAY).append(Component.text(x + ", " + y + ", " + z, NamedTextColor.YELLOW)).append(Component.text(" 不是 战利品 方块！", NamedTextColor.GRAY));
            sendMessage(title, null);
            return;
        }

        // 获取容器翻译 Key (如 block.minecraft.chest)
        String blockTranslationKey = blockEntity.getBlockState().getBlock().getDescriptionId();
        Component containerTypeComponent = Component.translatable(blockTranslationKey).color(NamedTextColor.WHITE);

        Component title = Component.text("开始检查位于 ", NamedTextColor.GRAY).append(Component.text(x + ", " + y + ", " + z, NamedTextColor.YELLOW)).append(Component.text(" 的 ", NamedTextColor.GRAY)).append(containerTypeComponent).append(Component.text(" 内容...", NamedTextColor.GRAY));
        sendMessage(title, null);

        List<ItemStack> generatedItems = predictLootTable(level, pos, lootable);

        gsb.setLength(0);

        if (generatedItems != null) {
            for (ItemStack stack : generatedItems) {
                printItemStack(stack, gsb);
            }
        }
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
    }

    public String inspectInventory(Container inv, StringBuilder sb) {
        inspectInventoryInternal(inv, sb);
        return sb != null ? sb.toString() : "";
    }

    public void inspectInventoryInternal(Container inv, StringBuilder sb) {

        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            if (item.isEmpty()) {
                continue;
            }

            printItemStack(item, sb);
        }
    }

    public void printItemStack(ItemStack item, StringBuilder sb) {
        Component message = Component.text("", NamedTextColor.GRAY);

        // 1. 识别物品中文名称
        Component itemNameComponent;
        if (item.has(DataComponents.CUSTOM_NAME)) {
            // 自定义显示名称
            itemNameComponent = Component.text(item.getHoverName().getString());
        } else {
            // 原生未命名物品，使用描述 ID 翻译
            itemNameComponent = Component.translatable(item.getItem().getDescriptionId());
        }

        Component amountComponent = Component.text(" x" + item.getCount(), NamedTextColor.DARK_GRAY);
        sendMessage(message.append(itemNameComponent).append(amountComponent), sb);

        // 2. 处理药水效果 (包括基础效果、自定义效果及不祥瓶组件 DataComponents.POTION_CONTENTS)
        PotionContents potionContents = item.get(DataComponents.POTION_CONTENTS);
        if (potionContents != null) {
            for (MobEffectInstance effect : potionContents.getAllEffects()) {
                int level = effect.getAmplifier() + 1;

                Component effectPrefix = Component.text("  - 效果: ", NamedTextColor.LIGHT_PURPLE);
                Component effectName = Component.translatable(effect.getEffect().value().getDescriptionId());
                Component effectLevel = Component.text(" " + toRoman(level), NamedTextColor.LIGHT_PURPLE);

                String durationStr = formatDuration(effect.getDuration());
                Component durationComp = durationStr.isEmpty() ? Component.empty() : Component.text(" (" + durationStr + ")", NamedTextColor.GRAY);

                sendMessage(effectPrefix.append(effectName).append(effectLevel).append(durationComp), sb);
            }
        }

        if (item.has(DataComponents.OMINOUS_BOTTLE_AMPLIFIER)) {
            OminousBottleAmplifier amplifier = item.get(DataComponents.OMINOUS_BOTTLE_AMPLIFIER);
            int level = (amplifier != null ? amplifier.value() : 0) + 1; // NMS 中的 amplifier 从 0 开始 (0 为 I 级，1 为 II 级...)

            Component effectPrefix = Component.text("  - 效果: ", NamedTextColor.LIGHT_PURPLE);
            Component effectName = Component.translatable("effect.minecraft.bad_omen"); // 不祥之兆翻译 Key
            Component effectLevel = Component.text(" " + toRoman(level), NamedTextColor.LIGHT_PURPLE);

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
                Component enchantName = Component.text("未知");
                if (enchant.value().description().getContents() instanceof TranslatableContents translatable) {
                    String key = translatable.getKey(); // 获取 "enchantment.minecraft.sharpness"
                    enchantName = Component.translatable(key);
                }

                Component enchantLevel = Component.text("", NamedTextColor.AQUA);

                if (level != 1 || enchant.value().getMaxLevel() != 1) {
                    enchantLevel = Component.text(" ").append(Component.translatable("enchantment.level." + level));
                }

                sendMessage(enchantPrefix.append(enchantName).append(enchantLevel), sb);
            }
        }
    }


    /**
     * 编码至 Cache 零分配内存区
     */
    public int encodeToCache(String s) {
        bCacheBuffer.clear();
        encoder.reset();
        encoder.encode(CharBuffer.wrap(s), bCacheBuffer, true);
        encoder.flush(bCacheBuffer);
        return bCacheBuffer.position();
    }

    /**
     * 统一消息发送：如果是控制台，通过 NMS 系统消息发送
     */
    public void sendMessage(Component component, StringBuilder sb) {
        // 在服务端使用中文 Locale 渲染组件
        Component rendered = GlobalTranslator.render(component, Locale.CHINA);

        if (sb != null) {
            sb.append(PlainTextComponentSerializer.plainText().serialize(rendered)).append("\n");
        } else {
            adventure.console().sendMessage(rendered);
        }
    }

    private String toRoman(int number) {
        if (number <= 0) return String.valueOf(number);
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] symbols = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            while (number >= values[i]) {
                number -= values[i];
                sb.append(symbols[i]);
            }
        }
        return sb.toString();
    }

    private String formatDuration(int ticks) {
        if (ticks <= 0 || ticks == Integer.MAX_VALUE) return "";
        int totalSeconds = ticks / 20;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }

    public int[] lastcenter = new int[3];

    public void playerInspect(MinecraftServer server) {

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

        lastcenter[0] = centerCX;
        lastcenter[1] = centerCZ;
        lastcenter[2] = worldindex;

        Component startTitle = Component.text("开始扫描：", NamedTextColor.GRAY).append(Component.text(centerCX + ", " + centerCZ + "(" + blocksize + ")", NamedTextColor.WHITE));
        sendMessage(startTitle, null);

        if (worldindex >= 3) worldindex = 0;

        int finalWorldindex = worldindex;

        List<ServerLevel> levels = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            levels.add(level);
        }

        if (finalWorldindex >= levels.size() || finalWorldindex < 0) return;
        ServerLevel level = levels.get(finalWorldindex);

        List<BlockPos> blocks = new ArrayList<>();

        int baseOffset = 0x10100;

        for (int i = 0; i < blocksize; i++) {
            int offset = baseOffset + (i * 12); // 每个坐标占用 12 字节 (x:4, y:4, z:4)

            int blockx = buffer.getInt(offset);
            int blocky = buffer.getInt(offset + 4);
            int blockz = buffer.getInt(offset + 8);

            blocks.add(new BlockPos(blockx, blocky, blockz));
        }


        int bufferOffset = 0x30100;
        int matchCount = 0;

        for (BlockPos pos : blocks) {
            BlockEntity blockEntity = level.getBlockEntity(pos);

            if (!(blockEntity instanceof RandomizableContainerBlockEntity lootable)) {
                Component title = Component.text("坐标：", NamedTextColor.GRAY).append(Component.text(pos.getX() + ", " + pos.getY() + ", " + pos.getZ(), NamedTextColor.YELLOW)).append(Component.text(" 不是 战利品 方块！", NamedTextColor.GRAY));
                sendMessage(title, null);
                continue;
            }

            List<ItemStack> generatedItems = predictLootTable(level, pos, lootable);

            // 判定容器内部（包括嵌套潜影盒/收纳袋）是否含有附魔金苹果
            if (generatedItems != null) {

                boolean founded = false;
                for (ItemStack stack : generatedItems) {
                    if (stack.is(Items.ENCHANTED_GOLDEN_APPLE)) {
                        founded = true;
                        break;
                    }
                }

                if (founded) {

                    int currentOffset = bufferOffset;
                    bufferOffset += 12;

                    buffer.putInt(currentOffset, pos.getX());
                    buffer.putInt(currentOffset + 4, pos.getY());
                    buffer.putInt(currentOffset + 8, pos.getZ());

                    matchCount++;
                    int count = matchCount;

                    Component matchTitle = Component.text("[" + count + "] 附魔金苹果：", NamedTextColor.GOLD).append(Component.text(pos.getX() + ", " + pos.getY() + ", " + pos.getZ(), NamedTextColor.WHITE));
                    sendMessage(matchTitle, null);
                }
            }

        }


        int finalCount = matchCount;
        buffer.putInt(0x30000, finalWorldindex);
        buffer.putInt(0x30004, finalCount);

        Component resultTitle = Component.text("扫描结束，共找到附魔金苹果：", NamedTextColor.GRAY).append(Component.text(finalCount, NamedTextColor.GOLD));
        sendMessage(resultTitle, null);
    }

    public List<ItemStack> predictLootTable(ServerLevel level, BlockPos pos, RandomizableContainerBlockEntity lootable) {
        if (lootable.getLootTable() == null) return null;

        // 1. 获取服务器注册的战利品表（适配 1.20.5+ NMS API，旧版本可改用 level.getServer().getLootData()）
        var lootTable = level.getServer().reloadableRegistries().getLootTable(lootable.getLootTable());

        // 2. 构建模拟生成上下文
        LootParams params = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos)).create(LootContextParamSets.CHEST);

        // 3. 传入箱子的固有随机种子（lootTableSeed），纯函数式计算出生成的物品列表（不影响任何实体状态）

        return lootTable.getRandomItems(params, lootable.getLootTableSeed());
    }

    public void onTick(MinecraftServer server) {

        chestInspect(server);
        playerInspect(server);
    }

}
