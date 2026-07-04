package dev.slne.surf.command.sub;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.slne.surf.SurfCanvasGlobalConfiguration;
import io.canvasmc.canvas.command.SubCommand;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public class SurfCanvasReloadCommand implements SubCommand {
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
    public LiteralArgumentBuilder<CommandSourceStack> construct(LiteralArgumentBuilder<CommandSourceStack> base, CommandBuildContext context) {
        return base.executes(ctx -> {
            ctx.getSource().sendSystemMessage(
                Component.literal("Some configuration options cannot be changed at runtime or may work incorrectly after reloading.")
                    .withColor(CommonColors.RED)
            );
            ctx.getSource().sendSystemMessage(
                Component.literal("This command is unsupported. If you encounter issues, please run /stop")
                    .withColor(CommonColors.RED)
            );

            long start = System.nanoTime();
            SurfCanvasGlobalConfiguration.reload();

            ctx.getSource()
                .sendSystemMessage(
                    Component.literal("Reloaded all SurfCanvas solid and patch configurations in " + String.format("%.2f", ((System.nanoTime() - start) / 1e+6)) + "ms")
                        .withColor(CommonColors.GREEN)
                );

            return 1;
        });
    }
}
