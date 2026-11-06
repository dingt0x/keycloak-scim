# keycloak-scim-client

This extension add [SCIM2](http://www.simplecloud.info) client capabilities to Keycloak. (See [RFC7643](https://datatracker.ietf.org/doc/html/rfc7643) and [RFC7644](https://datatracker.ietf.org/doc/html/rfc7644)).

## Overview

### Motivation

We want to build a unified collaborative platform based on multiple applications. To do that, we need a way to propagate immediately changes made in Keycloak to all these applications. And we want to keep using OIDC or SAML as the authentication protocol.

This will allow users to collaborate seamlessly across the platform without requiring every user to have connected once to each application. This will also ease GDRP compliance because deleting a user in Keycloak will delete the user from every app.

### Technical choices

The SCIM protocol is standard, comprehensible and easy to implement. It's a perfect fit for our goal.

We chose to build application extensions/plugins because it's easier to deploy and thus will benefit to a larger portion of the FOSS community.

#### Keycloak specific

This extension uses 3 concepts in KC :
- Event Listener : it's used to listens for changes and transform them in SCIM calls.
- Federation Provider : it's used to set up all the SCIM service providers without creating our own UI.
- JPA Entity Provider : it's used to save the mapping between the local IDs and the service providers IDs.

Because the event listener is the source of the SCIM flow, and it is not cancelable, we can't have strictly consistent behavior in case of SCIM service provider failure. 

## Usage

### Compatibility

Keycloak 26 (built and tested against 26.8.1).

### Build

```sh
./gradlew shadowJar
```

The provider jar is written to `build/libs/keycloak-scim.jar`. Libraries that Keycloak already ships (BouncyCastle, Apache HttpClient, Jackson, commons-io, commons-lang3, SLF4J, Jakarta APIs) are not bundled.

### Installation (quick)

1. Download `keycloak-scim.jar` from the GitHub Releases of this repository, or build it.
2. Put it in `/opt/keycloak/providers/`.

It's also possible to build your own custom image if you run Keycloak in a [container](/docs/container.md).

Other [installation options](/docs/installation.md) are available.

### Setup

#### Add the event listerner

1. Go to `Admin Console > Events > Config`.
2. Add `scim` in `Event Listeners`.
3. Save.

![Event listener page](/docs/img/event-listener-page.png)

#### Create a federation provider

1. Go to `Admin Console > User Federation`.
2. Click on `Add provider`.
3. Select `scim`.
4. Configure the provider ([see](#configuration)).
5. Save.

![Federation provider page](/docs/img/federation-provider-page.png)

### Configuration

Add the endpoint - for a local set up you have to add the two containers in a docker network and use the container ip see [here](https://docs.docker.com/engine/reference/commandline/network/)
If you use the [rocketchat app](https://lab.libreho.st/libre.sh/scim/rocketchat-scim) you get the endpoint from your rocket Chat Scim Adapter App Details.
Endpoint content type is application/json.
Auth mode Bearer or None for local test setup.
Copy the bearer token from your app details in rocketchat.

If you enable import during sync then you can choose between to following import actions:
- Create Local - adds users to keycloak
- Nothing
- Delete Remote - deletes users from the remote application




### Group path filter

The `group-filter` setting restricts what is pushed to a SCIM endpoint. It is a Java regex that is searched (`find`) in the **full path** of a group, e.g. `/aws/dev`.

- A group is pushed only if its path matches.
- A user is in scope only if they are a **direct** member of at least one matching group (parent groups are not considered).
- Examples:
  - `^/aws(/.*)?$` : `/aws` and everything below it.
  - `^/aws/.*` : only the sub-groups of `/aws`, not `/aws` itself.
  - `^/aws$` : only `/aws` itself, not its sub-groups.
- When a user leaves the scope, the remote account is kept but updated with `active=false`. When a group no longer matches (renamed, moved), it is deleted from the remote.
- The setting is **required**: an empty value is rejected on save. Use `.*` to push every group and every user that belongs to at least one group. Users without any group are never pushed.
- An invalid regex is rejected on save; if an existing one is invalid at runtime, nothing is pushed to that endpoint.

### SCIM userName source

The `user-name-source` setting chooses which Keycloak field is sent as the SCIM `userName`:

- `username` (default): the Keycloak username.
- `email`: the user's email. Use it when the remote matches the SAML NameID (configured as email) against the `userName`, e.g. Alibaba Cloud CloudSSO.

Users without a value for the chosen field are not pushed. Changing the setting updates the `userName` of already pushed users on their next update or sync; they stay linked through their remote id.

### Sync

You can set up a periodic sync for all users or just changed users. You can either do:
- Periodic Full Sync
- Periodic Changed User Sync


**[License AGPL](/LICENSE)**
