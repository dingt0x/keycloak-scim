package sh.libre.scim.storage;

import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import jakarta.ws.rs.core.MediaType;

import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.component.ComponentValidationException;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.KeycloakSessionTask;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import org.keycloak.storage.UserStorageProviderFactory;
import org.keycloak.storage.UserStorageProviderModel;
import org.keycloak.storage.user.ImportSynchronization;
import org.keycloak.storage.user.SynchronizationResult;

import sh.libre.scim.core.GroupAdapter;
import sh.libre.scim.core.ScimDispatcher;
import sh.libre.scim.core.UserAdapter;

import de.captaingoldfish.scim.sdk.common.constants.HttpHeader;

public class ScimStorageProviderFactory
        implements UserStorageProviderFactory<ScimStorageProvider>, ImportSynchronization {
    final private Logger LOGGER = Logger.getLogger(ScimStorageProviderFactory.class);
    public final static String ID = "scim";
    protected static final List<ProviderConfigProperty> configMetadata;
    static {
        configMetadata = ProviderConfigurationBuilder.create()
                .property()
                .name("endpoint")
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("SCIM 2.0 endpoint")
                .helpText("""
                        External SCIM 2.0 base \
                        URL (/ServiceProviderConfig  /Schemas and /ResourcesTypes should be accessible)\
                        """)
                .add()
                .property()
                .name("content-type")
                .type(ProviderConfigProperty.LIST_TYPE)
                .label("Endpoint content type")
                .helpText("Only used when endpoint doesn't support application/scim+json")
                .options(MediaType.APPLICATION_JSON.toString(), HttpHeader.SCIM_CONTENT_TYPE)
                .defaultValue(HttpHeader.SCIM_CONTENT_TYPE)
                .add()
                .property()
                .name("auth-mode")
                .type(ProviderConfigProperty.LIST_TYPE)
                .label("Auth mode")
                .helpText("Select the authorization mode")
                .options("NONE", "BASIC_AUTH", "BEARER")
                .defaultValue("NONE")
                .add()
                .property()
                .name("auth-user")
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("Auth username")
                .helpText("Required for basic authentification.")
                .add()
                .property()
                .name("auth-pass")
                .type(ProviderConfigProperty.PASSWORD)
                .label("Auth password/token")
                .helpText("Password or token required for basic or bearer authentification.")
                .add()
                .property()
                .name("user-name-source")
                .type(ProviderConfigProperty.LIST_TYPE)
                .label("SCIM userName source")
                .helpText("""
                        Keycloak field sent as the SCIM userName. \
                        Use email when the remote matches the SAML NameID (email) against the userName. \
                        Users without a value for this field are not pushed.\
                        """)
                .options(UserAdapter.USER_NAME_SOURCE_USERNAME, UserAdapter.USER_NAME_SOURCE_EMAIL)
                .defaultValue(UserAdapter.USER_NAME_SOURCE_USERNAME)
                .add()
                .property()
                .name("propagation-user")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .label("Enable user propagation")
                .defaultValue("true")
                .add()
                .property()
                .name("propagation-group")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .label("Enable group propagation")
                .defaultValue("true")
                .add()
                .property()
                .name("sync-import")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .label("Enable import during sync")
                .add()
                .property()
                .name("sync-import-action")
                .type(ProviderConfigProperty.LIST_TYPE)
                .label("Import action")
                .helpText("What to do when the user don\'t exists in Keycloak.")
                .options("NOTHING", "CREATE_LOCAL", "DELETE_REMOTE")
                .defaultValue("CREATE_LOCAL")
                .add()
                .property()
                .name("sync-refresh")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .label("Enable refresh during sync")
                .add()
                .property()
                .name("group-patchOp")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .label("Use patchOp for groups")
                .helpText("Only used when endpoint doesn't support putGroup API operation (full replace)")
                .defaultValue(false)
                .add()
                .property()
                .name("user-patchOp")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .label("Use patchOp for users")
                .helpText("Only used when endpoint doesn't support putUser API operation (full replace)")
                .defaultValue(false)
                .add()
                .property()
                .name("group-filter")
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("Group path filter (regex)")
                .helpText("""
                        Java regex matched against the full group path (e.g. /aws/dev). \
                        A group is pushed only if its path matches; a user is in scope only if \
                        directly member of at least one matching group. \
                        Required. Use .* to push every group and every user that belongs to a group.\
                        """)
                .required(true)
                .add()
                .build();
    }

    @Override
    public ScimStorageProvider create(KeycloakSession session, ComponentModel model) {
        LOGGER.info("create");
        return new ScimStorageProvider();
    }

    @Override
    public void validateConfiguration(KeycloakSession session, RealmModel realm, ComponentModel config)
            throws ComponentValidationException {
        var regex = config.get("group-filter");
        if (regex == null || regex.isBlank()) {
            throw new ComponentValidationException("Group path filter is required (use .* to match every group)");
        }
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new ComponentValidationException("Invalid group path filter regex: " + e.getMessage(), e);
        }
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return configMetadata;
    }

    @Override
    public SynchronizationResult sync(KeycloakSessionFactory sessionFactory, String realmId,
            UserStorageProviderModel model) {
        LOGGER.info("sync");
        // The admin console shows this result as "users", so it only counts users.
        // Group counts are logged separately.
        var result = new SynchronizationResult();
        var groupResult = new SynchronizationResult();
        KeycloakModelUtils.runJobInTransaction(sessionFactory, new KeycloakSessionTask() {

            @Override
            public void run(KeycloakSession session) {
                var realm = session.realms().getRealm(realmId);
                session.getContext().setRealm(realm);
                var dispatcher = new ScimDispatcher(session);
                if ("true".equals(model.get("propagation-user"))) {
                    dispatcher.runOne(model, client -> client.sync(UserAdapter.class, result));
                }
                if ("true".equals(model.get("propagation-group"))) {
                    dispatcher.runOne(model, client -> client.sync(GroupAdapter.class, groupResult));
                    LOGGER.infof("group sync: %d added, %d updated, %d removed, %d failed",
                            groupResult.getAdded(), groupResult.getUpdated(),
                            groupResult.getRemoved(), groupResult.getFailed());
                }
            }

        });

        return result;

    }

    @Override
    public SynchronizationResult syncSince(Date lastSync, KeycloakSessionFactory sessionFactory, String realmId,
            UserStorageProviderModel model) {
        return this.sync(sessionFactory, realmId, model);
    }

}
