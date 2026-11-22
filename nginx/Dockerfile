# Single entry point of the app: the front-end, the API and Keycloak on one address.
# Copied rather than bind-mounted, like the Keycloak image: the dev container drives the host's
# Docker, which can't see paths inside the dev container.
FROM nginx:1.27-alpine
COPY default.conf.template /etc/nginx/templates/default.conf.template
