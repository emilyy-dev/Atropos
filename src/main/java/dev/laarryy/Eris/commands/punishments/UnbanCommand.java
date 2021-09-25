package dev.laarryy.Eris.commands.punishments;

import dev.laarryy.Eris.commands.Command;
import dev.laarryy.Eris.utils.PermissionChecker;
import dev.laarryy.Eris.utils.SlashCommandChecks;
import discord4j.core.event.domain.interaction.SlashCommandEvent;
import discord4j.discordjson.json.ApplicationCommandOptionData;
import discord4j.discordjson.json.ApplicationCommandRequest;
import discord4j.rest.util.ApplicationCommandOptionType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class UnbanCommand implements Command {
    private final Logger logger = LogManager.getLogger(this);
    private final PermissionChecker permissionChecker = new PermissionChecker();
    private final ManualPunishmentEnder manualPunishmentEnder = new ManualPunishmentEnder();


    private final ApplicationCommandRequest request = ApplicationCommandRequest.builder()
            .name("unban")
            .description("Unban a user.")
            .addOption(ApplicationCommandOptionData.builder()
                    .name("id")
                    .description("ID of one user to unban OR space-delineated list of multiple users to unban.")
                    .type(ApplicationCommandOptionType.STRING.getValue())
                    .required(true)
                    .build())
            .addOption(ApplicationCommandOptionData.builder()
                    .name("reason")
                    .description("Why?")
                    .type(ApplicationCommandOptionType.STRING.getValue())
                    .required(false)
                    .build())
            .defaultPermission(true)
            .build();

    public ApplicationCommandRequest getRequest() {
        return this.request;
    }

    public Mono<Void> execute(SlashCommandEvent event) {
        if (!SlashCommandChecks.slashCommandChecks(event, request.name())) {
            return Mono.empty();
        }

        Mono.just(event)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(manualPunishmentEnder::endPunishment);
        return Mono.empty();
    }
}
