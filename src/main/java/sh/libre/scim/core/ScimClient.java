package sh.libre.scim.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.ws.rs.ProcessingException;

import de.captaingoldfish.scim.sdk.client.ScimClientConfig;
import de.captaingoldfish.scim.sdk.client.ScimRequestBuilder;
import de.captaingoldfish.scim.sdk.client.http.BasicAuth;
import de.captaingoldfish.scim.sdk.client.response.ServerResponse;
import de.captaingoldfish.scim.sdk.common.exceptions.ResponseException;
import de.captaingoldfish.scim.sdk.common.resources.ResourceNode;
import de.captaingoldfish.scim.sdk.common.response.ListResponse;

import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;
import org.keycloak.models.RoleMapperModel;
import org.keycloak.storage.user.SynchronizationResult;

import com.google.common.net.HttpHeaders;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;


public class ScimClient {
    final protected Logger LOGGER = Logger.getLogger(ScimClient.class);
    final protected ScimRequestBuilder scimRequestBuilder;
    final protected RetryRegistry registry;
    final protected KeycloakSession session;
    final protected String contentType;
    final protected ComponentModel model;
    final protected String scimApplicationBaseUrl;
    final protected Map<String, String> defaultHeaders;
    final protected Map<String, String> expectedResponseHeaders;
    final protected GroupFilter groupFilter;

    public ScimClient(ComponentModel model, KeycloakSession session) {
        this.model = model;
        this.groupFilter = new GroupFilter(model.get("group-filter"));
        this.contentType = model.get("content-type");
        this.session = session;
        this.scimApplicationBaseUrl = model.get("endpoint");
        this.defaultHeaders = new HashMap<>();
        this.expectedResponseHeaders = new HashMap<>();

        switch (model.get("auth-mode")) {
            case "BEARER":
                defaultHeaders.put(HttpHeaders.AUTHORIZATION,
                    BearerAuthentication(model.get("auth-pass")));
                break;
            case "BASIC_AUTH":
                defaultHeaders.put(HttpHeaders.AUTHORIZATION,
                    BasicAuthentication(model.get("auth-user"),
                                        model.get("auth-pass")));
                break;
        }

        defaultHeaders.put(HttpHeaders.CONTENT_TYPE, contentType);

        scimRequestBuilder = new ScimRequestBuilder(scimApplicationBaseUrl, genScimClientConfig());

        RetryConfig retryConfig = RetryConfig.custom()
            .maxAttempts(10)
            .intervalFunction(IntervalFunction.ofExponentialBackoff())
            .retryExceptions(ProcessingException.class)
            .build();

        registry = RetryRegistry.of(retryConfig);
    }

    protected String BasicAuthentication(String username, String password) {
        return  BasicAuth.builder()
        .username(username)
        .password(password)
        .build()
        .getAuthorizationHeaderValue();
    }

    protected ScimClientConfig genScimClientConfig() {
        return ScimClientConfig.builder()
        .httpHeaders(defaultHeaders)
        .connectTimeout(5)
        .requestTimeout(5)
        .socketTimeout(5)
        .expectedHttpResponseHeaders(expectedResponseHeaders)
        .build();
    }

    protected String BearerAuthentication(String token) {
        return "Bearer " + token ;
    }

    protected String genScimUrl(String scimEndpoint, String resourcePath) {
        return "%s/%s/%s".formatted(scimApplicationBaseUrl,
                scimEndpoint,
                resourcePath);
    }


    protected EntityManager getEM() {
        return session.getProvider(JpaConnectionProvider.class).getEntityManager();
    }

    protected String getRealmId() {
        return session.getContext().getRealm().getId();
    }

    protected <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> A getAdapter(
            Class<A> aClass) {
        try {
            return aClass.getDeclaredConstructor(KeycloakSession.class, String.class)
                    .newInstance(session, this.model.getId());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected boolean inScope(RoleMapperModel kcModel) {
        if (kcModel instanceof UserModel) {
            return groupFilter.inScope((UserModel) kcModel);
        }
        if (kcModel instanceof GroupModel) {
            return groupFilter.inScope((GroupModel) kcModel);
        }
        return true;
    }

    /** @return TRUE if created on the remote, FALSE if the remote request failed, null if nothing was done. */
    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> Boolean create(Class<A> aClass,
            M kcModel) {
        if (groupFilter.isInvalid() || !inScope(kcModel)) {
            return null;
        }
        var adapter = getAdapter(aClass);
        adapter.apply(kcModel);
        if (adapter.skip) {
            return null;
        }
        // If mapping exist then it was created by import so skip.
        if (adapter.query("findById", adapter.getId()).getResultList().size() != 0) {
            return null;
        }
        var retry = registry.retry("create-" + adapter.getId());

        ServerResponse<S> response = retry.executeSupplier(() -> {
            try {
                return scimRequestBuilder
                .create(adapter.getResourceClass(), "/" + adapter.getSCIMEndpoint())
                .setResource(adapter.toSCIM(false))
                .sendRequest();
            } catch (ResponseException e) {
                throw new RuntimeException(e);
            }
        });

        if (!response.isSuccess()){
            LOGGER.warn(response.getResponseBody());
            LOGGER.warn(response.getHttpStatus());
            return false;
        }

        adapter.apply(response.getResource());
        adapter.saveMapping();
        return true;
    }

    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> void replace(Class<A> aClass,
            M kcModel) {
        if (groupFilter.isInvalid()) {
            return;
        }
        var adapter = getAdapter(aClass);
        try {
            adapter.apply(kcModel);
            if (adapter.skip) {
                return;
            }
            var resource = adapter.query("findById", adapter.getId()).getSingleResult();
            adapter.apply(resource);
            boolean inScope = inScope(kcModel);
            if (adapter instanceof GroupAdapter && !inScope) {
                // Group no longer matches (renamed/moved): remove it from the remote.
                delete(aClass, adapter.getId());
                return;
            }
            if (adapter instanceof UserAdapter) {
                ((UserAdapter) adapter).overrideActive(((UserModel) kcModel).isEnabled() && inScope);
            }
            String url = genScimUrl(adapter.getSCIMEndpoint(), adapter.getExternalId());
            var retry = registry.retry("replace-" + adapter.getId());
            ServerResponse<S> response = retry.executeSupplier(() -> {
                try {
                    if (("Group".equals(adapter.getType()) && this.model.get("group-patchOp", false))
                         || ("User".equals(adapter.getType()) && this.model.get("user-patchOp", false))) {
                        return adapter.toPatchBuilder(scimRequestBuilder, url)
                                      .sendRequest();
                    }
                    else {
                        return scimRequestBuilder
                            .update(url, adapter.getResourceClass())
                            .setResource(adapter.toSCIM(false))
                            .sendRequest();
                    }
                } catch (ResponseException e) {
                    throw new RuntimeException(e);
                }
            });
            if (!response.isSuccess()){
                LOGGER.warn(response.getResponseBody());
                LOGGER.warn(response.getHttpStatus());
            }
        } catch (NoResultException e) {
            // No remote copy yet. In scope: create it (user entering the scope, or a new subgroup
            // reported as a replace). Out of scope: nothing to do.
            if (inScope(kcModel)) {
                create(aClass, kcModel);
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> void delete(Class<A> aClass,
            String id) {
        var adapter = getAdapter(aClass);
        adapter.setId(id);

        try {
            var resource = adapter.query("findById", adapter.getId()).getSingleResult();
            adapter.apply(resource);

            var retry = registry.retry("delete-" + id);

            ServerResponse<S> response = retry.executeSupplier(() -> {
                try {
                    return scimRequestBuilder.delete(genScimUrl(adapter.getSCIMEndpoint(), adapter.getExternalId()),
                                                                adapter.getResourceClass())
                                             .sendRequest();
                } catch (ResponseException e) {
                    throw new RuntimeException(e);
                }
            });

            if (!response.isSuccess() && response.getHttpStatus() != 404) {
                LOGGER.warn(response.getResponseBody());
                LOGGER.warn(response.getHttpStatus());
                return;
            }

            getEM().remove(resource);

        } catch (NoResultException e) {
            LOGGER.warnf("Failed to delete resource %s, scim mapping not found", id);
        }
    }

    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> void refreshResources(
            Class<A> aClass,
            SynchronizationResult syncRes) {
        LOGGER.info("Refresh resources");
        getAdapter(aClass).getResourceStream().forEach(resource -> {
            var adapter = getAdapter(aClass);
            adapter.apply(resource);
            LOGGER.infof("Reconciling local resource %s", adapter.getId());
            if (!adapter.skipRefresh()) {
                var mapping = adapter.getMapping();
                if (mapping == null) {
                    if (groupFilter.isInvalid() || !inScope(resource)) {
                        // Not on the remote and not in scope: nothing to push.
                        return;
                    }
                    LOGGER.info("Creating it");
                    var created = this.create(aClass, resource);
                    if (Boolean.TRUE.equals(created)) {
                        syncRes.increaseAdded();
                    } else if (Boolean.FALSE.equals(created)) {
                        syncRes.increaseFailed();
                    }
                } else {
                    LOGGER.info("Replacing it");
                    this.replace(aClass, resource);
                    syncRes.increaseUpdated();
                }
            }
        });

    }

    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> void importResources(
            Class<A> aClass, SynchronizationResult syncRes) {
        LOGGER.info("Import");
        try {
            var adapter = getAdapter(aClass);
            ServerResponse<ListResponse<S>> response  = scimRequestBuilder.list(adapter.getResourceClass(), "/" + adapter.getSCIMEndpoint()).get().sendRequest();
            if (!response.isSuccess()) {
                LOGGER.warnf("failed to list remote resources, status: %s, body: %s",
                             response.getHttpStatus(), response.getResponseBody());
                syncRes.increaseFailed();
                return;
            }
            ListResponse<S> resourceTypeListResponse = response.getResource();

            for (var resource : resourceTypeListResponse.getListedResources()) {
                try {
                    LOGGER.infof("Reconciling remote resource %s", resource);
                    adapter = getAdapter(aClass);
                    adapter.apply(resource);

                    var mapping = adapter.getMapping();
                    if (mapping != null) {
                        adapter.apply(mapping);
                        if (adapter.entityExists()) {
                            LOGGER.info("Valid mapping found, skipping");
                            continue;
                        } else {
                            LOGGER.info("Delete a dangling mapping");
                            adapter.deleteMapping();
                        }
                    }

                    var mapped = adapter.tryToMap();
                    if (mapped) {
                        LOGGER.info("Matched");
                        adapter.saveMapping();
                    } else {
                        switch (Objects.requireNonNullElse(this.model.get("sync-import-action"), "NOTHING")) {
                            case "CREATE_LOCAL":
                                LOGGER.info("Create local resource");
                                try {
                                    adapter.createEntity();
                                    adapter.saveMapping();
                                    syncRes.increaseAdded();
                                } catch (Exception e) {
                                    LOGGER.error(e.getMessage(), e);
                                }
                                break;
                            case "DELETE_REMOTE":
                                LOGGER.info("Delete remote resource");
                                if (adapter.getExternalId() == null) {
                                    LOGGER.warn("remote resource has no id, can't delete it");
                                    syncRes.increaseFailed();
                                    break;
                                }
                                ServerResponse<S> deleteResponse = scimRequestBuilder
                                    .delete(genScimUrl(adapter.getSCIMEndpoint(),
                                                       adapter.getExternalId()),
                                                       adapter.getResourceClass())
                                    .sendRequest();
                                if (deleteResponse.isSuccess()) {
                                    syncRes.increaseRemoved();
                                } else {
                                    LOGGER.warnf("failed to delete remote resource %s, status: %s, body: %s",
                                                 adapter.getExternalId(), deleteResponse.getHttpStatus(),
                                                 deleteResponse.getResponseBody());
                                }
                                break;
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error(e.getMessage(), e);
                    syncRes.increaseFailed();
                }
            }
        } catch (ResponseException e) {
            throw new RuntimeException(e);
        }
    }

    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> void sync(Class<A> aClass,
            SynchronizationResult syncRes) {
        if (this.model.get("sync-import", false)) {
            try {
                this.importResources(aClass, syncRes);
            } catch (Exception e) {
                // A failed import must not prevent the refresh below.
                LOGGER.error("import failed", e);
                syncRes.increaseFailed();
            }
        }
        if (this.model.get("sync-refresh", false)) {
            this.refreshResources(aClass, syncRes);
        }
    }

    public void close() {
        scimRequestBuilder.close();
    }
}
