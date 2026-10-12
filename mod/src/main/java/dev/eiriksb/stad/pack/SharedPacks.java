package dev.eiriksb.stad.pack;

import dev.eiriksb.stad.Stad;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.FolderRepositorySource;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.level.validation.DirectoryValidator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.AddPackFindersEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Building packs for every world: zips and folders in {@code <game dir>/stadpacks/} (a pack someone shared, or one
 * exported with {@code /stad pack export}) are data packs that are switched on in every world.
 */
public final class SharedPacks {
    public static final String FOLDER = "stadpacks";
    private static final PackSource SOURCE = PackSource.create(
            name -> Component.translatable("pack.nameAndSource", name, Component.literal("Stad packs")), true);

    private SharedPacks() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(SharedPacks::find);
    }

    public static Path folder() {
        return FMLPaths.GAMEDIR.get().resolve(FOLDER);
    }

    private static void find(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) {
            return;
        }
        Path dir = folder();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            Stad.LOGGER.warn("Couldn't make {}: {}", dir, e.toString());
            return;
        }
        event.addRepositorySource(new FolderRepositorySource(dir, PackType.SERVER_DATA, SOURCE, new DirectoryValidator(p -> true)));
    }
}
