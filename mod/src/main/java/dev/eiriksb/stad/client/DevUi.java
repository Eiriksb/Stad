package dev.eiriksb.stad.client;

import dev.eiriksb.stad.Stad;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Runs dev scripts from {@link dev.eiriksb.stad.net.DevUiPayload} against the open screen, one step a tick:
 * {@code click x y [button]}, {@code drag x1 y1 x2 y2 [button]}, {@code scroll x y amount}, {@code key name},
 * {@code type text}, {@code wait ticks}, {@code shot name} (saved in screenshots/ once the frame is drawn).
 * Coordinates are the screen's (GUI-scaled). In the world: {@code hold item_id} (creative), {@code look yaw pitch},
 * {@code use}, {@code attack}, {@code drop}, {@code press letter} (whatever key binding is on that key).
 */
@EventBusSubscriber(modid = Stad.MOD_ID, value = Dist.CLIENT)
public final class DevUi {
    private static final Deque<String> steps = new ArrayDeque<>();
    private static int waiting;
    private static String shot;

    private DevUi() {}

    static void run(String script) {
        for (String s : script.split(";")) {
            if (!s.isBlank()) {
                steps.add(s.strip());
            }
        }
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        if (waiting > 0) {
            waiting--;
            return;
        }
        while (!steps.isEmpty() && shot == null && waiting == 0) {
            step(steps.poll());
        }
    }

    @SubscribeEvent
    static void rendered(ScreenEvent.Render.Post event) {
        grab(event.getScreen().getClass().getSimpleName() + " at " + event.getScreen().width + "x" + event.getScreen().height);
    }

    /** In the world, with no screen open. */
    @SubscribeEvent
    static void renderedHud(net.neoforged.neoforge.client.event.RenderGuiEvent.Post event) {
        if (Minecraft.getInstance().screen == null) {
            grab("the world");
        }
    }

    private static void grab(String what) {
        if (shot != null) {
            Minecraft mc = Minecraft.getInstance();
            String name = shot;
            shot = null;
            Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), msg -> Stad.LOGGER.info("dev ui: {}", msg.getString()));
            Stad.LOGGER.info("dev ui: shot {} of {}", name, what);
        }
    }

    private static void step(String s) {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        String[] a = s.split("\\s+");
        try {
            switch (a[0]) {
                case "wait" -> waiting = Integer.parseInt(a[1]);
                case "shot" -> shot = a.length > 1 ? a[1] : "dev";
                case "click" -> {
                    if (screen != null) {
                        double x = Double.parseDouble(a[1]), y = Double.parseDouble(a[2]);
                        int b = a.length > 3 ? Integer.parseInt(a[3]) : 0;
                        screen.mouseClicked(x, y, b);
                        screen.mouseReleased(x, y, b);
                    }
                }
                case "drag" -> {
                    if (screen != null) {
                        double x1 = Double.parseDouble(a[1]), y1 = Double.parseDouble(a[2]), x2 = Double.parseDouble(a[3]), y2 = Double.parseDouble(a[4]);
                        int b = a.length > 5 ? Integer.parseInt(a[5]) : 0;
                        screen.mouseClicked(x1, y1, b);
                        int n = 10;
                        for (int i = 1; i <= n; i++) {
                            double px = x1 + (x2 - x1) * (i - 1) / n, py = y1 + (y2 - y1) * (i - 1) / n;
                            double nx = x1 + (x2 - x1) * i / n, ny = y1 + (y2 - y1) * i / n;
                            screen.mouseDragged(nx, ny, b, nx - px, ny - py);
                        }
                        screen.mouseReleased(x2, y2, b);
                    }
                }
                case "scroll" -> {
                    if (screen != null) {
                        screen.mouseScrolled(Double.parseDouble(a[1]), Double.parseDouble(a[2]), 0, Double.parseDouble(a[3]));
                    }
                }
                case "key" -> {
                    if (screen != null) {
                        int code = switch (a[1].toUpperCase()) {
                            case "ESC", "ESCAPE" -> GLFW.GLFW_KEY_ESCAPE;
                            case "ENTER" -> GLFW.GLFW_KEY_ENTER;
                            case "PGUP" -> GLFW.GLFW_KEY_PAGE_UP;
                            case "PGDN" -> GLFW.GLFW_KEY_PAGE_DOWN;
                            default -> a[1].toUpperCase().charAt(0);
                        };
                        screen.keyPressed(code, 0, 0);
                        screen.keyReleased(code, 0, 0);
                    }
                }
                case "type" -> {
                    if (screen != null) {
                        for (char c : s.substring(5).toCharArray()) {
                            screen.charTyped(c, 0);
                        }
                    }
                }
                case "hold" -> {
                    var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(a[1]));
                    int slot = mc.player.getInventory().selected;
                    net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item);
                    mc.player.getInventory().setItem(slot, stack);
                    mc.gameMode.handleCreativeModeItemAdd(stack, 36 + slot);
                }
                case "look" -> {
                    mc.player.setYRot(Float.parseFloat(a[1]));
                    mc.player.setXRot(Float.parseFloat(a[2]));
                }
                case "use" -> net.minecraft.client.KeyMapping.click(mc.options.keyUse.getKey());
                case "attack" -> net.minecraft.client.KeyMapping.click(mc.options.keyAttack.getKey());
                case "drop" -> net.minecraft.client.KeyMapping.click(mc.options.keyDrop.getKey());
                case "press" -> net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(a[1].toUpperCase().charAt(0)));
                default -> Stad.LOGGER.warn("dev ui: what's {}?", s);
            }
        } catch (RuntimeException e) {
            Stad.LOGGER.warn("dev ui: {} failed", s, e);
        }
    }
}
