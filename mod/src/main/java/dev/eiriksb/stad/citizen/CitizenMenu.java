package dev.eiriksb.stad.citizen;

import com.mojang.datafixers.util.Pair;
import dev.eiriksb.stad.StadRegistries;
import dev.eiriksb.stad.colony.OrderKind;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.ItemStack;

/**
 * A citizen's gear, for the town's officers (sneak and right-click them, or Gear on their screen), any job: the armour
 * they wear, what they hold in each hand, their own things and what they're carrying for work, next to the player's
 * own inventory. Their own things are what they've been given: they eat that food first, wear that armour, and their
 * job uses those tools and supplies before the town's stores, so a young town without workplaces or a warehouse can
 * be kitted out by hand. What they carry for work goes to the stores; a tool that isn't for their job does too.
 */
public class CitizenMenu extends AbstractContainerMenu {
    /** the gear slots, in the menu's order: armour top to bottom, then the hands */
    public static final EquipmentSlot[] GEAR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
    private static final ResourceLocation[] EMPTY = {InventoryMenu.EMPTY_ARMOR_SLOT_HELMET, InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE,
            InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS, InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS, null, InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD};
    /** where things are laid out (see the screen) */
    public static final int WIDTH = 176, HEIGHT = 232, ARMOUR_X = 8, TOP = 18, HANDS_X = 80, OWN_X = 116, POCKETS_Y = 102, PLAYER_Y = 150;
    private static final int OWN_START = GEAR.length, POCKETS_START = OWN_START + CitizenEntity.BELONGINGS,
            PLAYER_START = POCKETS_START + CitizenEntity.POCKETS, END = PLAYER_START + 36;

    /** the citizen's entity id (for the screen to draw them) */
    public final int citizenId;
    private final CitizenEntity citizen;

    /** The client's side: the slots are filled in from the server. */
    public CitizenMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        this(id, inv, buf.readVarInt(), new SimpleContainer(GEAR.length), new SimpleContainer(CitizenEntity.BELONGINGS),
                new SimpleContainer(CitizenEntity.POCKETS), null);
    }

    public CitizenMenu(int id, Inventory inv, CitizenEntity c) {
        this(id, inv, c.getId(), new Gear(c), c.belongings(), c.inventory(), c);
    }

    private CitizenMenu(int id, Inventory inv, int citizenId, Container gear, Container own, Container pockets, CitizenEntity citizen) {
        super(StadRegistries.CITIZEN_MENU.get(), id);
        this.citizenId = citizenId;
        this.citizen = citizen;
        for (int i = 0; i < GEAR.length; i++) {
            int n = i;
            boolean armour = GEAR[i].isArmor();
            int x = armour ? ARMOUR_X : HANDS_X, y = armour ? TOP + i * 18 : TOP + (i - 2) * 18;
            addSlot(new Slot(gear, i, x, y) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return !armour || fits(stack, GEAR[n]);
                }

                @Override
                public int getMaxStackSize() {
                    return armour ? 1 : super.getMaxStackSize();
                }

                @Override
                public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
                    return EMPTY[n] == null ? null : Pair.of(InventoryMenu.BLOCK_ATLAS, EMPTY[n]);
                }
            });
        }
        for (int i = 0; i < CitizenEntity.BELONGINGS; i++) {
            addSlot(new Slot(own, i, OWN_X + (i % 3) * 18, TOP + (i / 3) * 18));
        }
        for (int i = 0; i < CitizenEntity.POCKETS; i++) {
            addSlot(new Slot(pockets, i, 8 + (i % 9) * 18, POCKETS_Y + (i / 9) * 18));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inv, 9 + row * 9 + col, 8 + col * 18, PLAYER_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inv, col, 8 + col * 18, PLAYER_Y + 58));
        }
    }

    /** Whether it's worn in that slot (armour, a carved pumpkin or a head for the head...). */
    static boolean fits(ItemStack stack, EquipmentSlot slot) {
        Equipable e = Equipable.get(stack);
        return e != null && e.getEquipmentSlot() == slot;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem(), was = stack.copy();
        if (index < PLAYER_START) {
            if (!moveItemStackTo(stack, PLAYER_START, END, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            boolean moved = false;
            for (int i = 0; i < 4 && !moved; i++) { // armour onto them, if a slot for it is free
                if (fits(stack, GEAR[i]) && !slots.get(i).hasItem()) {
                    moved = moveItemStackTo(stack, i, i + 1, false);
                }
            }
            if (!moved && !moveItemStackTo(stack, OWN_START, POCKETS_START, false)) { // given to them: their own things
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return was;
    }

    @Override
    public boolean stillValid(Player player) {
        return citizen == null || citizen.isAlive() && player.distanceTo(citizen) <= CitizenScreens.REACH && player instanceof ServerPlayer sp && allowed(sp, citizen);
    }

    /** Who may see to a citizen's gear: their town's officers (as for giving them a job). */
    public static boolean allowed(ServerPlayer player, CitizenEntity c) {
        return c.colony().map(t -> t.rank(player.getUUID()).atLeast(OrderKind.ASSIGN_JOB.minRank)).orElse(false);
    }

    /** Opens it, or says why not. Server thread. */
    public static void open(ServerPlayer player, CitizenEntity c) {
        if (!allowed(player, c)) {
            player.displayClientMessage(Component.literal("Only their town's officers can see to " + c.getName().getString() + "'s gear.")
                    .withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new CitizenMenu(id, inv, c), c.getName()), buf -> buf.writeVarInt(c.getId()));
    }

    /** The citizen's armour and hands, as slots. */
    private record Gear(CitizenEntity c) implements Container {
        @Override
        public int getContainerSize() {
            return GEAR.length;
        }

        @Override
        public boolean isEmpty() {
            for (EquipmentSlot s : GEAR) {
                if (!c.getItemBySlot(s).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getItem(int i) {
            return c.getItemBySlot(GEAR[i]);
        }

        @Override
        public ItemStack removeItem(int i, int n) {
            ItemStack st = getItem(i);
            if (st.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack out = st.split(n);
            c.setItemSlot(GEAR[i], st.isEmpty() ? ItemStack.EMPTY : st);
            return out;
        }

        @Override
        public ItemStack removeItemNoUpdate(int i) {
            ItemStack st = getItem(i);
            c.setItemSlot(GEAR[i], ItemStack.EMPTY);
            return st;
        }

        @Override
        public void setItem(int i, ItemStack stack) {
            c.setItemSlot(GEAR[i], stack);
        }

        @Override
        public void setChanged() {}

        @Override
        public boolean stillValid(Player player) {
            return c.isAlive();
        }

        @Override
        public void clearContent() {
            for (EquipmentSlot s : GEAR) {
                c.setItemSlot(s, ItemStack.EMPTY);
            }
        }
    }
}
