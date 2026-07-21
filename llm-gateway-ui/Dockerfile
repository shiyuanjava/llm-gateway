# ---- 构建:Node 20 + Vite ----
FROM node:20-alpine AS build
WORKDIR /app
# 国内网络:npm 官方源慢,走 npmmirror(海外构建可删除)
RUN npm config set registry https://registry.npmmirror.com
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# 同源反代部署:VITE_API_BASE 留空,前端用相对路径经 nginx 反代到后端
RUN npm run build

# ---- 运行:nginx 托管静态文件并反代 /admin、/v1 ----
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
