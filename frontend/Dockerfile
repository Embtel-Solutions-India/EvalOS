FROM node:20-alpine AS build

WORKDIR /app

COPY package*.json ./

RUN npm install

COPY . .

RUN npm run build


FROM nginx:alpine

COPY --from=build /app/dist /usr/share/nginx/html

COPY nginx.conf /etc/nginx/conf.d/default.conf

COPY ssl/origin-cert.pem /etc/nginx/ssl/origin-cert.pem
COPY ssl/origin-key.pem /etc/nginx/ssl/origin-key.pem

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
