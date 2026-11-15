package dev.denismasterherobrine.animatio.api.event;

import dev.denismasterherobrine.animatio.api.AnimationController;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * What an {@link AnimationEvent} can see when it fires. Valid only during {@link AnimationEvent#fire}.
 */
public interface EventContext {
    /** The level of the animated object, or {@code null} outside a world (e.g. GUI previews). */
    @Nullable Level level();

    /** The animated object: an {@code Entity}, a {@code BlockEntity}, an {@code ItemStack}, or {@code null}. */
    @Nullable Object owner();

    AnimationController controller();

    /** Name of the clip whose timeline fired the event ({@code ""} for sequence {@code thenFire} steps). */
    String clip();

    /** Key of the track that fired the event. */
    String trackKey();

    /**
     * World-space position of a bone or socket, using the most recently evaluated pose.
     *
     * @return {@code false} if the bone does not exist or no pose is available, in which case {@code out}
     * is set to {@link #origin}
     */
    boolean bonePosition(String boneOrSocket, Vector3f out);

    /** World-space position of the model origin. */
    void origin(Vector3f out);
}
