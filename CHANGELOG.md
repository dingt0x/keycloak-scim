# Changelog

All notable changes to this project are documented here.
Versions are `{Keycloak version}-{plugin version}`: the first part is the Keycloak
version the build is tested against, the second is the plugin's own version.
A release is published by pushing the tag `v{version}`, e.g. `v26.8.1-1.0.0`.

## [26.8.1-1.0.0] - 2026-11-06

First release of this fork, built and tested against Keycloak 26.8.1.

### Added
- `group-filter` (required): Java regex matched against the full group path
  (e.g. `/aws/dev`). Only matching groups, and users directly in a matching group,
  are pushed. Users leaving the scope are set to `active=false`; groups leaving it
  are deleted from the remote. Invalid or empty values are rejected on save.
- `user-name-source`: send the Keycloak `username` or the `email` as SCIM
  `userName`, for remotes that match the SAML NameID (email) against `userName`.
- Gradle wrapper (`./gradlew shadowJar`).
- GitHub Actions: CI on every push and pull request; release on `v*` tags that
  publishes `keycloak-scim.jar`.

### Changed
- Keycloak 26 only (26.8.1). Legacy Keycloak modules and Wildfly files removed.
- The jar is `keycloak-scim.jar` and no longer bundles libraries that Keycloak
  already ships (BouncyCastle, Apache HttpClient, Jackson, commons-io,
  commons-lang3, SLF4J, Jakarta APIs).
- The sync result in the admin console counts users only; group counts are logged.
- Errors are logged with their cause and stack trace.

### Fixed
- Basic auth read credentials from the wrong config keys.
- Import requested an invalid URL; a failed import no longer stops the refresh.
- A mapping was saved after a failed create, and removed after a failed delete.
- Deleting a user from the admin console was not propagated.
- New subgroups and groups moved to the top level were not pushed.
- A group member without a mapping failed the whole group.
- String comparisons with `==`, and several null pointer errors on missing fields.

### Security
- TLS hostname verification is no longer disabled.
