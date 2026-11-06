package sh.libre.scim.event;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.*;

import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.util.JsonSerialization;

import sh.libre.scim.core.GroupAdapter;
import sh.libre.scim.core.ScimDispatcher;
import sh.libre.scim.core.UserAdapter;

public class ScimEventListenerProvider implements EventListenerProvider {
    final Logger LOGGER = Logger.getLogger(ScimEventListenerProvider.class);
    ScimDispatcher dispatcher;
    KeycloakSession session;
    HashMap<ResourceType, Pattern> patterns = new HashMap<ResourceType, Pattern>();

    public ScimEventListenerProvider(KeycloakSession session) {
        this.session = session;
        dispatcher = new ScimDispatcher(session);
        patterns.put(ResourceType.USER, Pattern.compile("users/(.+)"));
        patterns.put(ResourceType.GROUP, Pattern.compile("groups/([\\w-]+)(/children)?"));
        patterns.put(ResourceType.GROUP_MEMBERSHIP, Pattern.compile("users/(.+)/groups/(.+)"));
        patterns.put(ResourceType.REALM_ROLE_MAPPING, Pattern.compile("^(.+)/(.+)/role-mappings"));
    }

    @Override
    public void close() {
    }

    @Override
    public void onEvent(Event event) {
        if (event.getType() == EventType.VERIFY_EMAIL) {
            var user = getUser(event.getUserId());
            if (user.isEmailVerified()){
                dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.create(UserAdapter.class, user));
            }
        }
        if (event.getType() == EventType.UPDATE_EMAIL || event.getType() == EventType.UPDATE_PROFILE) {
            var user = getUser(event.getUserId());
            dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.replace(UserAdapter.class, user));
        }
        if (event.getType() == EventType.DELETE_ACCOUNT) {
            dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.delete(UserAdapter.class, event.getUserId()));
        }
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        if (event.getResourceType() == ResourceType.GROUP && "groups".equals(event.getResourcePath())) {
            onTopLevelGroupEvent(event);
            return;
        }
        var pattern = patterns.get(event.getResourceType());
        if (pattern == null) {
            return;
        }
        var matcher = pattern.matcher(event.getResourcePath());
        if (!matcher.find()) {
            return;
        }
        if (event.getResourceType() == ResourceType.USER) {
            var userId = matcher.group(1);
            LOGGER.infof("%s %s", userId, event.getOperationType());
            if (event.getOperationType() == OperationType.CREATE) {
                var user = getUser(userId);
                if (user.isEmailVerified()) {
                    dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.create(UserAdapter.class, user));
                    user.getGroupsStream().forEach(group -> {
                        dispatcher.run(ScimDispatcher.SCOPE_GROUP, client -> client.replace(GroupAdapter.class, group));
                    });
                }
            }
            if (event.getOperationType() == OperationType.UPDATE) {
                var user = getUser(userId);
                if (user.isEmailVerified()) {
                    dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.replace(UserAdapter.class, user));
                }
            }
            if (event.getOperationType() == OperationType.DELETE) {
                // The user is already removed when this event fires, so it can't be loaded here.
                // ScimClient.delete only acts when a SCIM mapping exists for this id.
                dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.delete(UserAdapter.class, userId));
            }
        }
        if (event.getResourceType() == ResourceType.GROUP) {
            var groupId = matcher.group(1);
            LOGGER.infof("group %s %s", groupId, event.getOperationType());
            if (matcher.group(2) != null) {
                // groups/{parentId}/children: a child was created or moved under the parent.
                if (event.getOperationType() != OperationType.DELETE) {
                    var parent = getGroup(groupId);
                    if (parent != null) {
                        syncGroups(descendants(parent));
                    }
                }
                return;
            }
            if (event.getOperationType() == OperationType.CREATE) {
                var group = getGroup(groupId);
                dispatcher.run(ScimDispatcher.SCOPE_GROUP, client -> client.create(GroupAdapter.class, group));
            }
            if (event.getOperationType() == OperationType.UPDATE) {
                // A rename changes the path of all descendants, so re-evaluate them too.
                var group = getGroup(groupId);
                var groups = descendants(group);
                groups.add(0, group);
                syncGroups(groups);
            }
            if (event.getOperationType() == OperationType.DELETE) {
                dispatcher.run(ScimDispatcher.SCOPE_GROUP,
                        client -> client.delete(GroupAdapter.class, groupId));
            }
        }
        if (event.getResourceType() == ResourceType.GROUP_MEMBERSHIP) {
            var userId = matcher.group(1);
            var groupId = matcher.group(2);
            LOGGER.infof("%s %s from %s", event.getOperationType(), userId, groupId);
            // User first: if it enters the scope it must exist remotely before the group lists it.
            var user = getUser(userId);
            dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.replace(UserAdapter.class, user));
            var group = getGroup(groupId);
            dispatcher.run(ScimDispatcher.SCOPE_GROUP, client -> client.replace(GroupAdapter.class, group));
        }
        if (event.getResourceType() == ResourceType.REALM_ROLE_MAPPING) {
            var type = matcher.group(1);
            var id = matcher.group(2);
            LOGGER.infof("%s %s %s roles", event.getOperationType(), type, id);
            if ("users".equals(type)) {
                var user = getUser(id);
                dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.replace(UserAdapter.class, user));
            } else if ("groups".equals(type)) {
                var group = getGroup(id);
                session.users().getGroupMembersStream(session.getContext().getRealm(), group).forEach(user -> {
                    dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.replace(UserAdapter.class, user));
                });
            }
        }
    }

    /**
     * Moving an existing group to the top level is reported with the bare path "groups";
     * the group id is only available in the event representation.
     */
    private void onTopLevelGroupEvent(AdminEvent event) {
        if (event.getOperationType() == OperationType.DELETE) {
            return;
        }
        var representation = event.getRepresentation();
        if (representation == null) {
            LOGGER.warn("group moved to the top level, but the admin event has no representation. "
                    + "Enable \"Include representation\" for admin events, or wait for the next sync.");
            return;
        }
        String groupId;
        try {
            groupId = JsonSerialization.readValue(representation, GroupRepresentation.class).getId();
        } catch (IOException e) {
            LOGGER.warn("failed to read group from admin event representation", e);
            return;
        }
        var group = groupId == null ? null : getGroup(groupId);
        if (group == null) {
            return;
        }
        LOGGER.infof("group %s moved to top level", groupId);
        var groups = descendants(group);
        groups.add(0, group);
        syncGroups(groups);
    }

    private List<GroupModel> descendants(GroupModel group) {
        var result = new ArrayList<GroupModel>();
        group.getSubGroupsStream().forEach(child -> {
            result.add(child);
            result.addAll(descendants(child));
        });
        return result;
    }

    /** Replace groups, then their direct members, then the groups again (so member lists are complete). */
    private void syncGroups(List<GroupModel> groups) {
        for (var group : groups) {
            dispatcher.run(ScimDispatcher.SCOPE_GROUP, client -> client.replace(GroupAdapter.class, group));
        }
        Map<String, UserModel> members = new LinkedHashMap<>();
        for (var group : groups) {
            session.users().getGroupMembersStream(session.getContext().getRealm(), group)
                    .forEach(u -> members.putIfAbsent(u.getId(), u));
        }
        for (var user : members.values()) {
            dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.replace(UserAdapter.class, user));
        }
        for (var group : groups) {
            dispatcher.run(ScimDispatcher.SCOPE_GROUP, client -> client.replace(GroupAdapter.class, group));
        }
    }

    private UserModel getUser(String id) {
        return session.users().getUserById(session.getContext().getRealm(), id);
    }

    private GroupModel getGroup(String id) {
        return session.groups().getGroupById(session.getContext().getRealm(), id);
    }
}
