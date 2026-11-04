package org.example_1.entry_traversal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.Reader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chat helper commands for looking up enchantments and potion effects:
 * <ul>
 *     <li>{@code /enchantlist [page]} - enchantments with translated name, level range and applicable items.</li>
 *     <li>{@code /enchantlist -a [page]} - same list, restricted to enchantments the main-hand item accepts.</li>
 *     <li>{@code /effectlist [page]} - potion effects with translated name and level range.</li>
 *     <li>{@code /enchantlist help} and {@code /effectlist help} - in-game usage notes.</li>
 * </ul>
 * Results are split into pages of {@value #PAGE_SIZE} entries with clickable previous/next buttons.
 * Every entry line is clickable and inserts a ready-to-use command into the chat box.
 *
 * <p>Other mods can reuse the listing code through {@link #showEnchantments} and {@link #showEffects}.</p>
 */
@Mod.EventBusSubscriber(modid = Entry_Traversal.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class InfoCommands {

    /** Item names shown directly on the chat line. */
    private static final int ITEMS_ON_LINE = 4;
    /** Item names shown in the hover tooltip before it is truncated. */
    private static final int ITEMS_IN_TOOLTIP = 32;
    /** Entries (enchantments or effects) shown per chat page. */
    private static final int PAGE_SIZE = 15;
    /** EnchantmentLevelBreak registers /cenchant, which ignores the vanilla item/level limits. */
    private static final String CENCHANT_MOD_ID = "enchantmentlevelbreak";
    /** Prefix of the bundled description keys, see {@link #descriptionFor}. */
    private static final String DESC_KEY_PREFIX = "entry_traversal.desc.";
    /**
     * Modded registry entries that have a bundled description in this mod's language files.
     * Modded language keys live on the client, so the server cannot check whether an arbitrary
     * key exists; only entries listed here are safe to describe.
     */
    private static final Set<String> MODDED_DESCRIPTIONS = Set.of(
            "brewinandchewin:intoxication",
            "brewinandchewin:raging",
            "brewinandchewin:satisfaction",
            "brewinandchewin:sweet_heart",
            "brewinandchewin:tipsy",
            "farmersdelight:backstabbing",
            "farmersdelight:comfort",
            "farmersdelight:nourishment",
            "farmersrespite:caffeinated",
            "mynethersdelight:b_pungent",
            "mynethersdelight:g_pungent",
            "mynethersdelight:poaching"
    );
    /** Description key suffixes used by Farmer's Delight addons, in priority order. */
    private static final List<String> MOD_DESCRIPTION_SUFFIXES = List.of(".desc", ".description");
    /** Cached {@code en_us} language keys per mod namespace; an empty set means unavailable. */
    private static final Map<String, Set<String>> MOD_LANG_KEYS = new ConcurrentHashMap<>();

    private InfoCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("enchantlist")
                        .executes(context -> listEnchantments(context, false, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> listEnchantments(context, false,
                                        IntegerArgumentType.getInteger(context, "page"))))
                        .then(Commands.literal("-a")
                                .executes(context -> listEnchantments(context, true, 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(context -> listEnchantments(context, true,
                                                IntegerArgumentType.getInteger(context, "page")))))
                        .then(Commands.literal("help")
                                .executes(context -> {
                                    sendEnchantHelp(context.getSource());
                                    return 1;
                                })));
        event.getDispatcher().register(
                Commands.literal("effectlist")
                        .executes(context -> listEffects(context, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> listEffects(context,
                                        IntegerArgumentType.getInteger(context, "page"))))
                        .then(Commands.literal("help")
                                .executes(context -> {
                                    sendEffectHelp(context.getSource());
                                    return 1;
                                })));
    }

    private static void sendEnchantHelp(CommandSourceStack source) {
        source.sendSystemMessage(Component.translatable("enchantlist.help.title").withStyle(ChatFormatting.GOLD));
        source.sendSystemMessage(Component.translatable("enchantlist.help.all").withStyle(ChatFormatting.GRAY));
        source.sendSystemMessage(Component.translatable("enchantlist.help.applicable").withStyle(ChatFormatting.GRAY));
        source.sendSystemMessage(Component.translatable("enchantlist.help.tip", PAGE_SIZE)
                .withStyle(ChatFormatting.GRAY));
    }

    private static void sendEffectHelp(CommandSourceStack source) {
        source.sendSystemMessage(Component.translatable("effectlist.help.title").withStyle(ChatFormatting.GOLD));
        source.sendSystemMessage(Component.translatable("effectlist.help.all").withStyle(ChatFormatting.GRAY));
        source.sendSystemMessage(Component.translatable("effectlist.help.tip", PAGE_SIZE)
                .withStyle(ChatFormatting.GRAY));
    }

    // ------------------------------------------------------------------
    // /enchantlist
    // ------------------------------------------------------------------

    private static int listEnchantments(CommandContext<CommandSourceStack> context, boolean onlyApplicable,
                                        int requestedPage) {
        return showEnchantments(context.getSource(), onlyApplicable, requestedPage);
    }

    /**
     * Sends one page of the enchantment list to the given source. Other mods may call this directly.
     *
     * @param source         source that receives the chat lines
     * @param onlyApplicable when {@code true}, only enchantments the source entity's main-hand item accepts
     * @param requestedPage  1-based page number; out-of-range values are clamped to a valid page
     * @return the total number of enchantments in the list, not just the entries on this page
     */
    public static int showEnchantments(CommandSourceStack source, boolean onlyApplicable, int requestedPage) {
        // The -a flag restricts the list to enchantments the held item accepts.
        ItemStack held = source.getEntity() instanceof LivingEntity living
                ? living.getMainHandItem()
                : ItemStack.EMPTY;

        if (onlyApplicable && held.isEmpty()) {
            source.sendSystemMessage(Component.translatable("enchantlist.no_held").withStyle(ChatFormatting.GRAY));
            return 0;
        }

        List<Map.Entry<ResourceKey<Enchantment>, Enchantment>> enchantments =
                ForgeRegistries.ENCHANTMENTS.getEntries().stream()
                        .filter(entry -> !onlyApplicable || entry.getValue().canEnchant(held))
                        .sorted(Comparator.comparing(
                                (Map.Entry<ResourceKey<Enchantment>, Enchantment> entry) ->
                                        entry.getKey().location().toString()))
                        .toList();

        // Item stacks are created once and reused for every enchantment check.
        List<ItemStack> stacks = new ArrayList<>();
        for (Item item : ForgeRegistries.ITEMS.getValues()) {
            ItemStack stack = new ItemStack(item);
            if (!stack.isEmpty()) {
                stacks.add(stack);
            }
        }

        if (enchantments.isEmpty()) {
            source.sendSystemMessage(Component.translatable("enchantlist.none", held.getHoverName())
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }

        if (onlyApplicable) {
            source.sendSystemMessage(Component.translatable("enchantlist.header.filtered",
                            held.getHoverName(), enchantments.size())
                    .withStyle(ChatFormatting.GOLD));
        } else {
            source.sendSystemMessage(Component.translatable("enchantlist.header", enchantments.size())
                    .withStyle(ChatFormatting.GOLD));
        }

        // Prefer EnchantmentLevelBreak's /cenchant when it is actually registered.
        String cEnchantCommand = availableCEnchantCommand(source);

        int totalPages = pageCount(enchantments.size());
        int page = clampPage(requestedPage, totalPages);
        int from = (page - 1) * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, enchantments.size());

        for (int i = from; i < to; i++) {
            Map.Entry<ResourceKey<Enchantment>, Enchantment> entry = enchantments.get(i);
            source.sendSystemMessage(enchantmentLine(i + 1, entry.getKey().location().toString(), entry.getValue(),
                    stacks, cEnchantCommand));
        }

        if (totalPages > 1) {
            source.sendSystemMessage(pageNavigation(
                    "/enchantlist" + (onlyApplicable ? " -a" : ""), page, totalPages));
        }

        return enchantments.size();
    }

    /**
     * @return {@code "cenchant"} (or the legacy {@code "cenchent"} spelling) when the
     * EnchantmentLevelBreak command is available, otherwise {@code null} to fall back to vanilla /enchant.
     */
    private static String availableCEnchantCommand(CommandSourceStack source) {
        if (!ModList.get().isLoaded(CENCHANT_MOD_ID)) {
            return null;
        }
        CommandDispatcher<CommandSourceStack> dispatcher = source.getServer().getCommands().getDispatcher();
        if (dispatcher.getRoot().getChild("cenchant") != null) {
            return "cenchant";
        }
        if (dispatcher.getRoot().getChild("cenchent") != null) {
            return "cenchent";
        }
        return null;
    }

    private static Component enchantmentLine(int index, String id, Enchantment enchantment, List<ItemStack> stacks,
                                             String cEnchantCommand) {
        int minLevel = enchantment.getMinLevel();
        int maxLevel = enchantment.getMaxLevel();

        List<Item> items = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (enchantment.canEnchant(stack)) {
                items.add(stack.getItem());
            }
        }
        items.sort(Comparator.comparing((Item item) -> {
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
            return key == null ? "" : key.toString();
        }));

        String command = cEnchantCommand != null
                ? "/" + cEnchantCommand + " " + id + " " + maxLevel
                : "/enchant @s " + id + " " + maxLevel;
        String commandLabel = "/" + (cEnchantCommand != null ? cEnchantCommand : "enchant");

        MutableComponent hover = Component.empty()
                .append(Component.translatable("enchantlist.hover.id", id))
                .append("\n")
                .append(Component.translatable("enchantlist.hover.levels", minLevel, maxLevel));

        // Vanilla and supported modded entries get a short explanation of what the enchantment does.
        Component description = descriptionFor("enchantment", id, enchantment.getDescriptionId());
        if (description != null) {
            hover.append("\n").append(Component.translatable("enchantlist.hover.desc", description)
                    .withStyle(ChatFormatting.GRAY));
        }

        hover.append("\n")
                .append(Component.translatable("enchantlist.hover.items", items.size()))
                .append("\n")
                .append(itemNames(items, ITEMS_IN_TOOLTIP));

        if (items.size() > ITEMS_IN_TOOLTIP) {
            hover.append(Component.translatable("enchantlist.hover.more", items.size() - ITEMS_IN_TOOLTIP));
        }

        hover.append("\n")
                .append(Component.translatable("enchantlist.hover.click", commandLabel)
                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));

        MutableComponent line = Component.empty()
                .append(Component.literal(index + ". ").withStyle(ChatFormatting.GRAY))
                .append(Component.translatable(enchantment.getDescriptionId()).withStyle(ChatFormatting.AQUA))
                .append(" ")
                .append(Component.translatable("enchantlist.levels", minLevel, maxLevel)
                        .withStyle(ChatFormatting.GRAY))
                .append(" ")
                .append(Component.translatable("enchantlist.items.label").withStyle(ChatFormatting.GRAY))
                .append(itemNames(items, ITEMS_ON_LINE).withStyle(ChatFormatting.GREEN));

        if (items.size() > ITEMS_ON_LINE) {
            line.append(Component.translatable("enchantlist.items.more", items.size() - ITEMS_ON_LINE)
                    .withStyle(ChatFormatting.GRAY));
        }

        applyClick(line, command, hover);
        return line;
    }

    // ------------------------------------------------------------------
    // /effectlist
    // ------------------------------------------------------------------

    private static int listEffects(CommandContext<CommandSourceStack> context, int requestedPage) {
        return showEffects(context.getSource(), requestedPage);
    }

    /**
     * Sends one page of the potion effect list to the given source. Other mods may call this directly.
     *
     * @param source        source that receives the chat lines
     * @param requestedPage 1-based page number; out-of-range values are clamped to a valid page
     * @return the total number of effects in the list, not just the entries on this page
     */
    public static int showEffects(CommandSourceStack source, int requestedPage) {
        List<Map.Entry<ResourceKey<MobEffect>, MobEffect>> effects =
                ForgeRegistries.MOB_EFFECTS.getEntries().stream()
                        .sorted(Comparator.comparing(
                                (Map.Entry<ResourceKey<MobEffect>, MobEffect> entry) ->
                                        entry.getKey().location().toString()))
                        .toList();

        Map<MobEffect, Integer> potionLevels = potionMaxLevels();

        source.sendSystemMessage(Component.translatable("effectlist.header", effects.size())
                .withStyle(ChatFormatting.GOLD));

        int totalPages = pageCount(effects.size());
        int page = clampPage(requestedPage, totalPages);
        int from = (page - 1) * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, effects.size());

        for (int i = from; i < to; i++) {
            Map.Entry<ResourceKey<MobEffect>, MobEffect> entry = effects.get(i);
            source.sendSystemMessage(effectLine(i + 1, entry.getKey().location().toString(), entry.getValue(),
                    potionLevels.get(entry.getValue())));
        }

        if (totalPages > 1) {
            source.sendSystemMessage(pageNavigation("/effectlist", page, totalPages));
        }

        return effects.size();
    }

    /** Highest level (amplifier + 1) each effect reaches across all registered potions. */
    private static Map<MobEffect, Integer> potionMaxLevels() {
        Map<MobEffect, Integer> levels = new HashMap<>();
        for (Potion potion : ForgeRegistries.POTIONS.getValues()) {
            for (MobEffectInstance instance : potion.getEffects()) {
                levels.merge(instance.getEffect(), instance.getAmplifier() + 1, Math::max);
            }
        }
        return levels;
    }

    private static Component effectLine(int index, String id, MobEffect effect, Integer potionMaxLevel) {
        MobEffectCategory category = effect.getCategory();

        // Potions define the highest level an effect reaches; effects without a potion use the command cap.
        int minLevel = 1;
        int maxLevel = potionMaxLevel == null ? 256 : potionMaxLevel;

        MutableComponent hover = Component.empty()
                .append(Component.translatable("effectlist.hover.id", id))
                .append("\n")
                .append(Component.translatable("effectlist.hover.levels", minLevel, maxLevel))
                .append("\n")
                .append(Component.translatable("effectlist.hover.category", categoryName(category)));

        // Vanilla and supported modded entries get a short explanation of what the effect does.
        Component description = descriptionFor("effect", id, effect.getDescriptionId());
        if (description != null) {
            hover.append("\n").append(Component.translatable("effectlist.hover.desc", description)
                    .withStyle(ChatFormatting.GRAY));
        }

        hover.append("\n")
                .append(Component.translatable("effectlist.hover.click")
                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));

        MutableComponent line = Component.empty()
                .append(Component.literal(index + ". ").withStyle(ChatFormatting.GRAY))
                .append(effect.getDisplayName().copy().withStyle(category.getTooltipFormatting()))
                .append(" ")
                .append(Component.translatable("effectlist.levels", minLevel, maxLevel)
                        .withStyle(ChatFormatting.GRAY))
                .append(" ")
                .append(categoryName(category).copy().withStyle(ChatFormatting.GRAY));

        if (effect.isInstantenous()) {
            line.append(" ")
                    .append(Component.translatable("effectlist.instant").withStyle(ChatFormatting.GRAY));
        }

        applyClick(line, "/effect give @s " + id, hover);
        return line;
    }

    private static Component categoryName(MobEffectCategory category) {
        return switch (category) {
            case BENEFICIAL -> Component.translatable("effectlist.category.beneficial");
            case HARMFUL -> Component.translatable("effectlist.category.harmful");
            case NEUTRAL -> Component.translatable("effectlist.category.neutral");
        };
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static int pageCount(int total) {
        return Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private static int clampPage(int requested, int totalPages) {
        return Math.min(Math.max(1, requested), totalPages);
    }

    private static Component pageNavigation(String command, int page, int totalPages) {
        MutableComponent nav = Component.empty();
        if (page > 1) {
            nav.append(pageButton("pagination.prev", command + " " + (page - 1))).append(" ");
        }
        nav.append(Component.translatable("pagination.page", page, totalPages).withStyle(ChatFormatting.GRAY));
        if (page < totalPages) {
            nav.append(" ").append(pageButton("pagination.next", command + " " + (page + 1)));
        }
        return nav;
    }

    private static Component pageButton(String translationKey, String command) {
        return Component.translatable(translationKey)
                .withStyle(Style.EMPTY
                        .withColor(ChatFormatting.YELLOW)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("pagination.hover", command))));
    }

    /**
     * Returns the description of a registry entry, or {@code null} when none is available.
     * Vanilla and {@link #MODDED_DESCRIPTIONS curated modded entries} use the descriptions bundled
     * in this mod. Other modded entries are resolved from the providing mod's own {@code en_us}
     * language file, using the {@code .desc} / {@code .description} suffix convention. The server
     * can read that file from the mod jar, so a missing key never leaks a raw translation key into
     * the tooltip.
     */
    private static Component descriptionFor(String kind, String id, String descriptionId) {
        if (id.startsWith("minecraft:") || MODDED_DESCRIPTIONS.contains(id)) {
            return Component.translatable(DESC_KEY_PREFIX + kind + "." + id.replace(':', '.'));
        }
        int colon = id.indexOf(':');
        if (colon < 0) {
            return null;
        }
        return modDescription(id.substring(0, colon), descriptionId);
    }

    /** Looks for a description key shipped by the mod that owns the entry. */
    private static Component modDescription(String namespace, String descriptionId) {
        Set<String> keys = modLangKeys(namespace);
        for (String suffix : MOD_DESCRIPTION_SUFFIXES) {
            String key = descriptionId + suffix;
            if (keys.contains(key)) {
                return Component.translatable(key);
            }
        }
        return null;
    }

    /** Loads and caches the {@code en_us} language keys of a mod for description lookups. */
    private static Set<String> modLangKeys(String namespace) {
        return MOD_LANG_KEYS.computeIfAbsent(namespace, ns -> {
            try {
                var modFileInfo = ModList.get().getModFileById(ns);
                if (modFileInfo == null) {
                    return Set.of();
                }
                var path = modFileInfo.getFile().findResource("assets", ns, "lang", "en_us.json");
                if (path == null) {
                    return Set.of();
                }
                try (Reader reader = Files.newBufferedReader(path)) {
                    JsonObject lang = JsonParser.parseReader(reader).getAsJsonObject();
                    return Set.copyOf(lang.keySet());
                }
            } catch (Exception ignored) {
                return Set.of();
            }
        });
    }

    /** Joins the first {@code limit} item display names with commas. */
    private static MutableComponent itemNames(List<Item> items, int limit) {
        MutableComponent result = Component.empty();
        int shown = Math.min(limit, items.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                result.append(", ");
            }
            result.append(items.get(i).getDescription());
        }
        return result;
    }

    /**
     * Adds the click/hover behaviour to the whole line. The style is applied to every nested
     * component as well, otherwise clicking on a coloured part of the line would do nothing.
     */
    private static void applyClick(MutableComponent line, String command, Component hover) {
        Style style = Style.EMPTY
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover));
        applyStyle(line, style);
    }

    private static void applyStyle(MutableComponent component, Style style) {
        component.withStyle(style);
        for (Component sibling : component.getSiblings()) {
            if (sibling instanceof MutableComponent mutable) {
                applyStyle(mutable, style);
            }
        }
    }
}
