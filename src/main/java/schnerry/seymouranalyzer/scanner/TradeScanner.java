package schnerry.seymouranalyzer.scanner;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import schnerry.seymouranalyzer.SeymourAnalyzer;
import schnerry.seymouranalyzer.SeymourAnalyzerClient;
import schnerry.seymouranalyzer.analyzer.ColorAnalyzer;
import schnerry.seymouranalyzer.analyzer.PatternDetector;
import schnerry.seymouranalyzer.data.ArmorPiece;
import schnerry.seymouranalyzer.data.CollectionManager;
import schnerry.seymouranalyzer.util.ItemStackUtils;
import schnerry.seymouranalyzer.util.StringUtility;

import java.util.*;

/**
 * Monitors Hypixel player-trade screens ("Trading with …") every 5 ticks.
 * When the trade-completion chat message arrives, posts a chat notification with
 * two clickable buttons:
 *   [Add X received pieces]   → /seymour trade add  <badgeId>
 *   [Remove Y traded pieces]  → /seymour trade remove <badgeId>
 * Classification heuristic:
 *   Pieces whose UUID is already in the DB  = outgoing  (player is trading them away)
 *   Pieces whose UUID is NOT in the DB      = incoming  (coming from the other player)
 */
public class TradeScanner {

    private static final String TRADE_TITLE_PREFIX = "Trading with ";
    /** Hypixel trade screen title starts with "You" followed by spaces and the other player's name. */
    private static final String TRADE_TITLE_YOU = "You";
    /** Hypixel chat message that confirms a trade was finalised. */
    private static final String TRADE_COMPLETE_MSG = "Trade completed with ";
    /** How long after the trade screen closes we still accept the completion chat (ms). */
    private static final long TRADE_COMPLETE_WINDOW_MS = 15_000;

    private static TradeScanner INSTANCE;

    // ── Live state ────────────────────────────────────────────────────────────
    private boolean tradeActive       = false;
    private String  tradePartner      = "";
    private int     tickCounter       = 0;
    private boolean completionPending = false; // chat fired before screen closed
    private long    lastTradeClosedMs = 0;     // timestamp of last trade-screen close

    /** UUID → pre-analysed ArmorPiece for pieces NOT in player inventory when trade opened (incoming). */
    private final Map<String, ArmorPiece> incomingPieces = new LinkedHashMap<>();
    /** UUIDs of pieces that WERE in the player's inventory when trade opened (outgoing). */
    private final Set<String> outgoingUuids = new LinkedHashSet<>();
    /** Snapshot of all item UUIDs in player inventory at the moment the trade screen opened. */
    private final Set<String> playerInventorySnapshot = new HashSet<>();

    // ── Pending-add badge storage ─────────────────────────────────────────────
    // Maps temporary badgeId → list of pre-analysed ArmorPieces to add on demand.
    private static final int MAX_TRADE_BADGES = 10;
    private final Deque<TradeBadge> addBadgeHistory  = new ArrayDeque<>();
    private final Deque<ScanBadge>  removeBadgeHistory = new ArrayDeque<>();

    // ── Singleton ─────────────────────────────────────────────────────────────
    private TradeScanner() {}

    public static TradeScanner getInstance() {
        if (INSTANCE == null) INSTANCE = new TradeScanner();
        return INSTANCE;
    }

    // ── Registration ──────────────────────────────────────────────────────────
    public static void register() {
        TradeScanner ts = getInstance();

        // Chat event – fires when server sends any game message
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            String text = message.getString();
            // Log all chat when a recent trade was detected (for debugging)
            TradeScanner inst = getInstance();
            boolean recentTrade = inst.tradeActive ||
                (System.currentTimeMillis() - inst.lastTradeClosedMs) < TRADE_COMPLETE_WINDOW_MS;
            if (text.contains(TRADE_COMPLETE_MSG)) {
                inst.onTradeCompleteChat(Minecraft.getInstance());
            }
        });

        SeymourAnalyzer.LOGGER.info("Registered TradeScanner chat listener");
    }

    // ── Tick (called from SeymourAnalyzerClient every client tick) ─────────────
    public void tick(Minecraft mc) {
        if (mc.player == null) return;

        if (mc.gui.screen() instanceof AbstractContainerScreen<?> screen) {
            String title = screen.getTitle().getString();
            // Strip Minecraft formatting codes (§X) for reliable matching
            String cleanTitle = title.replaceAll("§.", "");

            // Log every new container screen title (once per screen object) for debugging
            if (screen != lastLoggedScreen) {
                lastLoggedScreen = screen;
            }

            if (cleanTitle.contains(TRADE_TITLE_PREFIX.trim()) || isHypixelTradeTitle(cleanTitle)) {
                // Extract partner name
                String partner;
                if (cleanTitle.contains(TRADE_TITLE_PREFIX.trim())) {
                    // "Trading with [rank] name" format
                    int idx = cleanTitle.indexOf(TRADE_TITLE_PREFIX.trim());
                    partner = cleanTitle.substring(idx + TRADE_TITLE_PREFIX.trim().length()).trim();
                } else {
                    // "You          playername" format – last token after whitespace
                    String[] parts = cleanTitle.trim().split("\\s+");
                    partner = parts.length > 1 ? parts[parts.length - 1] : cleanTitle.trim();
                }

                if (!tradeActive) {
                    tradeActive = true;
                    tradePartner = partner;
                    completionPending = false;
                    incomingPieces.clear();
                    outgoingUuids.clear();
                    tickCounter = 0;

                    // Snapshot player inventory so we can tell "mine" from "theirs"
                    playerInventorySnapshot.clear();
                    if (mc.player != null) {
                        var inv = mc.player.getInventory();
                        for (int si = 0; si < inv.getContainerSize(); si++) {
                            ItemStack invStack = inv.getItem(si);
                            if (!invStack.isEmpty()) {
                                String invUuid = ItemStackUtils.getOrCreateItemUUID(invStack);
                                if (invUuid != null) playerInventorySnapshot.add(invUuid);
                            }
                        }
                    }
                }
                tickCounter++;
                if (tickCounter % 5 == 0) {
                    scanTradeSlots(mc, screen);
                }
                // If chat fired before the screen update reached us
                if (completionPending) {
                    fireTradeMessage(mc);
                }
                return;
            }
        }

        // Screen is no longer a trade screen
        if (tradeActive) {
            tradeActive = false;
            lastTradeClosedMs = System.currentTimeMillis();
            // If chat confirmation already arrived, fire now
            if (completionPending) {
                fireTradeMessage(mc);
            }
        }
    }

    // Screen reference used purely for "log once per screen" deduplication
    private AbstractContainerScreen<?> lastLoggedScreen = null;

    /**
     * Detects Hypixel's trade screen whose title is "You          PlayerName"
     * (left = "You", right = partner name, separated by many spaces).
     */
    public static boolean isHypixelTradeTitle(String cleanTitle) {
        if (!cleanTitle.startsWith(TRADE_TITLE_YOU)) return false;
        // Must have at least 2 spaces (the multi-space separator) and a trailing word
        String after = cleanTitle.substring(TRADE_TITLE_YOU.length());
        return after.contains("  ") && !after.trim().isEmpty();
    }

    // ── Slot scanning ─────────────────────────────────────────────────────────
    private void scanTradeSlots(Minecraft mc, AbstractContainerScreen<?> screen) {
        incomingPieces.clear();
        outgoingUuids.clear();

        List<Slot> slots = screen.getMenu().slots;
        int containerSlotCount = Math.max(0, slots.size() - 36);
        // If trade screen has no "extra" slots beyond player inv, scan everything
        int scanLimit = containerSlotCount > 0 ? containerSlotCount : slots.size();


        for (int i = 0; i < scanLimit; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;

            String itemName = stack.getHoverName().getString();
            if (!StringUtility.isSeymourArmor(itemName)) continue;

            String uuid = ItemStackUtils.getOrCreateItemUUID(stack);
            if (uuid == null) continue;

            // Classification with duplicate-safe behavior:
            // - Mine at trade open (snapshot) => outgoing candidate (remove if in DB)
            // - Not mine + not in DB           => incoming candidate (add)
            // - Not mine + already in DB       => ignore (received duplicate UUID)
            boolean isMineBySnapshot = playerInventorySnapshot.contains(uuid);
            boolean existsInDb = CollectionManager.getInstance().hasPiece(uuid);

            if (isMineBySnapshot) {
                if (existsInDb) {
                    outgoingUuids.add(uuid);
                }
            } else if (!existsInDb) {
                if (!incomingPieces.containsKey(uuid)) {
                    ArmorPiece piece = analyseStack(stack, itemName, uuid);
                    if (piece != null) {
                        incomingPieces.put(uuid, piece);
                    }
                }
            }
        }
    }

    /** Full analysis pipeline, mirrored from ChestScanner.scanChestContents. */
    private ArmorPiece analyseStack(ItemStack stack, String itemName, String uuid) {
        try {
            String hex = ItemStackUtils.extractHex(stack);
            if (hex == null) return null;

            ColorAnalyzer.AnalysisResult analysis = ColorAnalyzer.getInstance().analyzeArmorColor(hex, itemName);
            if (analysis == null) return null;

            ColorAnalyzer.ColorMatch best = analysis.bestMatch();
            int itemRgb   = Integer.parseInt(hex, 16);
            int targetRgb = Integer.parseInt(best.targetHex(), 16);
            int absDist   = Math.abs(((itemRgb >> 16) & 0xFF) - ((targetRgb >> 16) & 0xFF))
                          + Math.abs(((itemRgb >>  8) & 0xFF) - ((targetRgb >>  8) & 0xFF))
                          + Math.abs(( itemRgb        & 0xFF) - ( targetRgb        & 0xFF));

            String wordMatch      = PatternDetector.getInstance().detectWordMatch(hex);
            String specialPattern = PatternDetector.getInstance().detectPattern(hex);

            List<ArmorPiece.ColorMatch> top3 = new ArrayList<>();
            for (int m = 0; m < 3 && m < analysis.top3Matches().size(); m++) {
                ColorAnalyzer.ColorMatch cm = analysis.top3Matches().get(m);
                int cmRgb = Integer.parseInt(cm.targetHex(), 16);
                int cmDist = Math.abs(((itemRgb >> 16) & 0xFF) - ((cmRgb >> 16) & 0xFF))
                           + Math.abs(((itemRgb >>  8) & 0xFF) - ((cmRgb >>  8) & 0xFF))
                           + Math.abs(( itemRgb        & 0xFF) - ( cmRgb        & 0xFF));
                top3.add(new ArmorPiece.ColorMatch(cm.name(), cm.targetHex(), cm.deltaE(), cmDist, cm.tier()));
            }

            ArmorPiece piece = new ArmorPiece();
            piece.setPieceName(StringUtility.removeFormatting(itemName));
            piece.setUuid(uuid);
            piece.setHexcode(hex);
            piece.setSpecialPattern(specialPattern);
            piece.setBestMatch(new ArmorPiece.BestMatch(
                best.name(), best.targetHex(), best.deltaE(), absDist, analysis.tier()));
            piece.setAllMatches(top3);
            piece.setWordMatch(wordMatch);
            piece.setTimestamp(System.currentTimeMillis());
            return piece;
        } catch (Exception e) {
            SeymourAnalyzer.LOGGER.warn("[TradeScanner] Failed to analyse stack: {}", e.getMessage());
            return null;
        }
    }

    // ── Chat completion handler ────────────────────────────────────────────────
    public void onTradeCompleteChat(Minecraft mc) {

        if (!tradeActive) {
            // Screen already closed – fire if within the acceptance window and data exists
            long age = System.currentTimeMillis() - lastTradeClosedMs;
            if (age <= TRADE_COMPLETE_WINDOW_MS && (!incomingPieces.isEmpty() || !outgoingUuids.isEmpty())) {
                fireTradeMessage(mc);
            }
            return;
        }
        completionPending = true;
        // Do a final scan right now so data is fresh
        if (mc.gui.screen() instanceof AbstractContainerScreen<?> screen) {
            String cleanTitle = screen.getTitle().getString().replaceAll("§.", "");
            if (cleanTitle.contains(TRADE_TITLE_PREFIX.trim()) || isHypixelTradeTitle(cleanTitle)) {
            scanTradeSlots(mc, screen);
            }
        }
        fireTradeMessage(mc);
    }

    private void fireTradeMessage(Minecraft mc) {
        completionPending = false;

        if (mc.player == null) return;
        if (incomingPieces.isEmpty() && outgoingUuids.isEmpty()) {
            // No Seymour pieces in this trade
            tradeActive = false;
            return;
        }

        MutableComponent msg = Component.literal(
            "§a[Seymour Analyzer] §7Detected trade with §e" + tradePartner + "§7  ");

        // ── [Add X received pieces] ───────────────────────────────────────────
        if (!incomingPieces.isEmpty()) {
            String badgeId = UUID.randomUUID().toString();
            TradeBadge badge = new TradeBadge(badgeId, new ArrayList<>(incomingPieces.values()), tradePartner);
            storeAddBadge(badge);

            int n = incomingPieces.size();
            MutableComponent addBtn = Component.literal(
                "§a[Add " + n + " received piece" + (n == 1 ? "" : "s") + "]");
            addBtn.withStyle(s -> s
                .withClickEvent(new ClickEvent.RunCommand("/seymour trade add " + badgeId))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(
                    "§7Add §e" + n + " §7piece" + (n == 1 ? "" : "s") + " from §e" + tradePartner + " §7to database"))));
            msg.append(addBtn);
            msg.append(Component.literal(" "));
        }

        // ── [Remove Y traded pieces] ──────────────────────────────────────────
        if (!outgoingUuids.isEmpty()) {
            String badgeId = UUID.randomUUID().toString();
            ScanBadge removeBadge = new ScanBadge(badgeId, new ArrayList<>(outgoingUuids), "trade_remove");
            storeRemoveBadge(removeBadge);
            // Also register in ChestScanner so /seymour undo works as fallback
            SeymourAnalyzerClient.getScanner().registerBadge(removeBadge);

            int n = outgoingUuids.size();
            MutableComponent removeBtn = Component.literal(
                "§c[Remove " + n + " traded piece" + (n == 1 ? "" : "s") + "]");
            removeBtn.withStyle(s -> s
                .withClickEvent(new ClickEvent.RunCommand("/seymour trade remove " + badgeId))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(
                    "§7Remove §e" + n + " §7traded piece" + (n == 1 ? "" : "s") + " from database"))));
            msg.append(removeBtn);
        }
        if (!incomingPieces.isEmpty() || !outgoingUuids.isEmpty()) {
            mc.player.sendSystemMessage(msg);
        }

        // Reset trade state
        tradeActive = false;
        incomingPieces.clear();
        outgoingUuids.clear();
    }

    // ── Badge storage helpers ─────────────────────────────────────────────────
    private void storeAddBadge(TradeBadge badge) {
        addBadgeHistory.addFirst(badge);
        if (addBadgeHistory.size() > MAX_TRADE_BADGES) addBadgeHistory.removeLast();
    }

    private void storeRemoveBadge(ScanBadge badge) {
        removeBadgeHistory.addFirst(badge);
        if (removeBadgeHistory.size() > MAX_TRADE_BADGES) removeBadgeHistory.removeLast();
    }

    public TradeBadge getAddBadge(String badgeId) {
        for (TradeBadge b : addBadgeHistory) {
            if (b.getBadgeId().equals(badgeId)) return b;
        }
        return null;
    }

    public ScanBadge getRemoveBadge(String badgeId) {
        for (ScanBadge b : removeBadgeHistory) {
            if (b.getBadgeId().equals(badgeId)) return b;
        }
        return null;
    }

    public void removeAddBadge(String badgeId) {
        addBadgeHistory.removeIf(b -> b.getBadgeId().equals(badgeId));
    }

    public void removeRemoveBadge(String badgeId) {
        removeBadgeHistory.removeIf(b -> b.getBadgeId().equals(badgeId));
    }

    // ── Inner badge type for "add" operations ─────────────────────────────────
    public static class TradeBadge {
        private final String badgeId;
        private final List<ArmorPiece> pieces;
        private final String tradePartner;

        public TradeBadge(String badgeId, List<ArmorPiece> pieces, String tradePartner) {
            this.badgeId      = badgeId;
            this.pieces       = Collections.unmodifiableList(pieces);
            this.tradePartner = tradePartner;
        }

        public String         getBadgeId()     { return badgeId; }
        public List<ArmorPiece> getPieces()    { return pieces; }
        public String         getTradePartner(){ return tradePartner; }
        public int            size()           { return pieces.size(); }
    }
}

