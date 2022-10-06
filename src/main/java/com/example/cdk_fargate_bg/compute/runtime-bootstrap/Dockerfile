# nginx state for serving content
FROM public.ecr.aws/nginx/nginx:alpine
##TEST USING THE FOLLOWING VERSION public.ecr.aws/nginx/nginx:1.20.0-alpine
# Set working directory to nginx asset directory
WORKDIR /usr/share/nginx/html
# Remove default nginx static assets
RUN rm -rf ./*
# Copy static assets over
COPY ./index.html ./
COPY ./nginx.conf /etc/nginx/
# Containers run nginx with global directives and daemon off
ENTRYPOINT ["nginx", "-g", "daemon off;"]
