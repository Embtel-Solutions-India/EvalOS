FROM node:20-alpine AS build

WORKDIR /app

COPY frontend/package*.json ./

RUN npm install

COPY frontend/ ./frontend/
COPY packages/ ./packages/

WORKDIR /app/frontend

RUN npm run build


FROM nginx:alpine

COPY --from=build /app/frontend/dist /usr/share/nginx/html

COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf

COPY frontend/ssl/origin-cert.pem /etc/nginx/ssl/origin-cert.pem
COPY frontend/ssl/origin-key.pem /etc/nginx/ssl/origin-key.pem

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
