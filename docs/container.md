# Container

## Quarkus

```
FROM quay.io/keycloak/keycloak:26.8.1 as builder
# Replace <release-url> with the download URL of keycloak-scim.jar from a GitHub Release.
RUN curl -fL "<release-url>" -o /opt/keycloak/providers/keycloak-scim.jar
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8.1
COPY --from=builder /opt/keycloak/ /opt/keycloak/
WORKDIR /opt/keycloak
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start"]
```
