package sh.libre.scim.core;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.jboss.logging.Logger;
import org.keycloak.models.GroupModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * Decides whether a group or user is in the scope of a SCIM remote, based on a
 * regex matched (find) against the full group path (e.g. /aws/dev).
 * A blank regex matches nothing.
 */
public class GroupFilter {
    private static final Logger LOGGER = Logger.getLogger(GroupFilter.class);

    private final Pattern pattern;
    private final boolean invalid;

    public GroupFilter(String regex) {
        Pattern compiled = null;
        boolean bad = false;
        if (regex != null && !regex.isBlank()) {
            try {
                compiled = Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                LOGGER.errorf("invalid group-filter regex '%s': %s. Nothing will be pushed to this remote.",
                        regex, e.getMessage());
                bad = true;
            }
        }
        this.pattern = compiled;
        this.invalid = bad;
    }

    /** True when the regex cannot be compiled: the remote must not be touched at all. */
    public boolean isInvalid() {
        return invalid;
    }

    public boolean inScope(GroupModel group) {
        return pattern != null && pattern.matcher(KeycloakModelUtils.buildGroupPath(group)).find();
    }

    /** A user is in scope when at least one direct group matches. */
    public boolean inScope(UserModel user) {
        return pattern != null && user.getGroupsStream().anyMatch(this::inScope);
    }
}
