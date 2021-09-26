package dev.laarryy.Eris.commands.punishments;

import dev.laarryy.Eris.listeners.logging.LoggingListener;
import dev.laarryy.Eris.managers.LoggingListenerManager;
import dev.laarryy.Eris.models.guilds.DiscordServer;
import dev.laarryy.Eris.models.guilds.DiscordServerProperties;
import dev.laarryy.Eris.models.users.DiscordUser;
import dev.laarryy.Eris.models.users.Punishment;
import dev.laarryy.Eris.storage.DatabaseLoader;
import dev.laarryy.Eris.utils.AuditLogger;
import dev.laarryy.Eris.utils.DurationParser;
import dev.laarryy.Eris.utils.Notifier;
import dev.laarryy.Eris.utils.PermissionChecker;
import discord4j.common.util.Snowflake;
import discord4j.core.event.domain.interaction.SlashCommandEvent;
import discord4j.core.object.PermissionOverwrite;
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.Role;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.TextChannel;
import discord4j.core.object.entity.channel.VoiceChannel;
import discord4j.core.spec.BanQuerySpec;
import discord4j.core.spec.RoleCreateSpec;
import discord4j.core.spec.TextChannelEditSpec;
import discord4j.core.spec.VoiceChannelEditSpec;
import discord4j.discordjson.json.ApplicationCommandRequest;
import discord4j.discordjson.json.RoleData;
import discord4j.rest.util.OrderUtil;
import discord4j.rest.util.PermissionSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public class PunishmentManager {
    private final Logger logger = LogManager.getLogger(this);
    PermissionChecker permissionChecker = new PermissionChecker();
    LoggingListener loggingListener = LoggingListenerManager.getManager().getLoggingListener();

    public Mono<Void> doPunishment(ApplicationCommandRequest request, SlashCommandEvent event) {

        // Make sure this is done in a guild or else stop right here.

        if (event.getInteraction().getGuild().block() == null || event.getInteraction().getMember().isEmpty()) {
            event.reply("This must be done in a guild.").withEphemeral(true).subscribe();
            return Mono.empty();
        }

        // Gather some necessary information for the rest of this

        Guild guild = event.getInteraction().getGuild().block();

        Member member = event.getInteraction().getMember().get();
        User user = event.getInteraction().getUser();
        Long userIdSnowflake = member.getId().asLong();

        DatabaseLoader.openConnectionIfClosed();

        // Make sure user has permission to do this, or stop here - PermissionId 69 is the wildcard/everything permission.

        if (!permissionChecker.checkPermission(guild, user, request.name())) {
            Notifier.notifyCommandUserOfError(event, "noPermission");
            return Mono.empty();
        }

        Long guildIdSnowflake = guild.getId().asLong();
        DiscordServer discordServer = DiscordServer.findFirst("server_id = ?", guildIdSnowflake);
        int serverId;

        if (discordServer != null) {
            serverId = discordServer.getServerId();
        } else {
            Notifier.notifyCommandUserOfError(event, "nullServer");
            return Mono.empty();
        }

        // Handle forceban via API before dealing with literally every other punishment (that can actually provide a user)

        if (event.getOption("id").isPresent() && event.getOption("id").get().getValue().isPresent()) {
            String idInput = event.getOption("id").get().getValue().get().asString();
            Flux.fromArray(idInput.split(" "))
                    .map(Long::valueOf)
                    .onErrorReturn(NumberFormatException.class, 0L)
                    .filter(aLong -> aLong != 0)
                    .filter(aLong -> apiBanId(guild, aLong))
                    .doFirst(() -> event.acknowledge().block())
                    .doOnComplete(() -> {
                        Notifier.notifyPunisherForcebanComplete(event);
                        AuditLogger.addCommandToDB(event, true);
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(aLong -> {

                        // Ensure nobody is trying to forceban their boss or a bot
                        try {
                            guild.getMemberById(Snowflake.of(aLong));
                            if (guild.getMemberById(Snowflake.of(aLong)).block() != null) {
                                if (!checkIfPunisherHasHighestRole(event.getInteraction().getMember().get(), guild.getMemberById(Snowflake.of(aLong)).block(), guild, event)) {
                                    return;
                                }
                                if (guild.getMemberById(Snowflake.of(aLong)).block().isBot()) {
                                    return;
                                }
                            }
                        } catch (Exception ignored) {}

                        DatabaseLoader.openConnectionIfClosed();
                        DiscordUser punished = DiscordUser.findOrCreateIt("user_id_snowflake", aLong);
                        DiscordUser punisher = DiscordUser.findFirst("user_id_snowflake = ?", userIdSnowflake);
                        Punishment punishment = createDatabasePunishmentRecord(punisher, punished, serverId, request.name());
                        punishment.save();
                        punishment.refresh();
                        punishment.setEnded(true);
                        punishment.save();
                    });
            return Mono.empty();
        }

        // Ensure bot is not a target

        if (event.getOption("user").isPresent() && event.getOption("user").get().getValue().isPresent()) {
            if (event.getOption("user").get().getValue().get().asUser().block().isBot()) {
                Notifier.notifyCommandUserOfError(event, "cannotTargetBots");
                AuditLogger.addCommandToDB(event, false);
                return Mono.empty();
            }
        }
        // All other punishments have Users, so if we're missing one here it's a problem, we need to stop.

        if (event.getOption("user").isEmpty()) {
            Notifier.notifyCommandUserOfError(event, "noUser");
            AuditLogger.addCommandToDB(event, false);
            return Mono.empty();
        }
        User punishedUser = event.getOption("user").get().getValue().get().asUser().block();

        // Make sure bot has the ability to punish the user


        // Make sure the punisher is higher up the food chain than the person they're trying to punish in the guild they're both in.

        if (!checkIfPunisherHasHighestRole(member, punishedUser.asMember(guild.getId()).block(), guild, event)) {
            return Mono.empty();
        }
        // Get the DB objects for both the punishing user and the punished.

        DiscordUser punisher = DiscordUser.findFirst("user_id_snowflake = ?", userIdSnowflake);
        DiscordUser punished = DiscordUser.findFirst("user_id_snowflake = ?", punishedUser.getId().asLong());

        if (Punishment.findFirst("user_id_punished = ? and server_id = ? and punishment_type = ? and end_date_passed = ?",
                punished.getUserId(),
                serverId,
                request.name(),
                false) != null) {
            Notifier.notifyCommandUserOfError(event, "alreadyApplied");
            AuditLogger.addCommandToDB(event, false);
            return Mono.empty();
        }


        Punishment punishment = createDatabasePunishmentRecord(punisher, punished, serverId, request.name());
        punishment.save();
        punishment.refresh();

        String punishmentReason;
        if (event.getOption("reason").isPresent()) {
            String punishmentMessage = event.getOption("reason").get().getValue().get().asString();
            punishment.refresh();
            punishment.setPunishmentMessage(punishmentMessage);
            punishment.save();
            punishmentReason = punishmentMessage;
        } else {
            punishmentReason = "No reason provided.";
            punishment.refresh();
            punishment.setPunishmentMessage(punishmentReason);
            punishment.save();
        }

        // Check if there's a duration on this punishment, and if so save it to database

        if (event.getOption("duration").isPresent() && event.getOption("duration").get().getValue().isPresent()) {
            try {
                Duration punishmentDuration = DurationParser.parseDuration(event.getOption("duration").get().getValue().get().asString());
                Instant punishmentEndDate = Instant.now().plus(punishmentDuration);
                punishment.setEndDate(punishmentEndDate.toEpochMilli());
                punishment.save();
            } catch (Exception exception) {
                Notifier.notifyCommandUserOfError(event, "invalidDuration");
                AuditLogger.addCommandToDB(event, false);
                punishment.delete();
                return Mono.empty();
            }
        } else {
            punishment.setEnded(true);
            punishment.save();
        }

        // Find out how many days worth of messages to delete if this is a member ban

        int messageDeleteDays;
        if (event.getOption("days").isPresent() && event.getOption("days").get().getValue().isPresent()) {
            long preliminaryResult = event.getOption("days").get().getValue().get().asLong();
            if (preliminaryResult > 7 || preliminaryResult < 0) {
                messageDeleteDays = 0;
            } else messageDeleteDays = (int) preliminaryResult;
        } else messageDeleteDays = 0;

        if (event.getCommandName().equals("case")) {
            punishment.setDMed(false);
            punishment.save();
            punishment.refresh();
        }

        // DMing the punished user, notifying the punishing user that it's worked out

        if ((event.getOption("dm").isPresent() && event.getOption("dm").get().getValue().get().asBoolean())
                || (event.getOption("dm").isEmpty())) {
            punishment.setDMed(true);
            punishment.save();
            punishment.refresh();

            Notifier.notifyPunished(event, punishment, punishmentReason);
        }

        // Actually do the punishment, discord-side. Nothing to do for warnings or cases.

        DatabaseLoader.openConnectionIfClosed();
        switch (punishment.getPunishmentType()) {
            case "mute" -> discordMuteUser(guild, punished.getUserIdSnowflake(), DiscordServerProperties.findFirst("server_id = ?", discordServer.getServerId()));
            case "ban" -> discordBanUser(guild, punished.getUserIdSnowflake(), messageDeleteDays, punishmentReason);
            case "kick" -> discordKickUser(guild, punished.getUserIdSnowflake(), punishmentReason);
        }
        loggingListener.onPunishment(event, punishment);
        Notifier.notifyPunisher(event, punishment, punishmentReason);
        AuditLogger.addCommandToDB(event, true);

        return Mono.empty();
    }

    private Punishment createDatabasePunishmentRecord(DiscordUser punisher, DiscordUser punished, int serverId, String punishmentType) {
        return Punishment.createIt(
                "user_id_punished", punished.getUserId(),
                "user_id_punisher", punisher.getUserId(),
                "server_id", serverId,
                "punishment_type", punishmentType,
                "punishment_date", Instant.now().toEpochMilli()
        );
    }

    private void discordBanUser(Guild guild, Long userIdSnowflake, int messageDeleteDays, String punishmentReason) {
        guild.ban(Snowflake.of(userIdSnowflake), BanQuerySpec.builder()
                        .deleteMessageDays(messageDeleteDays)
                        .reason(punishmentReason)
                        .build())
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    private void discordKickUser(Guild guild, Long userIdSnowflake, String punishmentReason) {
        guild.kick(Snowflake.of(userIdSnowflake), punishmentReason).subscribe();
    }

    private boolean apiBanId(Guild guild, Long id) {
        try {
            guild.ban(Snowflake.of(id), BanQuerySpec.builder()
                    .reason("Mass API banned by staff.")
                    .deleteMessageDays(0)
                    .build()).block();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void discordMuteUser(Guild guild, Long userIdSnowflake, DiscordServerProperties discordServerProperties) {
        DatabaseLoader.openConnectionIfClosed();
        if (discordServerProperties.getMutedRoleSnowflake() == null || discordServerProperties.getMutedRoleSnowflake() == 0) {
            Role mutedRole = guild.createRole(RoleCreateSpec.builder()
                    .name("Muted")
                    .permissions(PermissionSet.none())
                    .reason("In order to mute users, a muted role must first be created.")
                    .mentionable(false)
                    .hoist(false)
                    .build()).block();

            List<Role> selfRoleList = guild.getSelfMember().block().getRoles().collectList().block();
            Role highestSelfRole = selfRoleList.get(selfRoleList.size() - 1);

            Flux<RoleData> roleDataFlux = Flux.fromIterable(guild.getRoles().map(Role::getData).collectList().block());

            Long roleCount = OrderUtil.orderRoles(roleDataFlux).takeWhile(role -> !role.equals(highestSelfRole)).count().block();
            int roleInt = roleCount != null ? roleCount.intValue() - 1 : 1;

            if (mutedRole != null) {
                mutedRole.changePosition(roleInt).subscribe();
                discordServerProperties.setMutedRoleSnowflake(mutedRole.getId().asLong());
                discordServerProperties.save();
            }
            discordServerProperties.save();
            discordServerProperties.refresh();
            Role mutedRole1 = guild.getRoleById(Snowflake.of(discordServerProperties.getMutedRoleSnowflake())).block();
            updateMutedRoleInAllChannels(guild, mutedRole1);
        }
        discordServerProperties.refresh();
        Member memberToMute = guild.getMemberById(Snowflake.of(userIdSnowflake)).block();

        if (memberToMute != null) {
            memberToMute.addRole(Snowflake.of(discordServerProperties.getMutedRoleSnowflake())).block();
        }

    }

    public void updateMutedRoleInAllChannels(Guild guild, Role mutedRole) {

        guild.getChannels().ofType(TextChannel.class)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(textChannel -> textChannel.edit(TextChannelEditSpec.builder()
                                .addPermissionOverwrite(PermissionOverwrite.forRole(mutedRole.getId(),
                                        PermissionSet.none(),
                                        PermissionSet.of(
                                                discord4j.rest.util.Permission.SEND_MESSAGES,
                                                discord4j.rest.util.Permission.ADD_REACTIONS,
                                                discord4j.rest.util.Permission.CHANGE_NICKNAME
                                        )))
                                .build()))
                .subscribe();

        guild.getChannels().ofType(VoiceChannel.class)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(voiceChannel -> voiceChannel.edit(VoiceChannelEditSpec.builder()
                        .addPermissionOverwrite(PermissionOverwrite.forRole(mutedRole.getId(),
                                PermissionSet.none(),
                                PermissionSet.of(
                                        discord4j.rest.util.Permission.SPEAK,
                                        discord4j.rest.util.Permission.PRIORITY_SPEAKER,
                                        discord4j.rest.util.Permission.STREAM
                                )))
                        .build()))
                .subscribe();

    }

    private boolean checkIfPunisherHasHighestRole(Member punisher, Member punished, Guild guild, SlashCommandEvent event) {
        if (permissionChecker.checkIsAdministrator(guild, punisher) && !permissionChecker.checkIsAdministrator(guild, punished)) {
            return true;
        } else if (permissionChecker.checkIsAdministrator(guild, punished) && !permissionChecker.checkIsAdministrator(guild, punisher)) {
            Notifier.notifyCommandUserOfError(event, "noPermission");
            AuditLogger.addCommandToDB(event, false);
            loggingListener.onAttemptedInsubordination(event, punished);
            return false;
        }

        Set<Snowflake> snowflakeSet = Set.copyOf(punished.getRoles().map(Role::getId).collectList().block());

        if (!guild.getSelfMember().block().hasHigherRoles(snowflakeSet).defaultIfEmpty(false).block()) {
            Notifier.notifyCommandUserOfError(event, "botRoleTooLow");
            AuditLogger.addCommandToDB(event, false);
            return false;
        }

        if (!punisher.hasHigherRoles(snowflakeSet).defaultIfEmpty(false).block()) {
            Notifier.notifyCommandUserOfError(event, "noPermission");
            AuditLogger.addCommandToDB(event, false);
            loggingListener.onAttemptedInsubordination(event, punished);
            return false;
        } else {
            return true;
        }
    }
}