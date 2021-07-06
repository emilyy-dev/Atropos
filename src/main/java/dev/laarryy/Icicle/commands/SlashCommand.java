package dev.laarryy.Icicle.commands;

import discord4j.core.event.domain.interaction.SlashCommandEvent;
import discord4j.discordjson.json.ApplicationCommandRequest;
import reactor.core.publisher.Mono;

public interface SlashCommand {

    Mono<Void> execute(SlashCommandEvent event);

    ApplicationCommandRequest getRequest();

}
