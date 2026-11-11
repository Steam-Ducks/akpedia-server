# Keycloak with the akpedia realm baked in, imported by --import-realm on the first start.
# Copied rather than bind-mounted: the dev container drives the host's Docker, which can't
# see paths inside the dev container.
FROM quay.io/keycloak/keycloak:26.7.4
COPY akpedia-realm.json /opt/keycloak/data/import/akpedia-realm.json
# Login pages in Akpedia's look (realm loginTheme "akpedia").
COPY themes/akpedia /opt/keycloak/themes/akpedia
