# ---- 构建:Node 20 + Vite ----
FROM node:20-alpine AS build
WORKDIR /app
# 国内网络可保持默认镜像；海外/私有源构建时用 --build-arg NPM_REGISTRY=... 覆盖。
ARG NPM_REGISTRY=https://registry.npmmirror.com
RUN npm config set registry "$NPM_REGISTRY"
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# 同源反代默认留空；分域镜像可在构建时覆盖这些非敏感参数。
ARG VITE_API_BASE=
ARG VITE_API_TIMEOUT_MS=30000
ARG VITE_STREAM_TIMEOUT_MS=310000
RUN npm run build

# ---- 运行:nginx 托管静态文件并反代 /admin、/v1 ----
FROM nginx:alpine
ARG VITE_API_BASE=
COPY nginx.conf /tmp/default.conf
# 分域构建时自动把 API Origin 加进 CSP connect-src；同源/相对路径时保持仅 'self'。
RUN api_connect_src="$(printf '%s' "$VITE_API_BASE" | sed -nE 's#^(https?://[^/]+).*$#\1#p')" \
    && sed "s|__API_CONNECT_SRC__|$api_connect_src|g" /tmp/default.conf > /etc/nginx/conf.d/default.conf \
    && rm /tmp/default.conf
COPY nginx-proxy.conf /etc/nginx/gateway-proxy.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
