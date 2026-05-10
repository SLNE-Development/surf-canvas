package dev.slne.surf.command.sub;

import com.mojang.brigadier.builder.*;
import dev.slne.surf.*;
import io.canvasmc.canvas.command.*;
import net.minecraft.commands.*;
import net.minecraft.network.chat.*;
import net.minecraft.util.*;
import org.jspecify.annotations.*;

@NullMarked
public class SurfCanvasReloadCommand implements Command {
    @Override
    public String getName() {
        return "reload";
    }

    @Override
    public @Nullable String getDescription() {
        return "Reloads SurfCanvas configuration";
    }

    @Override
    public boolean isAllowedSelfCommand() {
        return false;
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> construct(LiteralArgumentBuilder<CommandSourceStack> base) {
        return base.executes(context -> {
            context.getSource().sendSystemMessage(
                Component.literal("Some configuration options cannot be changed at runtime or may work incorrectly after reloading.")
                    .withColor(CommonColors.RED)
            );
            context.getSource().sendSystemMessage(
                Component.literal("This command is unsupported. If you encounter issues, please run /stop")
                    .withColor(CommonColors.RED)
            );

            long start = System.nanoTime();
            SurfCanvasGlobalConfiguration.reload();

            context.getSource()
                .sendSystemMessage(
                    Component.literal("Reloaded all SurfCanvas solid and patch configurations in " + String.format("%.2f", ((System.nanoTime() - start) / 1e+6)) + "ms")
                        .withColor(CommonColors.GREEN)
                );

            return 1;
        });
    }
}
