package dev.denismasterherobrine.animatio.anim.runtime;

import dev.denismasterherobrine.animatio.api.AnimationController;
import dev.denismasterherobrine.animatio.api.event.EventContext;

/**
 * Base for owner-specific event contexts. The controller fills in the clip and track before each event.
 */
public abstract class BaseEventContext implements EventContext {
    private String clip = "";
    private String trackKey = "";
    protected ControllerImpl controller;

    void prepare(ControllerImpl controller, String clip, String trackKey) {
        this.controller = controller;
        this.clip = clip;
        this.trackKey = trackKey;
    }

    @Override
    public AnimationController controller() {
        return controller;
    }

    @Override
    public String clip() {
        return clip;
    }

    @Override
    public String trackKey() {
        return trackKey;
    }
}
