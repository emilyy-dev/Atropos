package dev.laarryy.Icicle.listeners;

import dev.laarryy.Icicle.Icicle;
import discord4j.core.event.domain.guild.GuildCreateEvent;
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.Member;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class GuildJoinListener {
    private final Logger logger = LogManager.getLogger(this);

    @EventListener
    public Mono<Void> on(GuildCreateEvent event) {

        logger.info("Joining Guild: " + event.getGuild().getName());

        Guild guild = event.getGuild();

        Icicle.addServerToDatabase(guild);

        Flux<Member> memberFlux = event.getGuild().getMembers();

        memberFlux
                .map(member -> Icicle.addUserToDatabase(member, guild))
                .doOnError(logger::error)
                .subscribe();

        return Mono.empty();
    }
}