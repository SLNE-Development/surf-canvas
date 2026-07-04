package dev.slne.surf.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.slne.surf.command.sub.SurfCanvasReloadCommand;
import io.canvasmc.canvas.command.SubCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NullMarked;

import java.util.LinkedList;
import java.util.List;

import static net.minecraft.commands.Commands.literal;

@NullMarked
public class SurfCanvasRootCommandTree {

    private static final TextColor HEADER = TextColor.color(47, 140, 94);
    private static final TextColor PRIMARY = TextColor.color(32, 143, 89);
    private static final TextColor SECONDARY = TextColor.color(20, 123, 116);
    private static final TextColor INFORMATION = TextColor.color(79, 143, 47);
    private static final TextColor LIST = TextColor.color(21, 96, 68);
    private static final TextColor ACCENT = TextColor.color(47, 140, 94);
    private static final TextColor MUTED = TextColor.color(80, 120, 100);

    public static final SurfCanvasRootCommandTree INSTANCE;

    static {
        INSTANCE = new SurfCanvasRootCommandTree();
        INSTANCE.register(SurfCanvasReloadCommand.class);
    }

    private final List<SubCommand> subCommands = new LinkedList<>();

    private Component buildDetailComponent(SubCommand subCommand) {
        String name = subCommand.getName();
        String description = subCommand.getDescription();
        boolean selfCmd = subCommand.isAllowedSelfCommand();

        TextComponent.Builder builder = Component.text()
            .append(Component.text("----", SECONDARY))
            .append(Component.text("/surfcanvas " + name, HEADER).decorate(TextDecoration.BOLD))
            .append(Component.text("----", SECONDARY))
            .appendNewline()
            .appendNewline();

        builder.append(Component.text("  Description  ", MUTED).decorate(TextDecoration.BOLD))
            .append(Component.text(description != null ? description : "No description provided.", ACCENT))
            .appendNewline();

        builder.append(Component.text("  Permission   ", MUTED).decorate(TextDecoration.BOLD))
            .append(Component.text("surfcanvas.command." + name, ACCENT))
            .appendNewline();

        builder.append(Component.text("  Standalone   ", MUTED).decorate(TextDecoration.BOLD))
            .append(selfCmd
                ? Component.text("Yes ", TextColor.color(100, 220, 140)).append(Component.text("(/" + name + ", /surfcanvas:" + name + ")", INFORMATION))
                : Component.text("No", TextColor.color(220, 100, 100)))
            .appendNewline()
            .appendNewline();

        builder.append(Component.text("-----------------------", SECONDARY));

        return builder.build();
    }

    public void build(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        LiteralArgumentBuilder<CommandSourceStack> root = literal("surfcanvas")
            .requires(source -> source.getSender().isOp() || source.getSender().hasPermission("surfcanvas.command"));

        for (SubCommand subCommand : subCommands) {
            String name = subCommand.getName();

            root.then(subCommand.construct(literal(name)
                .requires(source -> source.getSender().isOp() || source.getSender().hasPermission("surfcanvas.command." + name)), context));

            if (subCommand.isAllowedSelfCommand()) {
                dispatcher.register(subCommand.construct(literal(name)
                    .requires(source -> source.getSender().isOp() || source.getSender().hasPermission("surfcanvas.command." + name)), context));

                dispatcher.register(subCommand.construct(literal("surfcanvas:" + name)
                    .requires(source -> source.getSender().isOp() || source.getSender().hasPermission("surfcanvas.command." + name)), context));
            }
        }

        root.then(literal("help")
            .requires(source -> source.getSender().isOp() || source.getSender().hasPermission("surfcanvas.command.help"))
            .executes(ctx -> {
                CommandSender bukkitSender = ctx.getSource().getBukkitSender();

                TextComponent.Builder builder = Component.text()
                    .append(Component.text("----", SECONDARY))
                    .append(Component.text("SurfCanvas Commands", HEADER).decorate(TextDecoration.BOLD))
                    .append(Component.text("----", SECONDARY))
                    .appendNewline();

                for (SubCommand subCommand : subCommands) {
                    String name = subCommand.getName();
                    if (!bukkitSender.hasPermission("surfcanvas.command." + name)) {
                        continue;
                    }

                    Component hoverText = Component.text()
                        .append(Component.text("Click to view further details", INFORMATION))
                        .build();

                    Component detailComponent = buildDetailComponent(subCommand);

                    Component entry = Component.text()
                        .append(Component.text("- ").color(LIST))
                        .append(Component.text("/").color(SECONDARY))
                        .append(Component.text(name, PRIMARY)
                            .decorate(TextDecoration.UNDERLINED)
                            .hoverEvent(HoverEvent.showText(hoverText))
                            .clickEvent(ClickEvent.callback((audience) -> audience.sendMessage(detailComponent))))
                        .appendNewline()
                        .build();

                    builder.append(entry);
                }

                builder.append(Component.text("-----------------------", SECONDARY));

                bukkitSender.sendMessage(builder.build());
                return 1;
            }));
        dispatcher.register(root);
    }

    public void register(Class<? extends SubCommand> command) {
        try {
            if (command.getDeclaredConstructor().getParameterCount() != 0) {
                throw new IllegalArgumentException("Command must have no-arg constructor");
            }
            this.subCommands.add(
                command.getDeclaredConstructor().newInstance()
            );
        } catch (InstantiationException | IllegalAccessException | java.lang.reflect.InvocationTargetException |
                 NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }
}
