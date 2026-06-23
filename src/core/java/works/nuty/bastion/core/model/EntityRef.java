package works.nuty.bastion.core.model;

import java.util.UUID;

/** A reference to an entity that a pause source is attached to, used for in-world labelling. */
public record EntityRef(UUID uuid, String name) {
}
