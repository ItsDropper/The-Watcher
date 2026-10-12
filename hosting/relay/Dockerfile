FROM node:22-alpine

ENV NODE_ENV=production
WORKDIR /app

COPY package.json ./
RUN npm install --omit=dev --ignore-scripts && npm cache clean --force

COPY server.mjs ./
USER node

EXPOSE 9443
CMD ["node", "server.mjs"]
