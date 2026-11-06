# Installation (advanced)

## 1. Get the jar file

Build it with `./gradlew shadowJar` (output: `build/libs/keycloak-scim.jar`), or download `keycloak-scim.jar` from the GitHub Releases of this repository. The jar contains the SCIM provider and the dependencies that Keycloak does not ship.

## 2. Install the jar file

Copy the jar file in `/opt/keycloak/providers/`. For production use, build Keycloak before starting it.
