package works.nuty.codon.client;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.FriendlyByteBuf;
import works.nuty.codon.adapter.NbtTreeReader;
import works.nuty.codon.adapter.WatchReader;
import works.nuty.codon.core.model.NbtPage;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;
import works.nuty.codon.network.NbtTreeSyncPayload;

import java.util.ArrayList;
import java.util.List;

/** Bootstrap-level NBT path and bounded-page checks; no live server or paused execution is required. */
@SuppressWarnings("UnstableApiUsage")
public final class NbtTreeReaderGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        CompoundTag root = fixture();
        verifiesEveryExposedPathResolvesExactly(root);
        verifiesNestedNavigationAndPinCompatibility(root);
        verifiesPagingAndBounds(root);
        verifiesInvalidAndOversizeInputs(root);
        verifiesPayloadCodec(root);
    }

    private static void verifiesEveryExposedPathResolvesExactly(CompoundTag root) {
        List<NbtPage.Node> all = new ArrayList<>();
        NbtPage first = NbtTreeReader.readPage(root, "", 0);
        NbtPage second = NbtTreeReader.readPage(root, "", NbtPage.PAGE_SIZE);
        require(first.status() == WatchResult.Status.VALUE && second.status() == WatchResult.Status.VALUE, "root pages are readable");
        all.addAll(first.children());
        all.addAll(second.children());
        require(all.size() == first.totalChildren() && first.totalChildren() > NbtPage.PAGE_SIZE, "all root children are exposed over 32-item pages");
        for (NbtPage.Node child : all) {
            if (child.path().isEmpty()) continue; // A deliberately overlong display key is not browseable.
            WatchResult result = WatchReader.readPath(root, child.path(), "fixture");
            require(result.status() == WatchResult.Status.VALUE,
                "generated path resolves its exact child: " + child.name() + " -> " + child.path());
        }
    }

    private static void verifiesNestedNavigationAndPinCompatibility(CompoundTag root) {
        NbtPage.Node nested = child(NbtTreeReader.readPage(root, "", 0), "nested");
        require(nested.expandable() && nested.pinnable(), "compound branch can be navigated and pinned");
        NbtPage page = NbtTreeReader.readPage(root, nested.path(), 0);
        require(page.status() == WatchResult.Status.VALUE && page.totalChildren() == 3, "nested compound page has exact children");
        for (NbtPage.Node node : page.children()) {
            require(WatchReader.readPath(root, node.path(), "fixture").status() == WatchResult.Status.VALUE,
                "nested generated path remains an exact WatchReader target: " + node.path());
            require(new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", node.path()).path().equals(node.path()),
                "legal generated nested path can be pinned as an entity NBT watch");
        }
        NbtPage.Node list = child(NbtTreeReader.readPage(root, "", 0), "list");
        NbtPage listPage = NbtTreeReader.readPage(root, list.path(), 0);
        require(listPage.totalChildren() == 3, "list branch is paged by element");
        NbtPage.Node array = child(NbtTreeReader.readPage(root, "", 0), "numbers");
        require(NbtTreeReader.readPage(root, array.path(), 0).totalChildren() == 3, "numeric arrays are paged by element");
    }

    private static void verifiesPagingAndBounds(CompoundTag root) {
        NbtPage first = NbtTreeReader.readPage(root, "", 0);
        NbtPage second = NbtTreeReader.readPage(root, "", NbtPage.PAGE_SIZE);
        require(first.children().size() == NbtPage.PAGE_SIZE && first.offset() == 0 && first.hasMore(), "first page is exactly 32 entries");
        require(second.offset() == NbtPage.PAGE_SIZE && second.children().size() == first.totalChildren() - NbtPage.PAGE_SIZE && !second.hasMore(),
            "second page has the remaining entries and exact offset");
        NbtPage.Node preview = rootChild(root, "preview");
        require(preview.preview().length() == NbtPage.MAX_PREVIEW_LENGTH && preview.preview().endsWith("…"), "long preview is bounded with an ellipsis");
        NbtPage.Node longName = rootChild(root, "k".repeat(129));
        String exactLongPath = "\"" + "k".repeat(129) + "\"";
        require(longName.name().length() == NbtPage.MAX_NAME_LENGTH && longName.name().endsWith("…")
                && longName.path().equals(exactLongPath) && !longName.pinnable(),
            "overlong display key is bounded while its exact query path remains untruncated and non-pinnable");
        require(WatchReader.readPath(root, exactLongPath, "fixture").status() == WatchResult.Status.VALUE,
            "the original long key remains queryable by its exact untruncated path");
        NbtPage.Node pathTooLong = rootChild(root, "p".repeat(511));
        require(pathTooLong.path().isEmpty() && !pathTooLong.pinnable(), "paths beyond the 512-character wire bound are inaccessible");
    }

    private static void verifiesInvalidAndOversizeInputs(CompoundTag root) {
        require(NbtTreeReader.readPage(root, "[", 0).status() == WatchResult.Status.INVALID_PATH, "syntax errors are invalid paths");
        require(NbtTreeReader.readPage(root, "\"list\"[]", 0).status() == WatchResult.Status.INVALID_PATH,
            "paths resolving multiple list values are rejected");
        require(NbtTreeReader.readPage(root, "\"missing\"", 0).status() == WatchResult.Status.VALUE_MISSING, "missing branch is explicit");
        require(NbtTreeReader.readPage(root, "", -1).status() == WatchResult.Status.INVALID_PATH, "negative offset is invalid");
        CompoundTag oversized = new CompoundTag();
        oversized.putString("payload", "x".repeat(1_100_000));
        require(NbtTreeReader.readPage(oversized, "", 0).status() == WatchResult.Status.TOO_LARGE, "one-megabyte root guard applies before expansion");
    }

    private static void verifiesPayloadCodec(CompoundTag root) {
        NbtPage page = NbtTreeReader.readPage(root, "", 0);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            NbtTreeSyncPayload payload = new NbtTreeSyncPayload(17, 23, page);
            NbtTreeSyncPayload.CODEC.encode(buffer, payload);
            require(NbtTreeSyncPayload.CODEC.decode(buffer).equals(payload) && buffer.readableBytes() == 0, "NBT page payload round trips exactly");
        } finally {
            buffer.release();
        }
        FriendlyByteBuf malformed = new FriendlyByteBuf(Unpooled.buffer());
        try {
            malformed.writeVarLong(1);
            malformed.writeVarLong(2);
            malformed.writeEnum(WatchResult.Status.VALUE);
            malformed.writeVarInt(0);
            malformed.writeVarInt(0);
            malformed.writeVarInt(NbtPage.PAGE_SIZE + 1);
            boolean rejected = false;
            try { NbtTreeSyncPayload.CODEC.decode(malformed); }
            catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected, "payload rejects an over-page child count before node allocation");
        } finally {
            malformed.release();
        }
    }

    private static NbtPage.Node child(NbtPage page, String name) {
        return page.children().stream().filter(node -> node.name().equals(name)
            || (name.length() > NbtPage.MAX_NAME_LENGTH && node.name().equals(name.substring(0, NbtPage.MAX_NAME_LENGTH - 1) + "…"))).findFirst().orElseThrow();
    }

    private static NbtPage.Node rootChild(CompoundTag root, String name) {
        NbtPage first = NbtTreeReader.readPage(root, "", 0);
        for (int offset = 0; offset < first.totalChildren(); offset += NbtPage.PAGE_SIZE) {
            try { return child(NbtTreeReader.readPage(root, "", offset), name); }
            catch (java.util.NoSuchElementException ignored) { }
        }
        throw new java.util.NoSuchElementException(name);
    }

    private static CompoundTag fixture() {
        CompoundTag root = new CompoundTag();
        root.putString("plain", "plain");
        root.putString("dot.key", "dot");
        root.putString("space key", "space");
        root.putString("quote\"key", "quote");
        root.putString("slash\\key", "slash");
        root.putString("preview", "x".repeat(200));
        root.putString("k".repeat(129), "long-name");
        root.putString("p".repeat(511), "long-path");
        CompoundTag nested = new CompoundTag();
        nested.putString("dot.key", "nested-dot");
        nested.putString("space key", "nested-space");
        nested.putString("quote\"key", "nested-quote");
        root.put("nested", nested);
        ListTag list = new ListTag();
        list.add(StringTag.valueOf("first"));
        list.add(StringTag.valueOf("second"));
        list.add(StringTag.valueOf("third"));
        root.put("list", list);
        root.put("numbers", new IntArrayTag(new int[] { 4, 5, 6 }));
        for (int i = 0; i < 36; i++) root.putInt("page%02d".formatted(i), i);
        return root;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
