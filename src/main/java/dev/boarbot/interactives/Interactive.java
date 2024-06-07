package dev.boarbot.interactives;

import dev.boarbot.BoarBotApp;
import dev.boarbot.bot.config.BotConfig;
import dev.boarbot.util.interactive.InteractiveUtil;
import dev.boarbot.util.interactive.StopType;
import dev.boarbot.util.time.TimeUtil;
import lombok.extern.log4j.Log4j2;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent;
import net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction;
import net.dv8tion.jda.api.interactions.components.ActionRow;

import java.io.IOException;
import java.util.concurrent.Executors;

@Log4j2
public abstract class Interactive {
    protected final BotConfig config = BoarBotApp.getBot().getConfig();

    protected final SlashCommandInteractionEvent initEvent;
    protected final SlashCommandInteraction interaction;
    protected final User user;

    protected long curStopTime = TimeUtil.getCurMilli() + this.config.getNumberConfig().getCollectorIdle();
    protected long hardStopTime = TimeUtil.getCurMilli() + this.config.getNumberConfig().getCollectorHardStop();
    protected long lastEndTime = 0;
    protected boolean isStopped = false;

    protected Interactive(SlashCommandInteractionEvent initEvent) {
        this.initEvent = initEvent;
        this.interaction = initEvent.getInteraction();
        this.user = initEvent.getUser();

        String duplicateInteractiveKey = InteractiveUtil.findDuplicateInteractive(this.user.getId(), this.getClass());

        if (duplicateInteractiveKey != null) {
            try {
                BoarBotApp.getBot().getInteractives().get(duplicateInteractiveKey).stop(StopType.EXPIRED);
            } catch (Exception exception) {
                log.error("Something went wrong when terminating interactive!", exception);
                return;
            }
        }

        BoarBotApp.getBot().getInteractives().put(this.interaction.getId() + this.user.getId(), this);

        Executors.newSingleThreadExecutor().submit(() -> this.tryStop(
            this.config.getNumberConfig().getCollectorIdle()
        ));
    }

    public synchronized void attemptExecute(GenericComponentInteractionCreateEvent compEvent, long startTime) {
        if (startTime < this.lastEndTime) {
            return;
        }

        this.curStopTime = TimeUtil.getCurMilli() + this.config.getNumberConfig().getCollectorIdle();

        this.execute(compEvent);

        this.lastEndTime = TimeUtil.getCurMilli();
    }

    public abstract void execute(GenericComponentInteractionCreateEvent compEvent);
    public abstract ActionRow[] getCurComponents();

    private void tryStop(long waitTime) {
        try {
            Thread.sleep(waitTime);
        } catch (InterruptedException exception) {
            try {
                this.stop(StopType.EXPIRED);
            } catch (Exception ignored) {}
        }

        long curTime = TimeUtil.getCurMilli();

        if (this.curStopTime <= curTime || this.hardStopTime <= curTime) {
            try {
                this.stop(StopType.EXPIRED);
            } catch (Exception ignored) {}
        } else {
            long newWaitTime = Math.min(this.curStopTime - curTime, this.hardStopTime - curTime);
            this.tryStop(newWaitTime);
        }
    }

    public void stop(StopType type) throws IOException, InterruptedException {
        Interactive interactive = this.removeInteractive();
        this.isStopped = true;

        if (interactive == null) {
            return;
        }

        this.interaction.getHook().editOriginalComponents().complete();
    }

    public boolean isStopped() {
        return this.isStopped;
    }

    public Interactive removeInteractive() {
        return BoarBotApp.getBot().getInteractives().remove(this.interaction.getId() + this.user.getId());
    }
}